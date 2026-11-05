package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Pin download completion, stored offline model enumeration and authenticated account/serialization contracts. */
internal object OfflineCommentDownloadContract {
    private val hashes = mapOf(
        "classes2.dex" to "ae46f96919190b1a2d99757ef2b72fd32bcfafc016ce7ff06f1101784dbb5dd1",
        "classes6.dex" to "98b4a71a732ac816310537df8e3642839213ca0f5c4806972159e48dfedc2688",
        "classes15.dex" to "d96d4d1964f3313ab6efac9d7dd9aa1cde4389b5290a2119ec00f056d8e0f51d",
        "classes25.dex" to "f86ff6155690b1bfd9746301eb453cb2804d995140415fcda714b528b908d23b")
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
