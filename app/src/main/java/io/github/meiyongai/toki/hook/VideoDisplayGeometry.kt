package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.VideoFitMode
import kotlin.math.floor
import kotlin.math.roundToInt

/** Additional modes preserve aspect ratio; no arbitrary stretching or rotation of the playback surface. */
internal object VideoDisplayGeometry {
    fun calculate(videoWidth: Int, videoHeight: Int, width: Int, height: Int,
        mode: VideoFitMode, zoomPercent: Int = 100): VideoFillGeometry.Size? {
        if (minOf(videoWidth, videoHeight, width, height) <= 0 || mode == VideoFitMode.CENTER) return null
        if (mode == VideoFitMode.SMART || mode == VideoFitMode.FILL)
            return VideoFillGeometry.calculate(videoWidth, videoHeight, width, height, mode == VideoFitMode.FILL, zoomPercent)
        val x = width.toDouble() / videoWidth
        val y = height.toDouble() / videoHeight
        val factor = when (mode) {
            VideoFitMode.CONTAIN -> minOf(x, y)
            VideoFitMode.WIDTH -> x
            VideoFitMode.HEIGHT -> y
            else -> return null
        }
        val w = videoWidth * factor; val h = videoHeight * factor
        if (!w.isFinite() || !h.isFinite() || w > Int.MAX_VALUE || h > Int.MAX_VALUE) return null
        val resultWidth = when (mode) {
            VideoFitMode.WIDTH -> width
            VideoFitMode.CONTAIN -> floor(w + 0.000001).toInt().coerceIn(1, width)
            else -> w.roundToInt().coerceAtLeast(1)
        }
        val resultHeight = when (mode) {
            VideoFitMode.HEIGHT -> height
            VideoFitMode.CONTAIN -> floor(h + 0.000001).toInt().coerceIn(1, height)
            else -> h.roundToInt().coerceAtLeast(1)
        }
        return VideoFillGeometry.Size(resultWidth, resultHeight, resultWidth > width || resultHeight > height,
            mode == VideoFitMode.HEIGHT || mode == VideoFitMode.CONTAIN && y <= x)
    }
}
