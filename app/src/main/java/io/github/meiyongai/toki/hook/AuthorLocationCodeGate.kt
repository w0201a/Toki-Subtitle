package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Exact 47.6.3 bytecode for the name callbacks, native selector and profile-mode formatter. */
internal object AuthorLocationCodeGate {
    private const val EXPECTED = "60d095d1bf85b222fa691453133c29b976b274d4ceed215a30603858ac5e18ff"
    @Volatile private var checked: Boolean? = null
    @Synchronized fun verify(context: Context): Boolean {
        checked?.let { return it }
        var supported = false
        try {
            val info = context.applicationInfo
            for (path in listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()) ZipFile(path).use { zip ->
                val entry = zip.getEntry("classes5.dex") ?: return@use
                require(entry.size in 1..32L * 1024 * 1024)
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) return false
                        val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count)
                    }
                }
                if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == EXPECTED) supported = true
            }
        } catch (_: Exception) { supported = false }
        return supported.also { checked = it }
    }
}
