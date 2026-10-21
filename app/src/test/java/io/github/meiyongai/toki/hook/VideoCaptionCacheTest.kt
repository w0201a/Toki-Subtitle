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
}
