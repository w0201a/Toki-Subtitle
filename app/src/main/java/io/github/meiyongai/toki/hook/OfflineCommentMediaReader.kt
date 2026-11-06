package io.github.meiyongai.toki.subtitle.hook

import java.net.URI
import java.security.MessageDigest

/** Inspected CommentImageStruct origin/crop and existing native sticker reader; no label or UI-container inference. */
internal class OfflineCommentMediaReader(loader: ClassLoader) {
    private val comment = loader.loadClass("com.ss.android.ugc.aweme.comment.model.Comment")
    private val images = comment.getMethod("getImageList")
    private val image = loader.loadClass("com.ss.android.ugc.aweme.comment.model.CommentImageStruct")
    private val original = image.getMethod("getOriginUrl")
    private val crop = image.getMethod("getCropUrl")
    private val urls = original.returnType.getMethod("getUrlList")
    private val uri = original.returnType.getMethod("getUri")
    private val stickers = CommentStickerDownloadHook.Reader(loader)
    fun read(value: Any): List<OfflineCommentData.Media> {
        require(comment.isInstance(value))
        val result = mutableListOf<OfflineCommentData.Media>()
        for (item in (images.invoke(value) as? List<*>).orEmpty().take(10)) {
            if (item == null || !image.isInstance(item)) continue
            val choices = listOfNotNull(original.invoke(item), crop.invoke(item))
            val locations = CommentStickerMedia.urls(choices.flatMap { (urls.invoke(it) as? List<*>)?.filterIsInstance<String>().orEmpty() }, emptyList())
                .filter { it.length <= 4096 }
            val nativeId = choices.mapNotNull { uri.invoke(it) as? String }.firstOrNull { it.isNotBlank() }
            if (locations.isNotEmpty()) result += OfflineCommentData.Media(identity("image", nativeId, locations.first()),
                OfflineCommentData.MediaKind.IMAGE, locations)
        }
        stickers.read(value)?.let { sticker ->
            val animated = sticker.animatedUrls.isNotEmpty()
            val locations = (if (animated) sticker.animatedUrls else sticker.staticUrls).filter { it.length <= 4096 }.take(12)
            if (locations.isNotEmpty()) result += OfflineCommentData.Media(identity("sticker", sticker.id, locations.first()),
                OfflineCommentData.MediaKind.STICKER, locations, animated)
        }
        return result.take(12)
    }
    companion object {
        internal fun identity(kind: String, nativeId: String?, location: String): String {
            val source = URI(location)
            val stable = kind + ":" + nativeId.orEmpty() + ":" + source.host + source.path
            return DigestHex.encode(MessageDigest.getInstance("SHA-256").digest(stable.toByteArray(Charsets.UTF_8)))
        }
    }
}
