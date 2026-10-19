package io.github.meiyongai.toki.subtitle.hook

import android.graphics.Color
import android.text.StaticLayout
import android.text.TextPaint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DescriptionTextLayoutTest {
    private fun original() = StaticLayout.Builder.obtain("Foreign description", 0, 19,
        TextPaint().apply { textSize = 36f; color = Color.WHITE }, 240).setMaxLines(2).build()

    @Test fun expandedLayoutContainsCompleteChineseTextAndKeepsOriginalStyle() {
        val original = original()
        val text = "中文长文案。".repeat(150)
        val result = DescriptionTextLayout.build(original, text, true)
        assertEquals(text, result.text.toString())
        assertEquals(text.length, result.getLineEnd(result.lineCount - 1))
        assertEquals(original.width, result.width)
        assertEquals(original.paint.textSize, result.paint.textSize)
        assertEquals(original.paint.color, result.paint.color)
        assertEquals("Foreign description", original.text.toString())
    }

    @Test fun collapsedLayoutUsesOriginalLineBudgetAndCanExpandWithoutOldText() {
        val original = original()
        val text = "这是已经翻译的长标题。".repeat(80)
        val collapsed = DescriptionTextLayout.build(original, text, false)
        assertTrue(collapsed.lineCount <= original.lineCount)
        val expanded = DescriptionTextLayout.build(original, text, true)
        assertTrue(expanded.lineCount > collapsed.lineCount)
        assertEquals(text, expanded.text.toString())
    }
}
