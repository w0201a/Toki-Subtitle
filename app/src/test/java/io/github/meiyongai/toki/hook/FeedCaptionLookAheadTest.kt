package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class FeedCaptionLookAheadTest {
    @Test fun readsOnlyTheTwoFollowingEntriesAndNeverWrapsOrGuessesMissingCurrent() {
        val items = listOf("previous", "current", "next-no-captions", "next-caption", "too-far")
        assertEquals(listOf("next-no-captions", "next-caption"), FeedCaptionLookAhead.following(items) { it == "current" })
        assertEquals(listOf("too-far"), FeedCaptionLookAhead.following(items) { it == "next-caption" })
        assertTrue(FeedCaptionLookAhead.following(items) { it == "too-far" }.isEmpty())
        assertTrue(FeedCaptionLookAhead.following(items) { it == "missing" }.isEmpty())
    }

    @Test fun rapidSwipingWindowUsesOnlyFourRealFollowingEntries() {
        val items = listOf("current", "next1", "no-caption", "next3", "next4", "too-far")
        assertEquals(listOf("next1", "no-caption", "next3", "next4"), FeedCaptionLookAhead.following(items, 4) { it == "current" })
        assertTrue(FeedCaptionLookAhead.following(items, 4) { it == "missing" }.isEmpty())
    }
    @Test fun reorderedAndFilteredFeedUsesActualPositions() {
        assertEquals(listOf("b", "a"), FeedCaptionLookAhead.following(listOf("current", "b", "a", "c")) { it == "current" })
        assertEquals(listOf("a", "c"), FeedCaptionLookAhead.following(listOf("current", "a", "c")) { it == "current" })
    }
}
