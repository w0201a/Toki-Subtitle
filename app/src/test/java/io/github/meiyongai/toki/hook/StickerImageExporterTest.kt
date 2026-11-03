package io.github.meiyongai.toki.subtitle.hook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StickerImageExporterTest {
    @Test fun animatedIntentCannotAccidentallyUseTheStaticFirstFrameEncoder() {
        for (mode in listOf(StickerExportMode.ANIMATED_GIF, StickerExportMode.ANIMATED_WEBP)) {
            assertThrows(IllegalArgumentException::class.java) { StickerImageExporter.convert(AnimatedStickerFixtures.GIF, mode) }
        }
    }

    private fun transparentPng(): ByteArray {
        val image = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.TRANSPARENT)
            return ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        } finally { image.recycle() }
    }

    @Test fun originalExportReturnsUnmodifiedAnimatedBytes() {
        val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        val result = StickerImageExporter.convert(gif, StickerExportMode.ORIGINAL)
        assertSame(gif, result.bytes)
        assertEquals("image/gif", result.format.mime)
    }

    @Test fun eachStaticExportHasRealEncodedBytesAndTheDeclaredFormat() {
        val source = transparentPng()
        for (mode in listOf(StickerExportMode.PNG, StickerExportMode.JPEG, StickerExportMode.WEBP)) {
            val result = StickerImageExporter.convert(source, mode)
            assertEquals(mode.extension, CommentStickerMedia.format(result.bytes)?.extension)
            assertEquals(mode.mime, result.format.mime)
            val decoded = checkNotNull(BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size))
            try {
                assertEquals(16, decoded.width)
                assertEquals(16, decoded.height)
                val pixel = decoded.getPixel(0, 0)
                if (mode == StickerExportMode.JPEG) {
                    assertEquals(255, Color.alpha(pixel))
                    assertTrue(Color.red(pixel) > 245 && Color.green(pixel) > 245 && Color.blue(pixel) > 245)
                } else assertEquals(0, Color.alpha(pixel))
            } finally { decoded.recycle() }
        }
    }

    @Test fun animationConversionProducesAStaticPngAndInvalidInputIsRejected() {
        val gif = java.util.Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7")
        val converted = StickerImageExporter.convert(gif, StickerExportMode.PNG)
        assertEquals("png", converted.format.extension)
        assertFalse(converted.bytes.contentEquals(gif))
        assertThrows(IllegalArgumentException::class.java) { StickerImageExporter.convert(byteArrayOf(), StickerExportMode.PNG) }
        assertThrows(IllegalArgumentException::class.java) { StickerImageExporter.convert("<html>error</html>".toByteArray(), StickerExportMode.ORIGINAL) }
        assertThrows(IllegalArgumentException::class.java) { StickerImageExporter.convert(ByteArray(CommentStickerMedia.MAX_BYTES + 1), StickerExportMode.PNG) }
    }
}
