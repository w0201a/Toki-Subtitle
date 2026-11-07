package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences

/** Bounded page merges and independent video quotas. Lowering limits never deletes stored pages or assets. */
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
            result[row.id] = if (old?.source == row.source) row.copy(
                translation = row.translation?.takeIf { it.isNotBlank() } ?: old.translation,
                language = if (row.translation.isNullOrBlank()) old.language else row.language,
                media = (row.media + old.media).distinctBy { it.id }.take(12),
                mediaKnown = row.mediaKnown || old.mediaKnown,
                replyCount = maxOf(row.replyCount, old.replyCount)) else row
        }
        return result.values.toList()
    }
    data class Quotas(val comments: Int = OfflineCommentPreferences.DEFAULT_LIMIT,
        val replies: Int = OfflineCommentPreferences.DEFAULT_REPLY_LIMIT,
        val perThread: Int = OfflineCommentPreferences.DEFAULT_THREAD_LIMIT) {
        init { require(comments in 1..2500 && replies in 0..2500 && perThread in 0..2500) }
        fun rootCount(rows: List<OfflineCommentData.Row>) = rows.count { it.parentId.isBlank() && it.depth == 0 }
        fun replyCount(rows: List<OfflineCommentData.Row>) = rows.count { it.parentId.isNotBlank() || it.depth > 0 }
        fun threadCount(rows: List<OfflineCommentData.Row>, root: String): Int {
            val byId = rows.associateBy { it.id }
            return rows.count { it.parentId.isNotBlank() && rootOf(it, byId) == root }
        }
        fun acceptsReplies(rows: List<OfflineCommentData.Row>, root: String): Boolean =
            replyCount(rows) < replies && threadCount(rows, root) < perThread
        fun select(rows: List<OfflineCommentData.Row>): List<OfflineCommentData.Row> {
            val values = rows.distinctBy { it.id }
            val byId = values.associateBy { it.id }
            val roots = values.filter { it.parentId.isBlank() && it.depth == 0 }.take(comments)
            val rootIds = roots.mapTo(hashSetOf()) { it.id }
            val counts = mutableMapOf<String, Int>(); var used = 0
            val admitted = roots.associateByTo(linkedMapOf()) { it.id }
            for (row in values) {
                if (row.parentId.isBlank() || used >= replies) continue
                val root = rootOf(row, byId) ?: continue
                if (root !in rootIds || (counts[root] ?: 0) >= perThread) continue
                if (row.parentId !in admitted && row.parentId != root) continue
                admitted[row.id] = row
                counts[root] = (counts[root] ?: 0) + 1; used++
            }
            return CommentPageMerge.ordered(admitted.values)
        }
        private fun rootOf(row: OfflineCommentData.Row, byId: Map<String, OfflineCommentData.Row>): String? {
            var value = row; var depth = 0
            while (value.parentId.isNotBlank() && depth++ < 4) value = byId[value.parentId] ?: return null
            return value.id.takeIf { value.parentId.isBlank() }
        }
    }
    fun mergeScoped(existing: List<OfflineCommentData.Row>, incoming: List<OfflineCommentData.Row>, quotas: Quotas): List<OfflineCommentData.Row> {
        val values = existing.associateByTo(linkedMapOf()) { it.id }
        incoming.forEach { row ->
            val old = values[row.id]
            values[row.id] = if (old?.source == row.source) row.copy(
                translation = row.translation?.takeIf { it.isNotBlank() } ?: old.translation,
                language = if (row.translation.isNullOrBlank()) old.language else row.language,
                media = (row.media + old.media).distinctBy { it.id }.take(12), mediaKnown = row.mediaKnown || old.mediaKnown,
                replyCount = maxOf(old.replyCount, row.replyCount)) else row
        }
        return quotas.select(values.values.toList())
    }

}
