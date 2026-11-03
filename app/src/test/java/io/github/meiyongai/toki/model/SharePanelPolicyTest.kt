package io.github.meiyongai.toki.subtitle.model

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class SharePanelPolicyTest {
    @Test fun everyOptionIsIndependentValidatedAndOffByDefault() {
        val none = ConfigSnapshot(emptyMap())
        assertTrue(SharePanelPolicy.hiddenKeys(none).isEmpty())
        assertEquals(SharePanelItem.entries.size, SharePanelItem.entries.map { it.key }.toSet().size)
        for (option in SharePanelItem.entries) {
            assertFalse(none.boolean(option.key))
            val config = mapOf(option.key to true)
            ConfigSchema.validate(config)
            assertEquals(option.hostKeys, SharePanelPolicy.hiddenKeys(ConfigSnapshot(config)))
        }
    }

    @Test fun filteringPreservesOrderIdentityUnknownItemsAndNativeList() {
        data class Item(val key: String?)
        val copy = Item("copy")
        val report = Item("report")
        val future = Item("future_action")
        val unreadable = Item(null)
        val native = listOf(copy, report, future, unreadable)
        val hidden = SharePanelPolicy.hiddenKeys(ConfigSnapshot(mapOf(SharePanelItem.REPORT.key to true)))
        val result = SharePanelPolicy.filter(native, hidden) { it.key }
        assertEquals(listOf(copy, future, unreadable), result)
        assertSame(copy, result[0])
        assertEquals(4, native.size)
        assertSame(native, SharePanelPolicy.filter(native, emptySet()) { it.key })
        assertSame(native, SharePanelPolicy.filter(native, setOf("not_present")) { it.key })
    }

    @Test fun advancedKeysRequireExplicitOptInAndAreNotWildcardLabelMatches() {
        val raw = "wallpaper_action\ncustom_action, effect info action"
        assertEquals(setOf("wallpaper_action", "custom_action", "effect info action"), SharePanelPolicy.customKeys(raw))
        assertTrue(SharePanelPolicy.hiddenKeys(ConfigSnapshot(mapOf(SharePanelPolicy.CUSTOM_KEYS to raw))).isEmpty())
        val enabled = mapOf(SharePanelPolicy.CUSTOM_KEYS to raw, SharePanelPolicy.CUSTOM_ENABLED to true)
        ConfigSchema.validate(enabled)
        assertEquals(3, SharePanelPolicy.hiddenKeys(ConfigSnapshot(enabled)).size)
        for (invalid in listOf("*", "copy|report", "x".repeat(81), "copy\u0001report", (1..65).joinToString(",") { "key_$it" })) {
            assertThrows(IllegalArgumentException::class.java) { SharePanelPolicy.customKeys(invalid) }
        }
        assertTrue(SharePanelPolicy.customKeys(" ").isEmpty())
        assertEquals(setOf("copy"), SharePanelPolicy.customKeys("copy,copy"))
    }
}
