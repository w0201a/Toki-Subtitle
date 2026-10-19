package io.github.meiyongai.toki.subtitle.hook

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/** 最终布局保留原字号、颜色、宽度及行间距；展开时容纳完整译文。 */
internal object DescriptionTextLayout {
    fun build(original: Layout, text: String, expanded: Boolean): Layout =
        StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(original.paint), original.width.coerceAtLeast(1))
            .setAlignment(original.alignment)
            .setIncludePad(original.topPadding != 0 || original.bottomPadding != 0)
            .setLineSpacing(original.spacingAdd, original.spacingMultiplier)
            .setMaxLines(if (expanded) Int.MAX_VALUE else original.lineCount.coerceAtLeast(1))
            .setEllipsize(if (expanded) null else TextUtils.TruncateAt.END)
            .build()
}
