package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class LiveSwipePolicyTest {
    /** 纵向超过阈值才拦截，横向为主的手势保留。 */
    @Test fun blocksOnlyVerticalDragsPastSlop() {
        assertTrue(LiveSwipePolicy.blocks(0f, 24f, 8f))
        assertTrue(LiveSwipePolicy.blocks(0f, -24f, 8f))
        assertTrue(LiveSwipePolicy.blocks(0f, 8f, 8f))
        assertFalse(LiveSwipePolicy.blocks(30f, 20f, 8f))
        assertFalse(LiveSwipePolicy.blocks(0f, 24f, 0f))
        assertFalse(LiveSwipePolicy.blocks(Float.NaN, 24f, 8f))
    }
}
