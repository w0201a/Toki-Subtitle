package io.github.meiyongai.toki.subtitle.hook

/** Explicit export intent: only ORIGINAL preserves animation; conversions export one frame. */
internal enum class StickerExportMode(val extension: String?, val mime: String?) {
    ORIGINAL(null, null), PNG("png", "image/png"), JPEG("jpg", "image/jpeg"), WEBP("webp", "image/webp")
}

internal enum class StickerSource { AUTOMATIC, ANIMATED, STATIC }

internal object StickerExportPolicy {
    const val MAX_EDGE = 2048
    private const val MAX_SOURCE_EDGE = 65536
    private const val MAX_SOURCE_PIXELS = 100_000_000L
    data class Dimensions(val width: Int, val height: Int)

    fun target(width: Int, height: Int): Dimensions {
        require(width in 1..MAX_SOURCE_EDGE && height in 1..MAX_SOURCE_EDGE)
        require(width.toLong() * height <= MAX_SOURCE_PIXELS)
        val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(width, height))
        return Dimensions(maxOf(1, (width * scale).toInt()), maxOf(1, (height * scale).toInt()))
    }

    fun sources(animated: List<String>, static: List<String>, source: StickerSource): List<String> = when (source) {
        StickerSource.AUTOMATIC -> CommentStickerMedia.urls(animated, static)
        StickerSource.ANIMATED -> CommentStickerMedia.urls(animated, emptyList())
        StickerSource.STATIC -> CommentStickerMedia.urls(emptyList(), static)
    }
}
