package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class OfflineCommentStreamPlanTest {
    private fun row(id: String, parent: String = "") = OfflineCommentData.Row(id, "author", "user", "uid", id, null, "", if (parent.isBlank()) 0 else 1, 0, parentId = parent)
    private fun snapshot() = OfflineCommentData.Snapshot("a".repeat(64), "video", 1,
        listOf(row("top").copy(replyCount = 2)), 261, true, false, 20)
    @Test fun localOnlyScrollingNeverDispatchesARequest() {
        assertEquals(OfflineCommentStreamPlan.Step.Idle, OfflineCommentStreamPlan.next(snapshot(), false, false, 200, true))
        assertEquals(OfflineCommentStreamPlan.Step.Idle, OfflineCommentStreamPlan.next(snapshot(), true, true, 200, true))
    }
    @Test fun repliesAndRootsHaveIndependentContinuousCursors() {
        val first = snapshot()
        assertEquals(OfflineCommentStreamPlan.Step.Replies("top", 0), OfflineCommentStreamPlan.next(first, true, false, 200, true))
        assertEquals(OfflineCommentStreamPlan.Step.Root(20), OfflineCommentStreamPlan.next(first, true, false, 200, false))
        val replies = first.copy(rows = first.rows + row("child", "top"), replyCursors = mapOf("top" to OfflineCommentData.ReplyCursor(20, true)))
        assertEquals(OfflineCommentStreamPlan.Step.Replies("top", 20), OfflineCommentStreamPlan.next(replies, true, false, 200, true))
        val endReply = replies.copy(replyCursors = mapOf("top" to OfflineCommentData.ReplyCursor(20, false)))
        assertEquals(OfflineCommentStreamPlan.Step.Root(20), OfflineCommentStreamPlan.next(endReply, true, false, 200, true))
    }
    @Test fun configuredSharedBudgetEndsWithoutAPageButtonOrInfiniteRepeat() {
        assertEquals(OfflineCommentStreamPlan.Step.Limit, OfflineCommentStreamPlan.next(snapshot(), true, false, 1, true, OfflineCommentBudget.Quotas(1, 0, 0)))
        val end = snapshot().copy(hasMore = false, nextCursor = null,
            replyCursors = mapOf("top" to OfflineCommentData.ReplyCursor(null, false)))
        assertEquals(OfflineCommentStreamPlan.Step.End, OfflineCommentStreamPlan.next(end, true, false, 200, true))
        assertEquals(OfflineCommentStreamPlan.Step.Root(0), OfflineCommentStreamPlan.next(null, true, false, 200, true))
    }
    @Test fun hittingRootQuotaStillAllowsRepliesWithoutBlockingAnotherRootBeforeItsQuota() {
        val limits = OfflineCommentBudget.Quotas(2, 10, 1)
        val first = snapshot().copy(rows = listOf(row("top").copy(replyCount = 10), row("r1", "top")))
        assertEquals(OfflineCommentStreamPlan.Step.Root(20), OfflineCommentStreamPlan.next(first, true, false, 2, true, limits))
        val second = first.copy(rows = first.rows + row("other").copy(replyCount = 2))
        assertEquals(OfflineCommentStreamPlan.Step.Replies("other", 0), OfflineCommentStreamPlan.next(second, true, false, 2, true, limits))
        val capped = second.copy(rows = second.rows + row("r2", "other"))
        assertEquals(OfflineCommentStreamPlan.Step.Limit, OfflineCommentStreamPlan.next(capped, true, false, 2, true, limits))
    }

}
