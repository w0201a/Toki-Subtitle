package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CaptionPlaybackSelectionTest {
    @Test fun onlySelectedHolderOwnsProgressAndOldCallbacksAreRejectedAfterSwiping() {
        val first = Any(); val second = Any()
        val selection = CaptionPlaybackSelection()
        selection.select(first, "first")
        assertTrue(selection.owns(first, "first"))
        assertFalse(selection.owns(second, "first"))
        assertFalse(selection.owns(second, "second"))
        selection.select(second, "second")
        assertFalse(selection.current("first"))
        assertFalse(selection.owns(first, "first"))
        assertTrue(selection.owns(second, "second"))
        selection.clear()
        assertFalse(selection.current("second"))
        assertFalse(selection.owns(second, "second"))
    }
}
