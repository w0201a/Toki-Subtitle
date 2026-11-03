package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.OperationJournal
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.abs

/** Compose every source patch/disposal into a complete canvas, then encode real animated output. */
internal object AnimatedStickerExporter {
    private class FrameOutput : OutputStream() {
        private val output = ByteArrayOutputStream()
        override fun write(value: Int) { check(output.size() < CommentStickerMedia.MAX_BYTES); output.write(value) }
        override fun write(bytes: ByteArray, offset: Int, count: Int) {
            require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
            check(output.size().toLong() + count <= CommentStickerMedia.MAX_BYTES); output.write(bytes, offset, count)
        }
        fun bytes(): ByteArray = output.toByteArray()
    }
    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP

    fun convert(context: Context, loader: ClassLoader, bytes: ByteArray, mode: StickerExportMode, active: () -> Boolean, operation: OperationJournal.Run? = null): StickerImageExporter.Result {
        require(mode == StickerExportMode.ANIMATED_GIF || mode == StickerExportMode.ANIMATED_WEBP)
        operation?.stage(OperationJournal.Step.ANIMATION_PARSE, mapOf("sourceBytes" to bytes.size.toLong()))
        val source = AnimatedStickerData.parse(bytes)
        require(source.frames.size > 1) { "Selected source contains no animation" }
        val gif = if (mode == StickerExportMode.ANIMATED_GIF) AnimatedStickerContainers.Gif(source) else null
        val webp = if (mode == StickerExportMode.ANIMATED_WEBP) AnimatedStickerContainers.Webp(source) else null
        fun checkActive() { check(active() && !Thread.currentThread().isInterrupted) { "Animation conversion cancelled" } }
        checkActive()
        val canvasBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        var previous: Bitmap? = null
        try {
            canvasBitmap.eraseColor(source.background)
            val canvas = Canvas(canvasBitmap)
            val replace = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
            val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
            val pixels = if (gif != null) IntArray(source.width * source.height) else null
            operation?.stage(OperationJournal.Step.ANIMATION_DECODE, mapOf("frames" to source.frames.size.toLong(),
                "width" to source.width.toLong(), "height" to source.height.toLong()))
            HostAnimatedStickerDecoder.open(context, loader, source, bytes).use { decoder ->
                operation?.stage(OperationJournal.Step.ANIMATION_ENCODE, mapOf("frames" to source.frames.size.toLong()))
                source.frames.forEachIndexed { index, frame ->
                    checkActive()
                    if (frame.disposal == AnimatedStickerData.Disposal.RESTORE) {
                        val snapshot = previous ?: Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { previous = it }
                        Canvas(snapshot).drawBitmap(canvasBitmap, 0f, 0f, replace)
                    }
                    val patch = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                    try {
                        patch.eraseColor(0); decoder.render(index, frame, patch)
                        canvas.drawBitmap(patch, frame.x.toFloat(), frame.y.toFloat(), if (frame.blend) null else replace)
                    } finally { patch.recycle() }
                    checkActive()
                    if (gif != null && pixels != null) {
                        canvasBitmap.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
                        gif.frame(pixels, frame.durationMs)
                    } else {
                        val output = FrameOutput()
                        check(canvasBitmap.compress(webpFormat(), 100, output)) { "WebP frame encoding failed" }
                        checkNotNull(webp).frame(output.bytes(), frame.durationMs)
                    }
                    when (frame.disposal) {
                        AnimatedStickerData.Disposal.KEEP -> Unit
                        AnimatedStickerData.Disposal.CLEAR -> {
                            clear.color = frame.clearColor
                            canvas.drawRect(frame.x.toFloat(), frame.y.toFloat(), (frame.x + frame.width).toFloat(), (frame.y + frame.height).toFloat(), clear)
                        }
                        AnimatedStickerData.Disposal.RESTORE -> canvas.drawBitmap(checkNotNull(previous), 0f, 0f, replace)
                    }
                }
            }
            checkActive()
            val output = gif?.finish() ?: checkNotNull(webp).finish()
            operation?.stage(OperationJournal.Step.READBACK, mapOf("outputBytes" to output.size.toLong()))
            val result = AnimatedStickerData.parse(output)
            check(result.frames.size == source.frames.size && result.plays == source.plays &&
                result.width == source.width && result.height == source.height) { "Encoded animation metadata mismatch" }
            if (gif != null) {
                var expected = 0L; var actual = 0L
                source.frames.indices.forEach { index ->
                    expected += source.frames[index].durationMs; actual += result.frames[index].durationMs
                    check(abs(expected - actual) <= 5) { "GIF timing rounding drifted" }
                }
            } else check(result.frames.map { it.durationMs } == source.frames.map { it.durationMs })
            val format = checkNotNull(CommentStickerMedia.format(output))
            check(format.extension == mode.extension && format.mime == mode.mime)
            return StickerImageExporter.Result(output, format)
        } finally { previous?.recycle(); canvasBitmap.recycle() }
    }
}
