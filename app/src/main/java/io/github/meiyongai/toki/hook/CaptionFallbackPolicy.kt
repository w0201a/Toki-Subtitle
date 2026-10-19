package io.github.meiyongai.toki.subtitle.hook

/** 保留可用轨道；普通字幕轨不可用时回退原字幕，无原字幕时保持宿主结果。 */
internal object CaptionFallbackPolicy {
    // subId 是有符号轨道标识，真机存在 -1111210457；只有 -1 是缺失哨兵。
    fun choose(selected: Int, original: Int?, selectedUsable: Boolean = selected != -1): Int =
        if (!selectedUsable && original != null && original != -1) original else selected
}
