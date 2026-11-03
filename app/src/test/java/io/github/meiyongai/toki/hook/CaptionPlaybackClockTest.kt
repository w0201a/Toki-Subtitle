package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CaptionPlaybackClockTest {
    @Test fun repeatedResumeAndForwardProgressDoNotInvalidateTheCurrentPass() {
        val clock = CaptionPlaybackClock()
        val owner = Any()
        assertTrue(clock.select("video", owner))
        val revision = clock.revision
        assertFalse(clock.select("video", owner))
        assertFalse(clock.select("video"))
        assertFalse(clock.progress("video", 0))
        assertFalse(clock.progress("video", 1000))
        assertFalse(clock.progress("video", 980))
        assertEquals(revision, clock.revision)
    }
    @Test fun replayOrBackwardSeekChangesPassButNeighbouringHoldersDoNotReportProgressForIt() {
        val clock = CaptionPlaybackClock()
        clock.select("video", Any())
        clock.progress("video", 20000)
        val revision = clock.revision
        assertFalse(clock.progress("neighbour", 0))
        assertFalse(clock.progress("video", -1))
        assertEquals(revision, clock.revision)
        assertTrue(clock.progress("video", 0))
        assertEquals(revision + 1, clock.revision)
        clock.progress("video", 10000)
        assertTrue(clock.progress("video", 5000))
        assertEquals(revision + 2, clock.revision)
    }
    @Test fun rebindingTheSameVideoToANewHolderChangesOwnershipWithoutConfusingNormalResume() {
        val clock = CaptionPlaybackClock()
        val first = Any(); val second = Any()
        clock.select("same", first)
        clock.progress("same", 20000)
        val before = clock.revision
        assertTrue(clock.select("same", second))
        assertEquals(before + 1, clock.revision)
        assertFalse(clock.progress("same", 0)) // New holder's first position is not a second replay event.
        assertFalse(clock.select("same", second))
        assertTrue(clock.select("different", second))
        assertEquals("different", clock.video)
    }
    @Test fun timestampsAreNotConvertedToFloatingPointOrOverflowed() {
        val clock = CaptionPlaybackClock()
        clock.select("video")
        assertFalse(clock.progress("video", Long.MAX_VALUE))
        assertTrue(clock.progress("video", 0))
        assertFalse(clock.progress("video", 100))
    }
}
