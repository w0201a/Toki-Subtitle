package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class OfflineLongPressStateTest {
    @Test fun stationaryHoldCanBeClaimedExactlyOnce() {
        val state = OfflineLongPressState()
        val token = state.begin(10f, 20f)
        state.move(13f, 22f, 1, 8f)
        assertTrue(state.claim(token))
        assertTrue(state.claimed)
        assertFalse(state.claim(token))
        state.cancel()
        assertFalse(state.claimed)
    }
    @Test fun scrollMultitouchCancelAndRebindingRejectDelayedHolds() {
        val state = OfflineLongPressState()
        val old = state.begin(0f, 0f)
        state.move(0f, 100f, 1, 8f)
        assertFalse(state.claim(old))
        val multi = state.begin(0f, 0f)
        state.move(0f, 0f, 2, 8f)
        assertFalse(state.claim(multi))
        val cancel = state.begin(0f, 0f)
        state.cancel()
        assertFalse(state.claim(cancel))
        val replaced = state.begin(0f, 0f)
        val current = state.begin(0f, 0f)
        assertFalse(state.claim(replaced))
        assertTrue(state.claim(current))
    }
    @Test fun invalidCoordinatesDoNotQualify() {
        val state = OfflineLongPressState()
        val token = state.begin(0f, 0f)
        state.move(Float.NaN, 0f, 1, 8f)
        assertFalse(state.claim(token))
    }
}
