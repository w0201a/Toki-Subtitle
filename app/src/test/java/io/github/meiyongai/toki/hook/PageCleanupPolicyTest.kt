package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.PageCleanupItem
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class PageCleanupPolicyTest {
    @Test fun allSixDefaultOffAndIndependentOfNavigationAndStartupSensitiveHooks() {
        val keys = PageCleanupItem.entries.map { it.key }
        assertEquals(6, keys.size); assertEquals(keys.size, keys.toSet().size)
        assertEquals(keys, ConfigSchema.featureSwitches.getValue("PageCleanupHook"))
        for (key in keys) {
            assertFalse(ConfigSchema.booleanDefault(key))
            val config = ConfigSnapshot(mapOf(key to true), 1L)
            val plan = HostFeaturePlan(config)
            assertEquals(setOf("PageCleanupHook"), plan.directHostFeatures)
            assertFalse(plan.requiresScan)
            assertFalse(plan.requiresRestart("PageCleanupHook", config))
            val late = HostFeaturePlan(ConfigSnapshot(emptyMap(), 0L))
            assertEquals(listOf("PageCleanupHook"), late.liveCandidates(config))
            assertTrue(HostFeaturePlan.liveSymbols(late.liveCandidates(config)).isEmpty())
            assertTrue(late.admitLive("PageCleanupHook"))
            assertTrue(late.apply(config).boolean(key))
            late.reject("PageCleanupHook"); assertFalse(late.apply(config).boolean(key))
        }
    }
    @Test fun eachComponentNeedsItsOwnInspectedDexSetAndMissingCodeKeepsNativeUi() {
        assertTrue(PageCleanupCodeGate.supported(emptySet()).isEmpty())
        val search = PageCleanupCodeGate.supported(setOf("classes34.dex"))
        assertEquals(setOf(PageCleanupItem.SEARCH_VOICE), search)
        assertEquals(setOf(PageCleanupItem.SEARCH_VOICE, PageCleanupItem.SEARCH_TAKO),
            PageCleanupCodeGate.supported(setOf("classes8.dex", "classes34.dex")))
        val follow = setOf("classes27.dex", "classes10.dex")
        assertEquals(setOf(PageCleanupItem.LIVE_FOLLOW_BOTTOM, PageCleanupItem.LIVE_FOLLOW_PIN), PageCleanupCodeGate.supported(follow))
        assertFalse(PageCleanupItem.LIVE_GIFT_GUIDE in PageCleanupCodeGate.supported(follow))
        assertTrue(PageCleanupCodeGate.supported(setOf("classes27.dex")).isEmpty())
        assertEquals(setOf(PageCleanupItem.INBOX_SHOP_MIGRATION), PageCleanupCodeGate.supported(setOf("classes11.dex")))
    }
}
