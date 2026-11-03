package io.github.meiyongai.toki.subtitle.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView
import io.github.meiyongai.toki.subtitle.model.SubtitleAppearance
import kotlin.math.abs
import kotlin.math.ceil

/** Two drawing passes, without setTextColor/invalidate calls from onDraw. Used by preview and playback alike. */
internal class StyledSubtitleTextView(context: Context) : TextView(context) {
    private var outlineWidthPx = 0f
    private var outlineColor = Color.BLACK

    fun applyAppearance(style: SubtitleAppearance, background: Int) {
        val density = resources.displayMetrics.density
        outlineWidthPx = style.outlineWidth * density
        outlineColor = Color.parseColor(style.outlineColor)
        setShadowLayer(style.shadowRadius * density, style.shadowDx * density, style.shadowDy * density,
            Color.parseColor(style.shadowColor))
        setLineSpacing(0f, style.lineSpacing)
        letterSpacing = style.letterSpacing
        maxLines = if (style.maxLines == 0) Int.MAX_VALUE else style.maxLines
        gravity = Gravity.CENTER_VERTICAL or when (style.alignment) {
            "start" -> Gravity.START
            "end" -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        val extentX = if (style.shadowRadius > 0) style.shadowRadius + abs(style.shadowDx) else 0f
        val extentY = if (style.shadowRadius > 0) style.shadowRadius + abs(style.shadowDy) else 0f
        val padX = ceil((style.paddingHorizontal + maxOf(style.outlineWidth, extentX)) * density).toInt()
        val padY = ceil((style.paddingVertical + maxOf(style.outlineWidth, extentY)) * density).toInt()
        setPadding(padX, padY, padX, padY)
        setBackground(GradientDrawable().apply {
            setColor(background)
            cornerRadius = style.backgroundRadius * density
        })
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val lines = layout
        if (outlineWidthPx > 0 && lines != null && text.isNotEmpty()) {
            val previousStyle = paint.style
            val previousWidth = paint.strokeWidth
            val previousJoin = paint.strokeJoin
            val previousColor = paint.color
            val radius = shadowRadius
            val dx = shadowDx
            val dy = shadowDy
            val shadow = shadowColor
            val save = canvas.save()
            try {
                paint.clearShadowLayer()
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = outlineWidthPx * 2
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = outlineColor
                val available = height - extendedPaddingTop - extendedPaddingBottom - lines.height
                val vertical = when (gravity and Gravity.VERTICAL_GRAVITY_MASK) {
                    Gravity.BOTTOM -> available
                    Gravity.CENTER_VERTICAL -> available / 2
                    else -> 0
                }.coerceAtLeast(0)
                canvas.translate(compoundPaddingLeft.toFloat(), (extendedPaddingTop + vertical).toFloat())
                if (maxLines < lines.lineCount && maxLines > 0) {
                    canvas.clipRect(-outlineWidthPx, -outlineWidthPx,
                        lines.width.toFloat() + outlineWidthPx, lines.getLineBottom(maxLines - 1).toFloat() + outlineWidthPx)
                }
                lines.draw(canvas)
            } finally {
                canvas.restoreToCount(save)
                paint.style = previousStyle
                paint.strokeWidth = previousWidth
                paint.strokeJoin = previousJoin
                paint.color = previousColor
                paint.setShadowLayer(radius, dx, dy, shadow)
            }
        }
        super.onDraw(canvas)
    }
}
