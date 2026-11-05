package io.github.meiyongai.toki.subtitle.model

import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot

/** Shared configuration contract for the preview and injected subtitle renderer. Dimensions are dp, spacing is em. */
internal data class SubtitleAppearance(
    val outlineWidth: Float, val outlineColor: String,
    val shadowRadius: Float, val shadowDx: Float, val shadowDy: Float, val shadowColor: String,
    val lineSpacing: Float, val letterSpacing: Float, val backgroundRadius: Float,
    val paddingHorizontal: Float, val paddingVertical: Float, val alignment: String, val maxLines: Int,
) {
    companion object {
        val defaults: Map<String, String> = linkedMapOf(
            "subtitle_outline_width" to "0", "subtitle_outline_color" to "#FF000000",
            "subtitle_shadow_radius" to "0", "subtitle_shadow_dx" to "0", "subtitle_shadow_dy" to "1",
            "subtitle_shadow_color" to "#CC000000", "subtitle_line_spacing" to "1", "subtitle_letter_spacing" to "0",
            "subtitle_background_radius" to "0", "subtitle_padding_horizontal" to "8", "subtitle_padding_vertical" to "4",
            "subtitle_alignment" to "center", "subtitle_max_lines" to "0", "subtitle_text_mode" to "translated",
            "subtitle_bilingual_order" to "original_first",
        )
        val ranges = mapOf(
            "subtitle_outline_width" to 0.0..6.0, "subtitle_shadow_radius" to 0.0..12.0,
            "subtitle_shadow_dx" to -12.0..12.0, "subtitle_shadow_dy" to -12.0..12.0,
            "subtitle_line_spacing" to 0.75..2.0, "subtitle_letter_spacing" to -0.05..0.5,
            "subtitle_background_radius" to 0.0..32.0, "subtitle_padding_horizontal" to 0.0..32.0,
            "subtitle_padding_vertical" to 0.0..24.0,
        )
        val colorKeys = setOf("subtitle_outline_color", "subtitle_shadow_color")
        fun from(config: ConfigSnapshot): SubtitleAppearance {
            fun text(key: String) = checkNotNull(config.string(key, defaults.getValue(key)))
            fun number(key: String) = text(key).toFloat()
            return SubtitleAppearance(number("subtitle_outline_width"), text("subtitle_outline_color"),
                number("subtitle_shadow_radius"), number("subtitle_shadow_dx"), number("subtitle_shadow_dy"),
                text("subtitle_shadow_color"), number("subtitle_line_spacing"), number("subtitle_letter_spacing"),
                number("subtitle_background_radius"), number("subtitle_padding_horizontal"), number("subtitle_padding_vertical"),
                text("subtitle_alignment"), text("subtitle_max_lines").toInt())
        }
    }
}

/** Never display a stale translation when the caller has invalidated its track or cue generation. */
internal object SubtitleCaptionPresentation {
    val orders = listOf("original_first", "translated_first")

    fun display(original: String, translated: String?, mode: String, order: String = "original_first"): String {
        if (mode == "original" || translated.isNullOrBlank()) return original
        if (mode != "bilingual" || original.trim() == translated.trim()) return translated
        // Unknown persisted values keep the original-first layout without interrupting rendering.
        return if (order == "translated_first") translated + "\n" + original else original + "\n" + translated
    }
}
