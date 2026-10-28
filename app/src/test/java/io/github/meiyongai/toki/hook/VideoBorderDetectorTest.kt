package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class VideoBorderDetectorTest {
    private fun frame(left: Int = 0, right: Int = left, top: Int = 0, bottom: Int = top): IntArray =
        IntArray(96 * 160) { i ->
            val x = i % 96; val y = i / 96
            if (x < left || x >= 96 - right || y < top || y >= 160 - bottom) 0xFF080808.toInt()
            else 0xFF91B4C2.toInt()
        }

    @Test fun detectsPairedEdgesButRejectsDarkFramesAndAsymmetricScenes() {
        val sides = checkNotNull(VideoBorderDetector.detect(frame(8), 96, 160))
        assertEquals(8f / 96, sides.horizontal, 0.001f)
        assertEquals(0f, sides.vertical)
        val letterbox = checkNotNull(VideoBorderDetector.detect(frame(top = 12), 96, 160))
        assertEquals(12f / 160, letterbox.vertical, 0.001f)
        assertNull(VideoBorderDetector.detect(frame(), 96, 160))
        assertNull(VideoBorderDetector.detect(frame(8, 0), 96, 160))
        assertNull(VideoBorderDetector.detect(IntArray(96 * 160), 96, 160))
        assertNull(VideoBorderDetector.detect(frame(24), 96, 160))
    }

    @Test fun requiresThreeConsecutiveMatchingFramesAndResetsAfterUncertainty() {
        val policy = VideoBorderDetector.Confirmation()
        val sides = VideoBorderDetector.Borders(0.08f, 0f)
        assertNull(policy.accept(sides))
        assertNull(policy.accept(sides))
        assertNull(policy.accept(null))
        assertNull(policy.accept(sides))
        assertNull(policy.accept(sides))
        assertEquals(sides, policy.accept(sides))
        assertNull(policy.accept(VideoBorderDetector.Borders(0f, 0.08f)))
    }

    @Test fun accountsForBordersAlreadyOutsideTheViewportAndBoundsExtraZoom() {
        val sides = VideoBorderDetector.Borders(0.08f, 0f)
        assertEquals(1f, sides.zoom(1400f, 2400f, 1080, 2400))
        assertEquals(1f / 0.84f, sides.zoom(1080f, 2400f, 1080, 2400), 0.001f)
        assertEquals(1.5f, sides.zoom(300f, 500f, 1080, 2400))
        assertEquals(1f, sides.zoom(0f, 0f, 1080, 2400))
    }
}
