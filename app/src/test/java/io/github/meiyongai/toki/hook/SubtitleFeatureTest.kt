package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigArchive
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class SubtitleFeatureTest {
    @Test fun largerCustomSettingsAndDynamicStyleSwitchAreSupported() {
        ConfigSchema.validate(mapOf("subtitle_size" to "160", "subtitle_x" to "-1000", "subtitle_y" to "1000", "subtitle_background_mode" to "hide"))
        val startup = io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot(mapOf("subtitle_style_enabled" to true), 1)
        val off = io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot(mapOf("subtitle_style_enabled" to false), 2)
        val plan = HostFeaturePlan(startup)
        assertFalse(plan.requiresRestart("VideoTranslateHook", off))
        assertFalse(plan.apply(off).boolean("subtitle_style_enabled"))
        assertTrue(plan.apply(startup).boolean("subtitle_style_enabled"))
    }
    @Test fun signedTrackIdentifiersAreNotMissingSentinels() {
        val original = -1111210457
        assertEquals(original, CaptionFallbackPolicy.choose(-1, original, false))
        assertEquals(original, CaptionFallbackPolicy.choose(original, 42))
        assertEquals(42, CaptionFallbackPolicy.choose(42, original, true))
        assertEquals(-1, CaptionFallbackPolicy.choose(-1, -1, false))
    }
    @Test fun englishVariantsAreRemovedWithoutChangingOtherLanguagesOrInput() {
        val input = listOf("en", "en-US", "EN_gb", "fr", "zh-Hans", "eng")
        assertArrayEquals(arrayOf("fr", "zh-Hans", "eng"), SubtitlePolicy.includeEnglish(input))
        assertEquals(6, input.size)
        assertArrayEquals(emptyArray<String>(), SubtitlePolicy.includeEnglish(emptyList()))
    }
    @Test fun subtitleSettingsSurviveArchiveRoundTrip() {
        val values = mapOf("subtitle_style_enabled" to true, "subtitle_size" to "48",
            "subtitle_x" to "-100", "subtitle_y" to "100", "subtitle_color" to "#ff00ffff",
            "subtitle_background" to "#00000000", "subtitle_font" to "monospace", "subtitle_weight" to "bold_italic")
        assertEquals(values, ConfigArchive.read(ByteArrayInputStream(ConfigArchive.encode(values))))
    }
    @Test fun invalidSettingsCannotReachRenderer() {
        for ((key, bad) in listOf("subtitle_size" to "NaN", "subtitle_size" to "0",
            "subtitle_size" to "161", "subtitle_x" to "1001", "subtitle_y" to "-1001",
            "subtitle_color" to "#FFF", "subtitle_background" to "black",
            "subtitle_font" to "/sdcard/font.ttf", "subtitle_weight" to "heavy")) {
            assertThrows(IllegalArgumentException::class.java) { ConfigSchema.validate(mapOf(key to bad)) }
        }
    }
}
