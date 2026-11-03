package io.github.meiyongai.toki.subtitle.model

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class SubtitleAppearanceTest {
    @Test fun absentKeysPreserveDefaultStyleWithoutClippingCaptions() {
        ConfigSchema.validate(SubtitleAppearance.defaults)
        val style = SubtitleAppearance.from(ConfigSnapshot(emptyMap()))
        assertEquals(0f, style.outlineWidth, 0f)
        assertEquals(0f, style.shadowRadius, 0f)
        assertEquals(1f, style.lineSpacing, 0f)
        assertEquals(0, style.maxLines)
        assertEquals("center", style.alignment)
    }

    @Test fun numericSettingsAcceptBoundariesAndRejectInvalidValues() {
        for ((key, range) in SubtitleAppearance.ranges) {
            ConfigSchema.validate(mapOf(key to range.start.toString()))
            ConfigSchema.validate(mapOf(key to range.endInclusive.toString()))
            for (value in listOf("NaN", "Infinity", (range.start - 1).toString(), (range.endInclusive + 1).toString())) {
                assertThrows(IllegalArgumentException::class.java) { ConfigSchema.validate(mapOf(key to value)) }
            }
        }
        for (value in listOf("-1", "13", "1.5", "NaN")) {
            assertThrows(IllegalArgumentException::class.java) { ConfigSchema.validate(mapOf("subtitle_max_lines" to value)) }
        }
    }

    @Test fun styleEnumsAndColorsHaveStrictImportValidation() {
        for (key in listOf("subtitle_alignment", "subtitle_text_mode")) {
            assertThrows(IllegalArgumentException::class.java) { ConfigSchema.validate(mapOf(key to "unsupported")) }
        }
        for (key in SubtitleAppearance.colorKeys) {
            ConfigSchema.validate(mapOf(key to "#80ABCDEF"))
            assertThrows(IllegalArgumentException::class.java) { ConfigSchema.validate(mapOf(key to "black")) }
        }
    }

    @Test fun bilingualPresentationFallsBackWithoutDuplicatingOrMixingCues() {
        assertEquals("原文", SubtitleCaptionPresentation.display("原文", null, "bilingual"))
        assertEquals("原文", SubtitleCaptionPresentation.display("原文", "译文", "original"))
        assertEquals("译文", SubtitleCaptionPresentation.display("原文", "译文", "translated"))
        assertEquals("原文\n译文", SubtitleCaptionPresentation.display("原文", "译文", "bilingual"))
        assertEquals("same", SubtitleCaptionPresentation.display("same", "same", "bilingual"))
        assertEquals("原文", SubtitleCaptionPresentation.display("原文", " ", "translated"))
    }
}
