package io.github.meiyongai.toki.subtitle.hook

import kotlin.math.abs
import kotlin.math.max

/** Conservative edge-only letterbox detection on a small, in-memory video frame. */
internal object VideoBorderDetector {
    class Confirmation {
        private var candidate: Borders? = null
        private var count = 0
        fun accept(borders: Borders?): Borders? {
            if (borders != null && candidate?.agrees(borders) == true) count++ else {
                candidate = borders
                count = if (borders == null) 0 else 1
            }
            return borders?.takeIf { count >= 3 }
        }
    }
    data class Borders(val horizontal: Float, val vertical: Float) {
        fun zoom(width: Float, height: Float, viewportWidth: Int, viewportHeight: Int): Float {
            if (minOf(width, height, viewportWidth.toFloat(), viewportHeight.toFloat()) <= 0f) return 1f
            return max(1f, max(viewportWidth / (width * (1f - 2f * horizontal)),
                viewportHeight / (height * (1f - 2f * vertical)))).coerceAtMost(1.5f)
        }
        fun agrees(other: Borders): Boolean = abs(horizontal - other.horizontal) <= 0.015f &&
            abs(vertical - other.vertical) <= 0.015f
    }

    fun detect(pixels: IntArray, width: Int, height: Int): Borders? {
        if (width < 32 || height < 32 || width.toLong() * height != pixels.size.toLong()) return null
        fun black(pixel: Int): Boolean = (pixel ushr 16 and 255) <= 24 &&
            (pixel ushr 8 and 255) <= 24 && (pixel and 255) <= 24
        // Never infer borders from a fade, a loading surface or a nearly black scene.
        if (pixels.count { !black(it) } < pixels.size / 5) return null
        fun column(x: Int): Boolean = (0 until height).count { black(pixels[it * width + x]) } >= height * 0.99
        fun row(y: Int): Boolean = (0 until width).count { black(pixels[y * width + it]) } >= width * 0.99
        fun margin(size: Int, edge: (Int) -> Boolean): Int {
            val limit = size / 5
            var count = 0
            while (count < limit && edge(count)) count++
            return if (count == limit) 0 else count
        }
        val left = margin(width, ::column)
        val right = margin(width) { column(width - 1 - it) }
        val top = margin(height, ::row)
        val bottom = margin(height) { row(height - 1 - it) }
        fun paired(a: Int, b: Int, size: Int): Float =
            if (a >= 2 && b >= 2 && abs(a - b) <= 2) minOf(a, b).toFloat() / size else 0f
        val x = paired(left, right, width)
        val y = paired(top, bottom, height)
        return if (x == 0f && y == 0f) null else Borders(x, y)
    }
}
