package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.assertEquals
import org.junit.Test

class LayoutPageSelectionTest {
    private val tabs = listOf("Following", "Shop", "Stem", "For You", "Live")

    @Test fun skipsConsecutiveHiddenPagesInBothDirections() {
        val hidden = setOf("Shop", "Stem")
        assertEquals(3, LayoutPageSelection.target(tabs, 0, 1, hidden::contains))
        assertEquals(0, LayoutPageSelection.target(tabs, 3, 2, hidden::contains))
    }

    @Test fun blockedEdgeSettlesBackOnCurrentPage() {
        assertEquals(3, LayoutPageSelection.target(tabs, 3, 4) { it == "Live" })
        assertEquals(1, LayoutPageSelection.target(tabs, 1, 0) { it == "Following" })
    }

    @Test fun hidingSelectedPageMovesToClosestAvailablePage() {
        assertEquals(3, LayoutPageSelection.target(tabs, 2, 2) { it in setOf("Following", "Shop", "Stem") })
    }

    @Test fun unknownTagsAndDisabledCleanupKeepNativeSelection() {
        assertEquals(1, LayoutPageSelection.target(listOf("For You", "future-tab"), 0, 1) { it == "For You" })
        assertEquals(1, LayoutPageSelection.target(listOf("For You", null), 0, 1) { true })
        assertEquals(2, LayoutPageSelection.target(tabs, 0, 2) { false })
    }

    @Test fun preservesNativeBoundsAndRetainsAnchorWhenAllPagesAreHidden() {
        assertEquals(-1, LayoutPageSelection.target(tabs, 0, -1) { false })
        assertEquals(99, LayoutPageSelection.target(tabs, 0, 99) { false })
        assertEquals(3, LayoutPageSelection.target(tabs, 3, 4) { true })
        assertEquals(0, LayoutPageSelection.target(emptyList(), 0, 0) { true })
    }
}
