package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class EnvironmentLivePlanTest {
    @Test fun allPlatformControlsAreEligibleWithoutHostSymbolsButMaskedBeforeInstallationAdmission() {
        for (feature in HostFeaturePlan.platform) {
            val key = ConfigSchema.featureSwitches.getValue(feature).single()
            val request = ConfigSnapshot(mapOf(key to true), 2)
            val plan = HostFeaturePlan(ConfigSnapshot(emptyMap(), 1))
            assertEquals(listOf(feature), plan.liveCandidates(request))
            assertTrue(HostFeaturePlan.liveSymbols(listOf(feature)).isEmpty())
            assertFalse(plan.apply(request).boolean(key))
            assertFalse(plan.requiresRestart(feature, request))
            assertTrue(plan.admitLive(feature)); assertTrue(plan.apply(request).boolean(key))
            assertEquals(setOf(feature), plan.platformFeatures); assertTrue(plan.symbols.isEmpty())
            assertTrue(plan.liveCandidates(request).isEmpty())
        }
    }
    @Test fun installedPayloadKeysStayCurrentAcrossDisableChangeImportAndReenable() {
        val switches = HostFeaturePlan.platform.associate { ConfigSchema.featureSwitches.getValue(it).single() to true }
        val first = ConfigSnapshot(switches + mapOf("target_region" to "JP", "sim_country_only_enabled" to false), 1)
        val plan = HostFeaturePlan(first)
        val changed = ConfigSnapshot(switches + mapOf("target_region" to "US", "sim_country_only_enabled" to true,
            "custom_language" to "fr-FR", "custom_timezone" to "Europe/Paris", "custom_latitude" to "48.85", "custom_longitude" to "2.35"), 3, 3)
        val effective = plan.apply(changed).configuration()
        changed.configuration().forEach { (key, value) -> assertEquals(key, value, effective[key]) }
        HostFeaturePlan.platform.forEach { assertFalse(plan.requiresRestart(it, changed)); assertEquals(3L, plan.appliedRevision(it, changed)) }
        val off = ConfigSnapshot(switches.mapValues { false }, 4)
        switches.keys.forEach { assertFalse(plan.apply(off).boolean(it)) }
        assertTrue(plan.liveCandidates(changed).isEmpty())
    }
    @Test fun aRejectedPlatformFeatureDoesNotEnableItselfOrDisableHealthySiblings() {
        val all = ConfigSnapshot(HostFeaturePlan.platform.associate { ConfigSchema.featureSwitches.getValue(it).single() to true }, 1)
        val plan = HostFeaturePlan(ConfigSnapshot(emptyMap()))
        plan.reject("SimHook")
        assertFalse("SimHook" in plan.liveCandidates(all)); assertFalse(plan.admitLive("SimHook"))
        assertTrue(plan.admitLive("LocaleHook")); assertTrue(plan.apply(all).boolean("language_spoof_enabled"))
        assertFalse(plan.apply(all).boolean("sim_spoof_enabled"))
    }
}
