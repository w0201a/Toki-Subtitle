package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CaptionBatchCursorTest {
    @Test fun advancesFiveAtATimeUntilOneHundredThenWaits() {
        val cursor = CaptionBatchCursor()
        for (batch in 0 until 20) {
            assertTrue(cursor.allowed)
            assertEquals(batch * 5, cursor.offset)
            cursor.complete(5, (batch + 1) * 5)
        }
        assertFalse(cursor.allowed)
        assertEquals(100, cursor.offset)
        cursor.reset()
        assertTrue(cursor.allowed)
        assertEquals(0, cursor.offset)
        cursor.complete(5, 100)
        assertFalse(cursor.allowed)
    }
    @Test fun skipsUnavailableTracksWithoutCountingThemAsCompletedVideos() {
        val cursor = CaptionBatchCursor()
        cursor.complete(5, 2)
        assertEquals(5, cursor.offset)
        assertTrue(cursor.allowed)
        cursor.complete(3, 4)
        assertEquals(8, cursor.offset)
        assertTrue(cursor.allowed)
    }
}
