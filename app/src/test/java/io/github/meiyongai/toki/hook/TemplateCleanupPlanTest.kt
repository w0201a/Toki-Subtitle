package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.LayoutElement
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class TemplateCleanupPlanTest {
    @Test fun dedicatedSwitchHasOneBusinessOwnerAndDoesNotRequireNavigationScan() {
        val key = LayoutElement.VIDEO_TEMPLATE.key
        assertEquals(listOf(key) + TemplateAnchorPolicy.controls.map { it.key }, ConfigSchema.featureSwitches.getValue("TemplateCleanupHook"))
        assertFalse(key in ConfigSchema.featureSwitches.getValue("LayoutCleanupHook"))
        assertFalse(ConfigSchema.booleanDefault(key))
        val request = ConfigSnapshot(mapOf(key to true))
        val plan = HostFeaturePlan(request)
        assertEquals(setOf("TemplateCleanupHook"), plan.directHostFeatures); assertFalse(plan.requiresScan)
        val off = HostFeaturePlan(ConfigSnapshot(emptyMap()))
        assertEquals(listOf("TemplateCleanupHook"), off.liveCandidates(request))
        assertFalse(off.apply(request).boolean(key)); assertTrue(off.admitLive("TemplateCleanupHook")); assertTrue(off.apply(request).boolean(key))
    }
    @Test fun preparedDisabledObserverDoesNotEnableCleanupButCanApplyALaterToggleWithoutReinstall() {
        val off = ConfigSnapshot(emptyMap()); val plan = HostFeaturePlan(off); val key = LayoutElement.VIDEO_TEMPLATE.key
        assertTrue(plan.admitLive("TemplateCleanupHook")); assertFalse(plan.apply(off).boolean(key))
        val request = ConfigSnapshot(mapOf(key to true), 2)
        assertTrue(plan.liveCandidates(request).isEmpty()); assertTrue(plan.apply(request).boolean(key))
        assertFalse(plan.requiresRestart("TemplateCleanupHook", request))
        plan.reject("TemplateCleanupHook"); assertFalse(plan.apply(request).boolean(key))
    }
}
