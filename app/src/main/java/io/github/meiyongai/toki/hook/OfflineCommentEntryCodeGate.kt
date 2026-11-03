package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Checked off the UI thread before any comment-button interception can become eligible. */
internal object OfflineCommentEntryCodeGate {
    private const val HASH = "60d095d1bf85b222fa691453133c29b976b274d4ceed215a30603858ac5e18ff"
    @Volatile private var supported: Boolean? = null
    @Synchronized fun verify(context: Context): Boolean {
        supported?.let { return it }
        val info = context.applicationInfo
        val result = try {
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).any { path -> ZipFile(path).use { zip ->
                val entry = zip.getEntry("classes5.dex")
                if (entry == null || entry.size !in 1..32L * 1024 * 1024) false else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { input ->
                        val bytes = ByteArray(128 * 1024)
                        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
                    }
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == HASH
                }
            } }
        } catch (_: Exception) { false }
        supported = result; return result
    }
}
