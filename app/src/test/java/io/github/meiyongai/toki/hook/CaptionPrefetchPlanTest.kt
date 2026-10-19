package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CaptionPrefetchPlanTest {
    @Test fun openingsAreInterleavedAndCurrentNearCuesRetainPriority() {
        val videos = (1..5).map { video -> (1..20).map { "v$video-$it" } }
        val current = (1..20).map { "current$it" }
        val plan = CaptionPrefetch.plan(current, videos)
        assertEquals(listOf("current1", "current2", "current3", "v1-1", "v2-1", "v3-1", "v4-1", "v1-2"), plan.take(8))
        assertEquals(31, plan.size)
        assertFalse(plan.any { it.startsWith("v5-") })
        assertFalse(plan.contains("current8"))
    }
    @Test fun laterVideosCanWarmEvenWithoutCurrentTimelineAndRepeatedCuesAreDeduplicated() {
        assertEquals(listOf("first", "second", "shared"), CaptionPrefetch.plan(emptyList(), listOf(listOf("first", "shared"), listOf("second", "shared"))))
    }
}
