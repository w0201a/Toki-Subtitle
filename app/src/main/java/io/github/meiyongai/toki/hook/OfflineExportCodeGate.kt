package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.security.MessageDigest
import java.util.zip.ZipFile

/** New read-only raw-field contracts must match their inspected code independently of the old MDL reader. */
internal object OfflineExportCodeGate {
    private val hashes = mapOf(
        "classes2.dex" to "ae46f96919190b1a2d99757ef2b72fd32bcfafc016ce7ff06f1101784dbb5dd1",
        "classes4.dex" to "2757ca393a37b513c91d4cbea971b9933e6bee17985a6cbb4d9f31c9b2f60c10",
        "classes6.dex" to "98b4a71a732ac816310537df8e3642839213ca0f5c4806972159e48dfedc2688",
        "classes12.dex" to "ed6054e1f5836c56b4c1bb3e0f8d7ba5fbc0557654d1f4e7ba3edf52ea4b4b61")
    @Volatile private var ready: Boolean? = null
    @Synchronized fun verify(context: Context): Boolean {
        ready?.let { return it }
        val matched = mutableSetOf<String>()
        try {
            val info = context.applicationInfo
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).forEach { path -> ZipFile(path).use { zip ->
                hashes.forEach entries@ { (name, expected) ->
                    val entry = zip.getEntry(name) ?: return@entries
                    require(entry.size in 1..32L * 1024 * 1024)
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) return false
                            val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
                        }
                    }
                    if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected) matched += name
                }
            } }
        } catch (_: Exception) { matched.clear() }
        return (matched == hashes.keys).also { ready = it }
    }
}
