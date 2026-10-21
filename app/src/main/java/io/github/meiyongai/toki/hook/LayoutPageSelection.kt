package io.github.meiyongai.toki.subtitle.hook

/** Select an available home tab without changing the host's page indices. */
internal object LayoutPageSelection {
    fun target(tags: List<String?>, current: Int, requested: Int, hidden: (String) -> Boolean): Int {
        if (tags.isEmpty()) return requested
        val candidate = requested.coerceIn(tags.indices)
        fun available(index: Int) = tags[index]?.let { !hidden(it) } ?: true
        if (available(candidate)) return requested
        val direction = candidate.compareTo(current)
        if (direction != 0) {
            var next = candidate + direction
            while (next in tags.indices) {
                if (available(next)) return next
                next += direction
            }
        }
        if (current in tags.indices && available(current)) return current
        return tags.indices.filter(::available).minByOrNull { kotlin.math.abs(it - candidate) }
            ?: current.coerceIn(tags.indices)
    }
}
