package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Exact inspected business enum, provider, renderer, root representation and template-button code. */
internal object TemplateCleanupCodeGate {
    private val hashes = mapOf(
        "classes4.dex" to "2757ca393a37b513c91d4cbea971b9933e6bee17985a6cbb4d9f31c9b2f60c10",
        "classes5.dex" to "60d095d1bf85b222fa691453133c29b976b274d4ceed215a30603858ac5e18ff",
        "classes6.dex" to "98b4a71a732ac816310537df8e3642839213ca0f5c4806972159e48dfedc2688",
        "classes41.dex" to "6bcf090304f5cf97fa80597feb005530bd5eb1f305d01e25ee47a7e9c35ccae8")
    @Volatile private var checked: Boolean? = null
    @Synchronized fun verify(context: Context): Boolean {
        checked?.let { return it }
        val found = mutableSetOf<String>()
        try {
            val info = context.applicationInfo
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).forEach { path -> ZipFile(path).use { zip ->
                hashes.forEach entries@ { (name, hash) ->
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
                    if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == hash) found += name
                }
            } }
        } catch (_: Exception) { found.clear() }
        return (found == hashes.keys).also { checked = it }
    }
}
