package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class AnimatedStickerDataTest {
    @Test fun independentGifAndWebpFixturesKeepCanvasDurationsAndTotalPlays() {
        for (bytes in listOf(AnimatedStickerFixtures.GIF, AnimatedStickerFixtures.WEBP)) {
            val value = AnimatedStickerData.parse(bytes)
            assertEquals(2, value.width); assertEquals(2, value.height)
            assertEquals(listOf(40, 80), value.frames.map { it.durationMs })
            assertEquals(3, value.plays)
            assertEquals(2, value.frames.size)
        }
        assertTrue(AnimatedStickerData.parse(AnimatedStickerFixtures.GIF).frames.all { it.disposal == AnimatedStickerData.Disposal.CLEAR })
    }
    @Test fun gifTimingRoundsTheCumulativeClockInsteadOfAccumulatingPerFrameError() {
        val source = AnimatedStickerData.Sequence(1, 1, listOf(1, 8, 17).map { AnimatedStickerData.Frame(0, 0, 1, 1, it) }, 0, 0, "webp")
        val encoder = AnimatedStickerContainers.Gif(source)
        source.frames.forEach { encoder.frame(intArrayOf(0xffff0000.toInt()), it.durationMs) }
        val result = AnimatedStickerData.parse(encoder.finish())
        assertEquals(listOf(0, 10, 20), result.frames.map { it.durationMs })
        assertEquals(0, result.plays)
    }
    @Test fun truncationWrongRiffSizesAndTrailingGifBytesAreNotAcceptedAsCompleteAnimation() {
        for (bytes in listOf(AnimatedStickerFixtures.GIF, AnimatedStickerFixtures.WEBP)) {
            assertThrows(IllegalArgumentException::class.java) { AnimatedStickerData.parse(bytes.copyOf(bytes.size - 1)) }
        }
        assertThrows(IllegalArgumentException::class.java) { AnimatedStickerData.parse(AnimatedStickerFixtures.GIF + byteArrayOf(0)) }
        val changed = AnimatedStickerFixtures.WEBP.also { it[4] = 0 }
        assertThrows(IllegalArgumentException::class.java) { AnimatedStickerData.parse(changed) }
        assertThrows(IllegalArgumentException::class.java) { AnimatedStickerData.parse(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { AnimatedStickerData.parse(AnimatedStickerFixtures.STATIC_WEBP) }
    }
    @Test fun oversizedCanvasAndCancelledConversionFailBeforeNativeDecode() {
        val changed = AnimatedStickerFixtures.GIF.also { it[6] = 0; it[7] = 8; it[8] = 0; it[9] = 8 }
        assertThrows(IllegalArgumentException::class.java) { AnimatedStickerData.parse(changed) }
        try {
            Thread.currentThread().interrupt()
            assertThrows(IllegalStateException::class.java) { AnimatedStickerData.parse(AnimatedStickerFixtures.GIF) }
        } finally { Thread.interrupted() }
    }
}
