package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CommentPageMergeTest {
    private fun row(id: String, text: String = id, parent: String = "") = OfflineCommentData.Row(
        id, "author", "handle", "uid", text, null, "", if (parent.isBlank()) 0 else 1, 0, parentId = parent)
    private fun snapshot(rows: List<OfflineCommentData.Row>, cursor: Long = 20, more: Boolean = true,
        account: String = "a".repeat(64), video: String = "video") = OfflineCommentData.Snapshot(
        account, video, 1, rows, 100, more, false, cursor)

    @Test fun refreshUpdatesSameIdsButRetainsUnobservedOldRowsWithAnExplicitFlag() {
        val old = snapshot(listOf(row("old"), row("same", "old text")))
        val fresh = snapshot(listOf(row("same", "new text"), row("new")))
        val merged = CommentPageMerge.merge(old, fresh, 0)
        assertEquals(listOf("same", "new", "old"), merged.rows.map { it.id })
        assertEquals("new text", merged.rows.first().source)
        assertTrue(merged.retainedOlder)
        assertEquals(listOf("old", "same"), old.rows.map { it.id })
    }
    @Test fun nextPageRequiresMatchingIdentityAndProgressingCursor() {
        val old = snapshot(listOf(row("first")))
        val next = snapshot(listOf(row("second")), 40)
        assertEquals(listOf("first", "second"), CommentPageMerge.merge(old, next, 20).rows.map { it.id })
        assertThrows(IllegalArgumentException::class.java) { CommentPageMerge.merge(old, next, 10) }
        assertThrows(IllegalArgumentException::class.java) { CommentPageMerge.merge(old, next.copy(account = "b".repeat(64)), 20) }
        assertThrows(IllegalArgumentException::class.java) { CommentPageMerge.merge(old, next.copy(video = "other"), 20) }
        assertThrows(IllegalArgumentException::class.java) { CommentPageMerge.merge(old, next.copy(nextCursor = 20), 20) }
        assertThrows(IllegalArgumentException::class.java) { CommentPageMerge.merge(null, next.copy(nextCursor = 0), 0) }
        assertFalse(CommentPageMerge.merge(old, next.copy(hasMore = false, nextCursor = 20), 20).hasMore)
    }
    @Test fun mergingPagesKeepsRepliesUnderTheirActualParentRatherThanTheLastNewRoot() {
        val old = snapshot(listOf(row("first"), row("reply", parent = "first")))
        val fresh = snapshot(listOf(row("second")))
        val rows = CommentPageMerge.merge(old, fresh, 0).rows
        assertEquals(listOf("second", "first", "reply"), rows.map { it.id })
        assertEquals("first", rows.last().parentId)
        assertEquals(1, rows.last().depth)
        val cycle = snapshot(listOf(row("a", parent = "b"), row("b", parent = "a")))
        assertThrows(IllegalArgumentException::class.java) { CommentPageMerge.merge(null, cycle, 0) }
    }
    @Test fun capacityIsNotMadeAvailableBySilentlyEvictingSavedRows() {
        val old = snapshot((1..5000).map { row(it.toString()) })
        val extra = snapshot(listOf(row("new")), 40)
        assertThrows(IllegalArgumentException::class.java) { CommentPageMerge.merge(old, extra, 20) }
        assertEquals(5000, old.rows.size)
        assertEquals("1", old.rows.first().id)
    }
    @Test fun cacheCombinationRetainsRowsWithoutPretendingTheyWereRevalidatedOnline() {
        val primary = snapshot(listOf(row("new")))
        val secondary = snapshot(listOf(row("old")))
        val combined = CommentPageMerge.cached(primary, secondary)
        assertEquals(listOf("new", "old"), combined.rows.map { it.id })
        assertTrue(combined.retainedOlder)
    }
    @Test fun cancelBeforeSubscriptionIsReturnedStillDisposesItExactlyOnce() {
        var disposed = 0
        val operation = CommentPageOperation()
        operation.close()
        operation.attach(AutoCloseable { disposed++ })
        operation.close()
        assertFalse(operation.active)
        assertEquals(1, disposed)
        val second = CommentPageOperation()
        second.attach(AutoCloseable { disposed++ })
        assertTrue(second.active)
        second.close(); second.close()
        assertEquals(2, disposed)
    }
    @Test fun serverPageSizeDoesNotCapTheContinuouslyMergedListAtTwentyOrFiveHundred() {
        var saved: OfflineCommentData.Snapshot? = null
        repeat(100) { page ->
            val cursor = page * 20L
            val next = snapshot((0 until 20).map { row("c${cursor + it}") }, cursor + 20, page < 99)
            saved = CommentPageMerge.merge(saved, next, cursor)
        }
        assertEquals(2000, saved!!.rows.size)
        assertEquals("c0", saved!!.rows.first().id); assertEquals("c1999", saved!!.rows.last().id)
    }

}
