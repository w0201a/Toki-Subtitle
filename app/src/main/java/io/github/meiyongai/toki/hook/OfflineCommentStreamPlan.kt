package io.github.meiyongai.toki.subtitle.hook

/** UI pagination is automatic; HTTP cursors remain private and never turn a local-only scroll into networking. */
internal object OfflineCommentStreamPlan {
    sealed class Step {
        data class Root(val cursor: Long) : Step()
        data class Replies(val root: String, val cursor: Long) : Step()
        object Idle : Step()
        object End : Step()
        object Limit : Step()
    }
    fun next(snapshot: OfflineCommentData.Snapshot?, online: Boolean, busy: Boolean, limit: Int, repliesFirst: Boolean, quotas: OfflineCommentBudget.Quotas = OfflineCommentBudget.Quotas(limit)): Step {
        require(limit in 1..2500)
        if (!online || busy) return Step.Idle
        if (snapshot == null) return Step.Root(0)
        val rootFull = quotas.rootCount(snapshot.rows) >= quotas.comments
        val counts = snapshot.rows.filter { it.parentId.isNotBlank() }.groupingBy { it.parentId }.eachCount()
        val root = snapshot.rows.firstOrNull { row -> row.parentId.isBlank() && row.depth == 0 &&
            quotas.acceptsReplies(snapshot.rows, row.id) &&
            (snapshot.replyCursors[row.id]?.hasMore ?: (row.replyCount > (counts[row.id] ?: 0))) }
        if (root != null && (repliesFirst || !snapshot.hasMore || rootFull)) {
            val cursor = snapshot.replyCursors[root.id]
            return Step.Replies(root.id, if (cursor?.hasMore == true) cursor.next ?: 0L else 0L)
        }
        if (!rootFull && snapshot.hasMore && snapshot.nextCursor != null) return Step.Root(snapshot.nextCursor)
        return if (rootFull) Step.Limit else if (root == null) Step.End else Step.Idle
    }
}
