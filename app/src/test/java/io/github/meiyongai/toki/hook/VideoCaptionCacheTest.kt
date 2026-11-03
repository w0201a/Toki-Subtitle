package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class VideoCaptionCacheTest {
    @Test fun twoHundredVideosRefillOnlyAfterTwoWatchedBatchesAndKeepCompleteTracks() {
        val cache = VideoCaptionCache()
        cache.select("v1")
        for (i in 1..100) {
            cache.expect("v$i", listOf("first", "second"))
            cache.put("v$i", "first", "a$i")
            cache.put("v$i", "second", "b$i")
        }
        assertEquals(100, cache.size)
        // Reading any cached video must not evict another video or advance viewing.
        assertEquals("a100", cache["v100", "first"])
        for (i in 2..6) assertEquals(0, cache.select("v$i"))
        assertEquals(100, cache.size)
        for (i in 1..5) assertTrue(cache.complete("v$i", listOf("first", "second")))
        for (i in 7..10) assertEquals(0, cache.select("v$i"))
        assertEquals(5, cache.select("v11"))
        assertEquals(95, cache.size)
        for (i in 1..5) assertNull(cache["v$i", "first"])
        for (i in 6..100) assertTrue(cache.complete("v$i", listOf("first", "second")))
        for (i in 101..105) {
            cache.expect("v$i", listOf("first", "second"))
            cache.put("v$i", "first", "a$i")
            cache.put("v$i", "second", "b$i")
        }
        assertEquals(100, cache.size)
        for (batch in 0 until 19) {
            val nextSelection = 12 + batch * 5
            var released = 0
            for (i in nextSelection until nextSelection + 5) released += cache.select("v$i")
            assertEquals(5, released)
            assertEquals(95, cache.size)
            for (i in (106 + batch * 5)..(110 + batch * 5)) {
                cache.expect("v$i", listOf("first", "second"))
                cache.put("v$i", "first", "a$i")
                cache.put("v$i", "second", "b$i")
            }
            assertEquals(100, cache.size)
        }
        assertTrue(cache.complete("v200", listOf("first", "second")))
        assertTrue(cache.complete("v106", listOf("first", "second")))
    }
    @Test fun fullCacheDoesNotReplaceUnwatchedVideosWithNewPrefetchResults() {
        val cache = VideoCaptionCache()
        cache.select("v1")
        for (i in 1..100) {
            cache.expect("v$i", listOf("first"))
            cache.put("v$i", "first", "a$i")
        }
        cache.expect("v101", listOf("first"))
        cache.put("v101", "first", "new")
        for (i in 1..100) assertEquals("a$i", cache["v$i", "first"])
        assertEquals(100, cache.size)
    }
    @Test fun partialPrefetchDoesNotEvictCompletedVideosAndSeparatesIdenticalText() {
        val cache = VideoCaptionCache(1)
        cache.expect("done", listOf("shared"))
        cache.put("done", "shared", "done translation")
        cache.expect("next", listOf("shared", "last"))
        cache.put("next", "shared", "next translation")
        assertEquals("done translation", cache["done", "shared"])
        assertFalse(cache.complete("next", listOf("shared", "last")))
        cache.put("next", "last", "last translation")
        assertEquals("done translation", cache["done", "shared"])
        assertTrue(cache.complete("next", listOf("shared", "last")))
    }
    @Test fun repeatedSelectionAndReadingDoNotCountAsFiveMoreWatchedVideos() {
        val cache = VideoCaptionCache()
        cache.select("current")
        cache.expect("current", listOf("first", "last"))
        cache.put("current", "first", "a")
        repeat(30) { cache.select("current"); assertEquals("a", cache["current", "first"]) }
        cache.put("current", "last", "b")
        assertTrue(cache.complete("current", listOf("first", "last")))
    }
    @Test fun returningToOldVideoKeepsItsWholeTrackWhenAnEarlierBatchIsReleased() {
        val cache = VideoCaptionCache()
        for (i in 1..10) {
            cache.expect("v$i", listOf("first", "last"))
            cache.put("v$i", "first", "a$i")
            cache.put("v$i", "last", "b$i")
            cache.select("v$i")
        }
        cache.select("v1")
        assertEquals("a1", cache["v1", "first"])
        assertEquals("b1", cache["v1", "last"])
        cache.put("v2", "last", "late background result")
        assertNull(cache["v2", "last"])
    }


    @Test fun snapshotRestoreKeepsTranslationsForOfflineReopen() {
        val cache = VideoCaptionCache()
        cache.select("v1")
        cache.expect("v1", listOf("first", "second"))
        cache.put("v1", "first", "a")
        cache.put("v1", "second", "b")
        val saved = cache.snapshot()
        val reopened = VideoCaptionCache()
        reopened.restore(saved)
        assertEquals("a", reopened["v1", "first"])
        assertEquals("b", reopened["v1", "second"])
        assertTrue(reopened.complete("v1", listOf("first", "second")))
    }

    @Test fun changedCaptionTrackInvalidatesTranslationsAndRejectsLateResults() {
        val cache = VideoCaptionCache()
        cache.selectTrack("video", "english-track")
        cache.expect("video", listOf("shared phrase"), "english-track")
        cache.put("video", "shared phrase", "English translation", "english-track")
        assertEquals("English translation", cache["video", "shared phrase", "english-track"])

        cache.selectTrack("video", "russian-track")
        assertNull(cache["video", "shared phrase", "russian-track"])
        cache.put("video", "shared phrase", "late English result", "english-track")
        assertNull(cache["video", "shared phrase", "russian-track"])

        cache.expect("video", listOf("shared phrase"), "russian-track")
        cache.put("video", "shared phrase", "Russian translation", "russian-track")
        assertEquals("Russian translation", cache["video", "shared phrase", "russian-track"])
        assertTrue(cache.diagnostic().contains("trackMismatches=1"))
    }
    @Test fun restoredPartialTrackAcceptsRemainingTranslation() {
        val cache = VideoCaptionCache()
        cache.expect("v1", listOf("first", "second"), "track-a")
        cache.put("v1", "first", "one", "track-a")
        val reopened = VideoCaptionCache()
        reopened.restore(cache.snapshot())
        reopened.put("v1", "second", "two", "track-a")
        assertTrue(reopened.complete("v1", listOf("first", "second"), "track-a"))
    }

    @Test fun restoringReplacesOldTrackOwnershipAndRejectsLateResults() {
        val old = VideoCaptionCache()
        old.expect("v1", listOf("first"), "old-track")
        old.put("v1", "first", "old", "old-track")
        val current = VideoCaptionCache()
        current.expect("v1", listOf("first"), "current-track")
        current.put("v1", "first", "current", "current-track")
        old.restore(current.snapshot())
        old.put("v1", "first", "late old result", "old-track")
        assertEquals("current", old["v1", "first", "current-track"])
        old.selectTrack("v1", "new-track")
        assertNull(old["v1", "first", "current-track"])
    }

    @Test fun archiveSnapshotDoesNotShareMutableCaptionEntries() {
        val cache = VideoCaptionCache()
        cache.expect("v1", listOf("first", "second"))
        cache.put("v1", "first", "one")
        val snapshot = cache.snapshot()
        cache.put("v1", "first", "updated")
        assertEquals("one", snapshot.getValue("v1").values["first"])
        val reopened = VideoCaptionCache()
        reopened.restore(snapshot)
        snapshot.getValue("v1").values["first"] = "external change"
        assertEquals("one", reopened["v1", "first"])
    }

    @Test fun restoringEmptyArchiveReplacesPreviousValues() {
        val cache = VideoCaptionCache()
        cache.expect("v1", listOf("first"), "old-track")
        cache.put("v1", "first", "old", "old-track")
        cache.restore(emptyMap())
        assertEquals(0, cache.size)
        assertEquals(0, cache.partialSize)
        assertNull(cache["v1", "first", "old-track"])
    }

}
