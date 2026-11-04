package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.CountryDisplayCode

/** Session-only, bounded author-id matching; never infer the author from device/SIM/IP region. */
internal class AuthorRegionMemory(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private data class Entry(val region: String, val at: Long)
    private val known = LinkedHashMap<String, Entry>()
    @Synchronized fun resolve(author: String, supplied: String?): String? {
        if (author.isBlank() || author.length > 128) return null
        val region = supplied?.let(CountryDisplayCode::resolve)?.takeIf { it in java.util.Locale.getISOCountries() }
        val now = clock()
        if (region != null) {
            known.remove(author); known[author] = Entry(region, now)
            while (known.size > 256) known.remove(known.keys.first())
            return region
        }
        val saved = known[author] ?: return null
        if (now - saved.at !in 0..(30 * 60 * 1000L)) { known.remove(author); return null }
        return saved.region
    }
    @Synchronized fun clear() { known.clear() }
}
