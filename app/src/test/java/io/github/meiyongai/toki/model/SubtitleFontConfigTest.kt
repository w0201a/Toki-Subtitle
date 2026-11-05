package io.github.meiyongai.toki.subtitle.model

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.HostSettingsPatch
import io.github.meiyongai.toki.subtitle.provider.HostSettingsWire
import org.junit.Assert.*
import org.junit.Test

class SubtitleFontConfigTest {
    @Test fun fontAndOrderKeysRoundTripThroughTheExistingHostMirror() {
        val values = mapOf("subtitle_font_asset" to "a".repeat(64), "subtitle_font_asset_name" to "My.ttf", "subtitle_bilingual_order" to "translated_first")
        ConfigSchema.validate(values)
        val patch = HostSettingsPatch("com.zhiliaoapp.musically", "font-order", values)
        val decoded = HostSettingsWire.decode(HostSettingsWire.encode(patch))
        assertEquals(values, decoded.values)
        ConfigSchema.validate(mapOf("subtitle_font" to "sans-serif", "subtitle_font_asset" to "", "subtitle_font_asset_name" to ""))
    }
    @Test fun unknownOrderAndUnsafeFontAssetIdentifiersAreRejected() {
        for (order in SubtitleCaptionPresentation.orders) ConfigSchema.validate(mapOf("subtitle_bilingual_order" to order))
        assertThrows(IllegalArgumentException::class.java) { ConfigSchema.validate(mapOf("subtitle_bilingual_order" to "random")) }
        for (asset in listOf("../file.ttf", "A".repeat(64), "a".repeat(63)))
            assertThrows(IllegalArgumentException::class.java) { ConfigSchema.validate(mapOf("subtitle_font_asset" to asset)) }
        assertThrows(IllegalArgumentException::class.java) { ConfigSchema.validate(mapOf("subtitle_font_asset_name" to "x\ny")) }
        assertThrows(IllegalArgumentException::class.java) { ConfigSchema.validate(mapOf("subtitle_font_asset_name" to "x".repeat(121))) }
    }
}
