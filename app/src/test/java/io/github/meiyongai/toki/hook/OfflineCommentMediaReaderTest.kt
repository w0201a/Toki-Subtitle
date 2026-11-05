package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class OfflineCommentMediaReaderTest {
    class Url(private val value: List<String>, private val nativeId: String = "") {
        fun getUrlList(): List<String> = value
        fun getUri(): String = nativeId
    }
    class Image(private val origin: Url?, private val cropped: Url?) {
        fun getOriginUrl(): Url? = origin
        fun getCropUrl(): Url? = cropped
    }
    class Sizes(high: Url?) {
        @JvmField val highResolutionUrl = high
        @JvmField val midResolutionUrl: Url? = null
        @JvmField val lowResolutionUrl: Url? = null
    }
    class Sticker(private val animation: Sizes?, private val preview: Sizes?) {
        fun getId(): String = "native-sticker"
        fun getAnimatedUrl(): Sizes? = animation
        fun getStaticUrl(): Sizes? = preview
    }
    class Comment(private val photos: List<Image>, private val sticker: Sticker?) {
        fun getImageList(): List<Image> = photos
        fun getStickerStruct(): Sticker? = sticker
    }
    private fun reader(): OfflineCommentMediaReader {
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> = when (name) {
                "com.ss.android.ugc.aweme.comment.model.Comment" -> Comment::class.java
                "com.ss.android.ugc.aweme.comment.model.CommentImageStruct" -> Image::class.java
                else -> super.loadClass(name)
            }
        }
        return OfflineCommentMediaReader(loader)
    }
    @Test fun extractsOriginCropAndAnimatedStickerThroughTheVerifiedNativeMethodShapes() {
        val image = Image(Url(listOf("https://p16.tiktokcdn.com/original.jpg?sign=a"), "image-id"),
            Url(listOf("https://p16.tiktokcdn.com/cropped.jpg")))
        val sticker = Sticker(Sizes(Url(listOf("https://p16.tiktokcdn.com/a.gif"))),
            Sizes(Url(listOf("https://p16.tiktokcdn.com/static.webp"))))
        val values = reader().read(Comment(listOf(image), sticker))
        assertEquals(2, values.size)
        assertEquals(OfflineCommentData.MediaKind.IMAGE, values[0].kind)
        assertEquals(2, values[0].urls.size)
        assertEquals(OfflineCommentData.MediaKind.STICKER, values[1].kind)
        assertTrue(values[1].animated)
        assertEquals(listOf("https://p16.tiktokcdn.com/a.gif"), values[1].urls)
    }
    @Test fun croppedImageAndStaticStickerAreRetainedWhenOriginalOrAnimatedSourcesAreAbsent() {
        val values = reader().read(Comment(listOf(Image(null, Url(listOf("https://p16.tiktokcdn.com/crop.png")))),
            Sticker(null, Sizes(Url(listOf("https://p16.tiktokcdn.com/static.webp"))))))
        assertEquals(2, values.size)
        assertTrue(values.none { it.animated })
        assertEquals(listOf("https://p16.tiktokcdn.com/crop.png"), values[0].urls)
        assertTrue(reader().read(Comment(emptyList(), null)).isEmpty())
    }
}
