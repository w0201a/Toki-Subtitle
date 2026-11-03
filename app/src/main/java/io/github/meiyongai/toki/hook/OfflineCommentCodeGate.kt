package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.security.MessageDigest
import java.util.zip.ZipFile

/** The inspected native store/account/key schema is pinned to its DEX, not guessed from versionName. */
internal object OfflineCommentCodeGate {
    private const val HASH = "d96d4d1964f3313ab6efac9d7dd9aa1cde4389b5290a2119ec00f056d8e0f51d"
    @Volatile private var verified: Boolean? = null
    @Synchronized fun verify(context: Context): Boolean {
        verified?.let { return it }
        val info = context.applicationInfo
        val paths = listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()
        val result = try {
            paths.any { path -> ZipFile(path).use { zip ->
                val entry = zip.getEntry("classes15.dex")
                if (entry == null || entry.size !in 1..32L * 1024 * 1024) false else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                    }
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == HASH
                }
            } }
        } catch (_: Exception) { false }
        verified = result
        return result
    }
}
