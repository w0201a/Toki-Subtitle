package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CommentStickerMediaTest {
    @Test fun prefersAnimatedHighQualitySourcesAndRejectsUnexpectedLocations() {
        val animated = "https://media.giphy.com/media/id/giphy.gif?signature=abc"
        val static = "https://p16.tiktokcdn.com/preview.webp"
        assertEquals(listOf(animated, static), CommentStickerMedia.urls(
            listOf("http://media.giphy.com/a.gif", animated, animated, "https://127.0.0.1/a.gif"), listOf(static)))
        for (url in listOf("file:///data/private", "https://tiktokcdn.com.attacker.invalid/a",
            "https://user:password@p16.tiktokcdn.com/a", "https://p16.tiktokcdn.com:8443/a", "https://localhost/a")) {
            assertFalse(url, CommentStickerMedia.allowed(url))
        }
    }

    @Test fun detectsOriginalImageTypeInsteadOfTrustingUrlSuffixOrResponseMime() {
        assertEquals("gif", CommentStickerMedia.format("GIF89aDATA".toByteArray())?.extension)
        assertEquals("image/png", CommentStickerMedia.format(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))?.mime)
        assertEquals("jpg", CommentStickerMedia.format(byteArrayOf(255.toByte(), 216.toByte(), 255.toByte()))?.extension)
        assertEquals("webp", CommentStickerMedia.format("RIFF0000WEBPVP8X".toByteArray())?.extension)
        assertEquals("avif", CommentStickerMedia.format("0000ftypavif".toByteArray())?.extension)
        assertNull(CommentStickerMedia.format("<html>Access denied</html>".toByteArray()))
        assertNull(CommentStickerMedia.format(byteArrayOf()))
    }

    @Test fun boundedTransferPreservesAnimationBytesAndRejectsOversizedPayloads() {
        val bytes = "GIF89a\u0000\u0001FRAME1FRAME2".toByteArray()
        assertArrayEquals(bytes, CommentStickerMedia.read(bytes.inputStream(), bytes.size))
        assertThrows(IllegalStateException::class.java) {
            CommentStickerMedia.read(bytes.inputStream(), bytes.size - 1)
        }
    }

    @Test fun fileNameCannotEscapeTargetDirectoryAndUsesDetectedExtension() {
        val name = CommentStickerMedia.fileName("../../id/\\name", "gif", "unique-123")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertTrue(name.endsWith("unique-123.gif"))
        assertThrows(IllegalArgumentException::class.java) { CommentStickerMedia.fileName("id", "exe", "x") }
    }

    class UrlModel(private val list: List<String>) { fun getUrlList() = list }
    class Sizes(high: List<String> = emptyList(), medium: List<String> = emptyList(), low: List<String> = emptyList()) {
        @JvmField val highResolutionUrl = UrlModel(high)
        @JvmField val midResolutionUrl = UrlModel(medium)
        @JvmField val lowResolutionUrl = UrlModel(low)
    }
    class Sticker(private val animated: Sizes?, private val static: Sizes?) {
        fun getAnimatedUrl() = animated
        fun getStaticUrl() = static
        fun getId() = "sticker-id"
    }
    class Comment(private val data: Sticker?) { fun getStickerStruct() = data }

    @Test fun readsCommentStickerOnlyAndFallsBackToStaticWhenAnimationIsMissing() {
        val reader = CommentStickerDownloadHook.Reader(Comment::class.java)
        val animated = "https://p16.tiktokcdn.com/animated.gif"
        val preview = "https://p16.tiktokcdn.com/static.webp"
        val asset = checkNotNull(reader.read(Comment(Sticker(Sizes(listOf(animated)), Sizes(listOf(preview))))))
        assertEquals("sticker-id", asset.id)
        assertEquals(listOf(animated, preview), asset.urls)
        assertEquals(listOf(preview), reader.read(Comment(Sticker(null, Sizes(listOf(preview)))))?.urls)
        assertNull(reader.read(Comment(null)))
        assertNull(reader.read(Any()))
    }
}
