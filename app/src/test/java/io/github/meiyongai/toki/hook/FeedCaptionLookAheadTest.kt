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

    @Test fun reorderedAndFilteredFeedUsesActualPositions() {
        assertEquals(listOf("b", "a"), FeedCaptionLookAhead.following(listOf("current", "b", "a", "c")) { it == "current" })
        assertEquals(listOf("a", "c"), FeedCaptionLookAhead.following(listOf("current", "a", "c")) { it == "current" })
    }
}
