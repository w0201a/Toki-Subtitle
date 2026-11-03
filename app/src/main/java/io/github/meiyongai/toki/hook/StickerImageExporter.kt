package io.github.meiyongai.toki.subtitle.hook

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** Decode/re-encode actual bytes on the bounded sticker worker, never by changing a filename suffix. */
internal object StickerImageExporter {
    data class Result(val bytes: ByteArray, val format: CommentStickerMedia.Format)

    fun convert(bytes: ByteArray, mode: StickerExportMode): Result {
        require(mode != StickerExportMode.ANIMATED_GIF && mode != StickerExportMode.ANIMATED_WEBP) { "Use the complete animation exporter" }
        require(bytes.isNotEmpty() && bytes.size <= CommentStickerMedia.MAX_BYTES)
        val original = requireNotNull(CommentStickerMedia.format(bytes)) { "Unsupported source image" }
        if (mode == StickerExportMode.ORIGINAL) return Result(bytes, original)
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            val target = StickerExportPolicy.target(info.size.width, info.size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(target.width, target.height)
            decoder.setOnPartialImageListener { false }
        }
        var opaque: Bitmap? = null
        try {
            val output = BoundedOutput()
            val source = if (mode == StickerExportMode.JPEG && bitmap.hasAlpha()) {
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { flattened ->
                    opaque = flattened
                    Canvas(flattened).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                }
            } else bitmap
            val compression = when (mode) {
                StickerExportMode.PNG -> Bitmap.CompressFormat.PNG
                StickerExportMode.JPEG -> Bitmap.CompressFormat.JPEG
                StickerExportMode.WEBP -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS
                    else legacyWebp()
                StickerExportMode.ORIGINAL -> error("Original returned before decoding")
                StickerExportMode.ANIMATED_GIF, StickerExportMode.ANIMATED_WEBP -> error("Animation must not use a first-frame encoder")
            }
            check(source.compress(compression, if (mode == StickerExportMode.JPEG) 95 else 100, output)) { "Image encoding failed" }
            val encoded = output.bytes()
            val format = requireNotNull(CommentStickerMedia.format(encoded))
            check(format.extension == mode.extension && format.mime == mode.mime) { "Encoder output does not match export format" }
            return Result(encoded, format)
        } finally {
            opaque?.recycle()
            bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyWebp(): Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP

    /** Refuse output growth before writing, rather than checking only after allocation. */
    private class BoundedOutput : OutputStream() {
        private val output = ByteArrayOutputStream()
        override fun write(value: Int) {
            check(output.size() < CommentStickerMedia.MAX_BYTES)
            output.write(value)
        }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            check(output.size().toLong() + length <= CommentStickerMedia.MAX_BYTES)
            output.write(bytes, offset, length)
        }
        fun bytes(): ByteArray = output.toByteArray()
    }
}
