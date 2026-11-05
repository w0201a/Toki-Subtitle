package io.github.meiyongai.toki.subtitle.hook

/** The budget counts unique top-level comments and replies together. Existing rows are never removed by lowering it. */
internal object OfflineCommentBudget {
    fun merge(existing: List<OfflineCommentData.Row>, incoming: List<OfflineCommentData.Row>, known: Set<String>, limit: Int): List<OfflineCommentData.Row> {
        require(limit in 1..2500)
        val result = LinkedHashMap<String, OfflineCommentData.Row>()
        existing.forEach { result[it.id] = it }
        val admitted = known.toMutableSet()
        for (row in incoming) {
            if (row.id !in admitted && admitted.size >= limit) continue
            admitted += row.id
            val old = result[row.id]
            result[row.id] = if (old?.source == row.source && row.translation.isNullOrBlank())
                row.copy(translation = old.translation, language = old.language) else row
        }
        return result.values.toList()
    }
}
