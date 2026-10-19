package io.github.meiyongai.toki.subtitle.model

/** Android 系统字体名称；设置预览与宿主绘制使用相同字体族。 */
internal object SubtitleFonts {
    val choices = listOf(
        "sans-serif" to "默认黑体",
        "serif" to "衬线体",
        "monospace" to "等宽体",
        "sans-serif-condensed" to "窄体",
        "sans-serif-light" to "细体",
        "sans-serif-thin" to "纤细体",
        "sans-serif-medium" to "中等黑体",
        "sans-serif-black" to "重黑体",
        "sans-serif-condensed-light" to "细窄体",
        "sans-serif-smallcaps" to "小型大写",
        "serif-monospace" to "衬线等宽",
        "casual" to "手写体",
        "cursive" to "花体",
    )
    val names = choices.map { it.first }.toSet()
}
