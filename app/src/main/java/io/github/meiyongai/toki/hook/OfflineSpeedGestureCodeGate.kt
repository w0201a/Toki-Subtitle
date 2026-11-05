package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Only the inspected native speed implementation, independent of the user's downward-lock option. */
internal object OfflineSpeedGestureCodeGate {
    fun verify(context: Context): Boolean = try {
        val expected = "98b4a71a732ac816310537df8e3642839213ca0f5c4806972159e48dfedc2688"
        val info = context.applicationInfo
        (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).any { path -> ZipFile(path).use { zip ->
            val entry = zip.getEntry("classes6.dex") ?: return@use false
            require(entry.size in 1..32L * 1024 * 1024)
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { stream ->
                val buffer = ByteArray(131072)
                while (true) { if (Thread.currentThread().isInterrupted) return false; val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected
        } }
    } catch (_: Exception) { false }
}
