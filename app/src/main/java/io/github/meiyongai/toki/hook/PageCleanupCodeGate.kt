package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import io.github.meiyongai.toki.subtitle.model.PageCleanupItem
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Hash only inspected component DEX off-thread; each item needs its own relevant code, not a version-name guess. */
internal object PageCleanupCodeGate {
    private val hashes = mapOf(
        "classes8.dex" to "733a8169bb16c61256cf7de528539bcace48c7778e155edb6dcdc4775de15cd3",
        "classes11.dex" to "af192e6086912ccab90bde46556fae451de1dda7892f9fc04716b364543c4c06",
        "classes27.dex" to "e7bce03d25706dab71f8dc3964c5f6c55e0c71810b2c513fe9a2f14f0d96b9ee",
        "classes30.dex" to "bbd26b2f364dac6409ae3ad8b2bba032f9c51ef7bf303287e6e6b6c15a659c34",
        "classes34.dex" to "bcdcc1dc4f7c26643a3944c37394fb3b496138305fccca49830b97bad89bf061",
        "classes10.dex" to "02c0273c05a26a73670bab39e253b1dd8aa049fa07f8aa78ebbdcda5417b7655",
    )
    internal fun required(item: PageCleanupItem): Set<String> = when (item) {
        PageCleanupItem.SEARCH_VOICE -> setOf("classes34.dex")
        PageCleanupItem.SEARCH_TAKO -> setOf("classes8.dex", "classes34.dex")
        PageCleanupItem.INBOX_SHOP_MIGRATION -> setOf("classes11.dex")
        PageCleanupItem.LIVE_FOLLOW_BOTTOM, PageCleanupItem.LIVE_FOLLOW_PIN -> setOf("classes27.dex", "classes10.dex")
        PageCleanupItem.LIVE_GIFT_GUIDE -> setOf("classes30.dex", "classes10.dex")
    }
    internal fun supported(matched: Set<String>): Set<PageCleanupItem> =
        PageCleanupItem.entries.filterTo(linkedSetOf()) { matched.containsAll(required(it)) }
    @Volatile private var verified: Set<PageCleanupItem>? = null
    @Synchronized fun verify(context: Context): Set<PageCleanupItem> {
        verified?.let { return it }
        val matched = mutableSetOf<String>()
        val info = context.applicationInfo
        try {
            (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).forEach { path -> ZipFile(path).use { zip ->
                hashes.forEach { (name, expected) ->
                    val entry = zip.getEntry(name)
                    if (entry != null && entry.size in 1..32L * 1024 * 1024) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) return emptySet()
                                val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n)
                            }
                        }
                        if (digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected) matched += name
                    }
                }
            } }
        } catch (_: Exception) { matched.clear() }
        return supported(matched).also { verified = it }
    }
}
