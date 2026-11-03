package io.github.meiyongai.toki.subtitle.hook

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageInputStream
import org.junit.Assert.*
import org.junit.Test

class AnimatedStickerContainersTest {
    private fun sequence(width: Int = 2, height: Int = 2, plays: Int = 3) = AnimatedStickerData.Sequence(width, height,
        listOf(40, 80).map { AnimatedStickerData.Frame(0, 0, width, height, it) }, plays, 0, "gif")

    @Test fun gifOutputIsDecodedByAnIndependentReaderWithAllFramesAndTransparency() {
        val source = sequence(); val encoder = AnimatedStickerContainers.Gif(source)
        val frames = listOf(intArrayOf(0xffff0000.toInt(), 0xffff0000.toInt(), 0xffff0000.toInt(), 0xffff0000.toInt()),
            intArrayOf(0xff0000ff.toInt(), 0, 0, 0))
        frames.forEachIndexed { index, pixels -> encoder.frame(pixels, source.frames[index].durationMs) }
        val bytes = encoder.finish()
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            try {
                reader.input = input
                assertEquals(2, reader.getNumImages(true))
                frames.forEachIndexed { index, pixels ->
                    val image = reader.read(index)
                    for (pixel in pixels.indices) assertEquals(pixels[pixel], image.getRGB(pixel % 2, pixel / 2))
                }
            } finally { reader.dispose() }
        }
        val parsed = AnimatedStickerData.parse(bytes)
        assertEquals(listOf(40, 80), parsed.frames.map { it.durationMs }); assertEquals(3, parsed.plays)
    }
    @Test fun gifDictionaryResetBoundariesRemainDecodableAcrossManyNonRepeatingColors() {
        val source = sequence(64, 64); val encoder = AnimatedStickerContainers.Gif(source)
        var state = 70931
        val pixels = IntArray(4096) {
            state = state * 1664525 + 1013904223
            val index = (state ushr 1) % 216
            (255 shl 24) or ((index / 36 * 51) shl 16) or (((index / 6) % 6 * 51) shl 8) or (index % 6 * 51)
        }
        source.frames.forEach { encoder.frame(pixels, it.durationMs) }
        val bytes = encoder.finish()
        MemoryCacheImageInputStream(ByteArrayInputStream(bytes)).use { input ->
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            try {
                reader.input = input
                repeat(2) { frame ->
                    val image = reader.read(frame)
                    pixels.indices.forEach { assertEquals(pixels[it], image.getRGB(it % 64, it / 64)) }
                }
            } finally { reader.dispose() }
        }
    }
    @Test fun webpWriterUsesRealEncodedImagePayloadsAndPreservesFrameTimingAndNoBlend() {
        val source = sequence(); val encoder = AnimatedStickerContainers.Webp(source)
        source.frames.forEach { encoder.frame(AnimatedStickerFixtures.STATIC_WEBP, it.durationMs) }
        val result = AnimatedStickerData.parse(encoder.finish())
        assertEquals(2, result.frames.size); assertEquals(listOf(40, 80), result.frames.map { it.durationMs })
        assertEquals(3, result.plays); assertTrue(result.frames.none { it.blend })
        assertTrue(result.frames.all { it.width == 2 && it.height == 2 && it.x == 0 && it.y == 0 })
    }
    @Test fun incompleteWritersExtraFramesAndNestedAnimatedPayloadsAreRejected() {
        val source = sequence(); val gif = AnimatedStickerContainers.Gif(source)
        assertThrows(IllegalStateException::class.java) { gif.finish() }
        source.frames.forEach { gif.frame(IntArray(4), it.durationMs) }
        assertThrows(IllegalStateException::class.java) { gif.frame(IntArray(4), 10) }
        gif.finish(); assertThrows(IllegalStateException::class.java) { gif.finish() }
        val webp = AnimatedStickerContainers.Webp(source)
        assertThrows(IllegalArgumentException::class.java) { webp.frame(AnimatedStickerFixtures.WEBP, 40) }
        assertThrows(IllegalArgumentException::class.java) { AnimatedStickerContainers.Webp(sequence(plays = 65536)) }
    }
    @Test fun gifLoopLimitRemainsRepresentableAndAlphaIsExplicitlyBinary() {
        val source = sequence(plays = 65536); val gif = AnimatedStickerContainers.Gif(source)
        source.frames.forEach { gif.frame(intArrayOf(0x40ff0000, 0xc0ff0000.toInt(), 0, 0xff000000.toInt()), it.durationMs) }
        val bytes = gif.finish()
        assertEquals(65536, AnimatedStickerData.parse(bytes).plays)
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(0, image.getRGB(0, 0)); assertEquals(0xffff0000.toInt(), image.getRGB(1, 0))
        assertEquals(0xff000000.toInt(), image.getRGB(1, 1))
    }
}
