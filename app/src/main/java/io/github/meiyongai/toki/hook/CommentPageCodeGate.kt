package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Exact request-builder and Rx contracts were read from these original 47.6.3 DEX files. */
internal object CommentPageCodeGate {
    private val expected = mapOf(
        "classes2.dex" to "ae46f96919190b1a2d99757ef2b72fd32bcfafc016ce7ff06f1101784dbb5dd1",
        "classes8.dex" to "733a8169bb16c61256cf7de528539bcace48c7778e155edb6dcdc4775de15cd3",
    )
    @Volatile private var result: Boolean? = null
    @Synchronized fun verify(context: Context): Boolean {
        result?.let { return it }
        val info = context.applicationInfo
        val matches = mutableSetOf<String>()
        val valid = try {
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).forEach { path -> ZipFile(path).use { zip ->
                expected.forEach { (name, hash) ->
                    val entry = zip.getEntry(name)
                    if (entry != null && entry.size in 1..32L * 1024 * 1024) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.getInputStream(entry).use { input ->
                            val bytes = ByteArray(128 * 1024)
                            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
                        }
                        if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == hash) matches += name
                    }
                }
            } }
            matches.containsAll(expected.keys)
        } catch (_: Exception) { false }
        result = valid; return valid
    }
}
