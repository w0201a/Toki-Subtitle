package io.github.meiyongai.toki.subtitle.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import org.robolectric.RuntimeEnvironment
import io.github.meiyongai.toki.subtitle.model.SubtitleAppearance
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class StyledSubtitleTextViewTest {
    @Test fun stylePropertiesMatchTheSharedConfigurationAndCanReset() {
        val view = StyledSubtitleTextView(RuntimeEnvironment.getApplication())
        val config = ConfigSnapshot(mapOf("subtitle_outline_width" to "2", "subtitle_shadow_radius" to "3",
            "subtitle_letter_spacing" to "0.1", "subtitle_line_spacing" to "1.5", "subtitle_max_lines" to "4",
            "subtitle_alignment" to "end"))
        view.applyAppearance(SubtitleAppearance.from(config), Color.BLACK)
        assertEquals(0.1f, view.letterSpacing, 0.0001f)
        assertEquals(1.5f, view.lineSpacingMultiplier, 0.0001f)
        assertEquals(4, view.maxLines)
        assertEquals(Gravity.END, view.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)
        view.applyAppearance(SubtitleAppearance.from(ConfigSnapshot(emptyMap())), Color.TRANSPARENT)
        assertEquals(0f, view.letterSpacing, 0f)
        assertEquals(1f, view.lineSpacingMultiplier, 0f)
        assertEquals(Int.MAX_VALUE, view.maxLines)
        assertEquals(0f, view.shadowRadius, 0f)
    }

    @Test fun outlineDrawingRestoresFillPaintAndTextColor() {
        val view = StyledSubtitleTextView(RuntimeEnvironment.getApplication())
        view.text = "Subtitle\n字幕"
        view.setTextColor(Color.YELLOW)
        view.applyAppearance(SubtitleAppearance.from(ConfigSnapshot(mapOf("subtitle_outline_width" to "2"))), Color.TRANSPARENT)
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 400, 200)
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        assertEquals(Paint.Style.FILL, view.paint.style)
        assertEquals(Color.YELLOW, view.currentTextColor)
        assertEquals("Subtitle\n字幕", view.text.toString())
    }
}
