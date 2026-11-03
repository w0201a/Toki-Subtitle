package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Only the two inspected cache-reader DEX files may supply obfuscated cache-index semantics. */
internal object OfflineCacheCodeGate {
    private val expected = mapOf(
        "classes3.dex" to "825ac3f229bd1176a291ceda75dbb25bcb3fb5debd2163db7015c624fe97dddf",
        "classes7.dex" to "18238d4d351c34449656fbc63cea49e8cdf2e0180322acbc3d43b953596a78ed",
    )
    @Volatile private var supported: Boolean? = null
    @Synchronized fun verify(context: Context): Boolean {
        supported?.let { return it }
        val info = context.applicationInfo
        val paths = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        val found = mutableSetOf<String>()
        val result = try {
            paths.forEach { path -> ZipFile(path).use { zip ->
                expected.forEach { (name, hash) ->
                    val entry = zip.getEntry(name)
                    if (entry != null && entry.size in 1..32L * 1024 * 1024) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) return false
                                val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
                            }
                        }
                        if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == hash) found += name
                    }
                }
            } }
            found.containsAll(expected.keys)
        } catch (_: Exception) { false }
        supported = result
        return result
    }
}
