package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CommentReplyMergeTest {
    private fun row(id: String, parent: String = "", text: String = id) = OfflineCommentData.Row(
        id, "author", "handle", "uid", text, null, "", if (parent.isBlank()) 0 else 1, 10, parentId = parent)
    private fun snapshot(rows: List<OfflineCommentData.Row>, cursor: Long = 20, more: Boolean = true) =
        OfflineCommentData.Snapshot("a".repeat(64), "video", 1, rows, 100, more, false, cursor)

    @Test fun eachRootHasAnIndependentReplyCursorAndLeavesTopLevelCursorUntouched() {
        val base = snapshot(listOf(row("root1"), row("root2")), 60)
        val first = CommentReplyMerge.merge(base, "root1", snapshot(listOf(row("r1"))), 0)
        assertEquals(60L, first.nextCursor)
        assertEquals(OfflineCommentData.ReplyCursor(20, true), first.replyCursors["root1"])
        assertNull(first.replyCursors["root2"])
        val other = CommentReplyMerge.merge(first, "root2", snapshot(listOf(row("r2")), 40), 0)
        assertEquals(20L, other.replyCursors["root1"]?.next)
        assertEquals(40L, other.replyCursors["root2"]?.next)
        assertEquals(listOf("root1", "r1", "root2", "r2"), other.rows.map { it.id })
        assertEquals("root1", other.rows.single { it.id == "r1" }.parentId)
        assertEquals("root2", other.rows.single { it.id == "r2" }.parentId)
        val end = CommentReplyMerge.merge(other, "root1", snapshot(listOf(row("r3")), 20, false), 20)
        assertFalse(end.replyCursors.getValue("root1").hasMore)
        assertEquals(60L, end.nextCursor)
        assertTrue(end.hasMore)
    }
    @Test fun wrongParentIdentityCursorAndRootCollisionsAreRefused() {
        val base = snapshot(listOf(row("root1"), row("r1", "root1"), row("root2")))
        val page = snapshot(listOf(row("new")))
        assertThrows(IllegalArgumentException::class.java) { CommentReplyMerge.merge(base, "root1", page, 20) }
        assertThrows(IllegalArgumentException::class.java) { CommentReplyMerge.merge(base, "root1", page.copy(nextCursor = 0), 0) }
        assertThrows(IllegalArgumentException::class.java) { CommentReplyMerge.merge(base, "root1", page.copy(video = "other"), 0) }
        assertThrows(IllegalArgumentException::class.java) { CommentReplyMerge.merge(base, "root1", page.copy(account = "b".repeat(64)), 0) }
        assertThrows(IllegalArgumentException::class.java) { CommentReplyMerge.merge(base, "root1", snapshot(listOf(row("root1"))), 0) }
        assertThrows(IllegalArgumentException::class.java) { CommentReplyMerge.merge(base, "root2", snapshot(listOf(row("r1"))), 0) }
        assertThrows(IllegalArgumentException::class.java) { CommentReplyMerge.merge(base, "r1", page, 0) }
    }
    @Test fun refreshRetainsOldRepliesAndCapacityDoesNotEvictAnotherThread() {
        val old = snapshot(listOf(row("root"), row("old", "root")))
        val refreshed = CommentReplyMerge.merge(old, "root", snapshot(listOf(row("new"))), 0)
        assertEquals(setOf("root", "old", "new"), refreshed.rows.map { it.id }.toSet())
        assertTrue(refreshed.retainedOlder)
        val full = snapshot(listOf(row("root")) + (1..499).map { row("other" + it) })
        assertThrows(IllegalArgumentException::class.java) { CommentReplyMerge.merge(full, "root", snapshot(listOf(row("extra"))), 0) }
        assertEquals(500, full.rows.size)
    }
    @Test fun nestedReplyParentsAreRetainedAndSavedCursorsSurviveTopLevelRefresh() {
        val old = snapshot(listOf(row("root")))
        val page = snapshot(listOf(row("reply"), row("nested", "reply")))
        val merged = CommentReplyMerge.merge(old, "root", page, 0)
        assertEquals(2, merged.rows.single { it.id == "nested" }.depth)
        assertEquals("reply", merged.rows.single { it.id == "nested" }.parentId)
        val refreshed = CommentPageMerge.merge(merged, snapshot(listOf(row("root", text = "updated"))), 0)
        assertEquals(merged.replyCursors, refreshed.replyCursors)
        assertEquals(merged.replyCursors, CommentPageMerge.cached(merged, old).replyCursors)
    }
}
