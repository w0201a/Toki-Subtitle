package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class OfflineCommentWorkPlanTest {
    private fun job(id: Int, first: Boolean = false, source: Boolean = false, translation: Boolean = false,
        retry: Long = 0L, last: Long = 0L) = OfflineCommentWorkPlan.Job("video-%02d".format(id), first, source, translation, retry, last)
    @Test fun everyTurnContainsAtMostFiveVideosAndUnseenVideosPrecedeDeepPages() {
        val rows = (0..59).map { job(it, first = it < 5) }
        val batch = OfflineCommentWorkPlan.batch(rows, OfflineCommentWorkPlan.Lane.SOURCE, 100L)
        assertEquals(5, batch.size); assertTrue(batch.all { !it.firstPage })
        assertEquals((5..9).map { "video-%02d".format(it) }, batch.map { it.key })
    }
    @Test fun untranslatedHeadDoesNotStopSourceWorkOrOtherVideoTranslations() {
        val rows = listOf(job(0, first = true, source = true, retry = 5000L), job(1), job(2, first = true))
        assertEquals(listOf("video-01", "video-02"), OfflineCommentWorkPlan.batch(rows, OfflineCommentWorkPlan.Lane.SOURCE, 100L).map { it.key })
        assertEquals(listOf("video-02"), OfflineCommentWorkPlan.batch(rows, OfflineCommentWorkPlan.Lane.TRANSLATION, 100L).map { it.key })
    }
    @Test fun futureRetriesAlwaysHaveAWakeupInsteadOfSilentlyStoppingAfterThreeAttempts() {
        val retry = OfflineCommentWorkPlan.retryDelay(3)
        assertEquals(60_000L, retry)
        val rows = listOf(job(1, retry = 100L + retry))
        assertTrue(OfflineCommentWorkPlan.batch(rows, OfflineCommentWorkPlan.Lane.SOURCE, 100L).isEmpty())
        assertEquals(retry, OfflineCommentWorkPlan.nextDelay(rows, OfflineCommentWorkPlan.Lane.SOURCE, 100L))
        assertEquals(1, OfflineCommentWorkPlan.batch(rows, OfflineCommentWorkPlan.Lane.SOURCE, 100L + retry).size)
        assertEquals(300_000L, OfflineCommentWorkPlan.retryDelay(100))
    }
    @Test fun lastRunRotatesEqualPriorityVideosAndCompletedWorkHasNoTimer() {
        val rows = (0..9).map { job(it, first = true, last = if (it < 5) 100L else 0L) }
        assertEquals((5..9).map { "video-%02d".format(it) }, OfflineCommentWorkPlan.batch(rows, OfflineCommentWorkPlan.Lane.SOURCE, 200L).map { it.key })
        assertNull(OfflineCommentWorkPlan.nextDelay(listOf(job(1, true, true, true)), OfflineCommentWorkPlan.Lane.TRANSLATION, 0L))
    }
    @Test fun completionCallbacksBeforeVerificationAreRetainedAndDrainedInFiveVideoBatches() {
        val ingress = OfflineCommentIngress<Int>()
        repeat(60) { ingress.offer("video-$it", it) }
        assertTrue(ingress.take(false).isEmpty()); assertEquals(60, ingress.size())
        ingress.offer("video-0", 1000); assertEquals(60, ingress.size())
        val values = mutableListOf<Int>()
        while (ingress.size() > 0) { val batch = ingress.take(true); assertTrue(batch.size <= 5); values += batch }
        assertEquals(60, values.size); assertTrue(1000 in values); assertFalse(0 in values)
    }
}
