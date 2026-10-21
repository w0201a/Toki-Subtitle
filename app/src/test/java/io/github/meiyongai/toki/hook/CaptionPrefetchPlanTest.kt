package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CaptionPrefetchPlanTest {
    @Test fun openingsAreInterleavedAndCurrentNearCuesRetainPriority() {
        val videos = (1..5).map { video -> (1..20).map { "v$video-$it" } }
        val current = (1..20).map { "current$it" }
        val plan = CaptionPrefetch.plan(current, videos)
        assertEquals(listOf("current1", "current2", "current3", "v1-1", "v2-1", "v3-1", "v4-1", "v5-1"), plan.take(8))
        assertEquals(120, plan.size)
        assertTrue(plan.contains("v5-20"))
        assertTrue(plan.contains("current20"))
    }
    @Test fun laterVideosCanWarmEvenWithoutCurrentTimelineAndRepeatedCuesAreDeduplicated() {
        assertEquals(listOf("first", "second", "shared"), CaptionPrefetch.plan(emptyList(), listOf(listOf("first", "shared"), listOf("second", "shared"))))
    }
    @Test fun entireTracksAreScheduledIncludingLongVideoTail() {
        val tracks = (1..6).map { video -> "v$video" to (1..3100).map { "video$video cue$it" } }
        val plan = CaptionPrefetch.planVideos("current", emptyList(), tracks)
        assertEquals(15_500, plan.size)
        assertTrue(plan.contains(CaptionTranslationInput("v5", "video5 cue3100")))
        assertFalse(plan.any { it.video == "v6" })
    }
}
