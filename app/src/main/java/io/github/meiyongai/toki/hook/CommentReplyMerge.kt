package io.github.meiyongai.toki.subtitle.hook

/** Root-comment reply cursors are separate from top-level list cursors and from every other root's cursor. */
internal object CommentReplyMerge {
    fun merge(old: OfflineCommentData.Snapshot, root: String, page: OfflineCommentData.Snapshot,
        requestedCursor: Long): OfflineCommentData.Snapshot {
        require(old.account == page.account && old.video == page.video && requestedCursor >= 0)
        val parent = old.rows.single { it.id == root }
        require(parent.parentId.isBlank() && parent.depth == 0)
        if (requestedCursor > 0) require(old.replyCursors[root]?.next == requestedCursor && old.replyCursors[root]?.hasMore == true)
        if (page.hasMore) require(page.nextCursor != null && page.nextCursor > requestedCursor) { "Non-progressing reply cursor" }
        val values = old.rows.associateByTo(linkedMapOf()) { it.id }
        val incomingIds = page.rows.mapTo(mutableSetOf()) { it.id }
        require(root !in incomingIds)
        var limited = false
        page.rows.forEach { row ->
            if (row.depth >= 3) { limited = true; return@forEach }
            val parentId = row.parentId.ifBlank { root }
            require(parentId == root || parentId in incomingIds)
            val before = values[row.id]
            require(before == null || before.parentId == parentId) { "Reply belongs to another cached parent" }
            values[row.id] = row.copy(parentId = parentId, depth = row.depth + 1)
        }
        val savedChildren = values.values.count { it.parentId == root }.toLong()
        values[root] = parent.copy(replyCount = maxOf(parent.replyCount, page.total, savedChildren))
        require(values.size <= OfflineCommentData.MAX_ROWS) { "Comment snapshot capacity reached" }
        val earlier = old.rows.any { it.parentId == root && it.id !in incomingIds }
        val cursors = old.replyCursors + (root to OfflineCommentData.ReplyCursor(page.nextCursor, page.hasMore))
        return old.copy(rows = CommentPageMerge.ordered(values.values), capturedAt = maxOf(old.capturedAt, page.capturedAt),
            truncated = old.truncated || page.truncated || limited,
            retainedOlder = old.retainedOlder || requestedCursor == 0L && earlier, replyCursors = cursors)
    }
}
