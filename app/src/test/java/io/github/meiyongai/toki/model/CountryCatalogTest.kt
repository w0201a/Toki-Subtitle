package io.github.meiyongai.toki.subtitle.model

import io.github.meiyongai.toki.subtitle.hook.HostFeaturePlan
import io.github.meiyongai.toki.subtitle.hook.SimHook
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CountryCatalogTest {
    @Test fun missingMetadataDiagnosticsRespectManualModeAndDisabledFeatures() {
        val extra = CountryCatalog.countries.first { it.preset == null }
        val config = ConfigSnapshot(mapOf("gps_spoof_enabled" to true, "target_region" to extra.isoCode))
        assertTrue(RegionEnvironmentPolicy.missingPreset("GpsHook", config))
        assertFalse(RegionEnvironmentPolicy.missingPreset("SimHook", config))
        assertFalse(RegionEnvironmentPolicy.missingPreset("GpsHook", ConfigSnapshot(config.configuration() + ("gps_follow_region" to false))))
        assertFalse(RegionEnvironmentPolicy.missingPreset("GpsHook", ConfigSnapshot(config.configuration() + ("gps_spoof_enabled" to false))))
        assertFalse(RegionEnvironmentPolicy.missingPreset("GpsHook", ConfigSnapshot(config.configuration() + ("target_region" to "JP"))))
    }

    @Test fun directoryIncludesAllPlatformIsoCountriesAndExistingPresetsWithoutInventingCarriers() {
        val countries = CountryCatalog.countries
        val codes = countries.map { it.isoCode }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.containsAll(Locale.getISOCountries().toList()))
        assertTrue(codes.containsAll(RegionPresets.ALL_PRESETS.map { it.isoCode }))
        assertTrue(countries.size > RegionPresets.ALL_PRESETS.size)
        countries.forEach { assertTrue(it.isoCode.matches(Regex("[A-Z]{2}"))); assertTrue(it.chineseName.isNotBlank()) }
    }
    @Test fun queryMatchesChineseEnglishCodeLocalizedNamesAndAccents() {
        assertTrue(CountryCatalog.search("美国", Locale.ENGLISH).any { it.isoCode == "US" })
        assertTrue(CountryCatalog.search("cote", Locale.ENGLISH).any { it.isoCode == "CI" })
        assertTrue(CountryCatalog.search("  jp ", Locale.SIMPLIFIED_CHINESE).any { it.isoCode == "JP" })
        assertEquals(CountryCatalog.countries, CountryCatalog.search(" ", Locale.ENGLISH))
        assertTrue(CountryCatalog.search("no-such-country-123456", Locale.ENGLISH).isEmpty())
    }
    @Test fun knownSelectionIsAtomicAndUnknownPresetPreservesStoredManualCarrierData() {
        val jp = CountryCatalog.countries.single { it.isoCode == "JP" }
        val known = CountryCatalog.selection(jp)
        assertEquals("JP", known["target_region"]); assertEquals(false, known["sim_country_only_enabled"])
        assertEquals("44010", known["sim_operator_code"])
        ConfigSchema.validate(known)
        val extra = CountryCatalog.countries.first { it.preset == null }
        val selected = CountryCatalog.selection(extra)
        assertEquals(setOf("target_region", "sim_country_only_enabled"), selected.keys)
        assertEquals(true, selected["sim_country_only_enabled"])
        ConfigSchema.validate(selected)
        val carrier = SimHook.resolveCarrier(extra.isoCode, "44010", "old manual carrier", countryOnly = true)
        assertEquals(extra.isoCode.lowercase(Locale.ROOT), carrier.countryIso)
        assertEquals("", carrier.operatorCode); assertEquals("", carrier.operatorName)
        val noData = SimHook.resolveCarrier(extra.isoCode, null, null)
        assertEquals("", noData.operatorCode); assertEquals("", noData.operatorName)
    }
    @Test fun missingEnvironmentMetadataReturnsAbsenceInsteadOfUsUtcOrTokyo() {
        val extra = CountryCatalog.countries.first { it.preset == null }
        assertNull(RegionPresets.resolveLanguageForIso(extra.isoCode))
        assertNull(RegionPresets.resolveTimeZoneForIso(extra.isoCode))
        assertNull(RegionPresets.resolveLatitudeForIso(extra.isoCode))
        assertNull(RegionPresets.resolveLongitudeForIso(extra.isoCode))
        assertEquals("ja-JP", RegionPresets.resolveLanguageForIso("jp"))
        assertEquals("Asia/Tokyo", RegionPresets.resolveTimeZoneForIso("JP"))
    }
    @Test fun countryOnlyDoesNotInstallSimAloneAndRemainsStartupSensitiveWithItsOwner() {
        assertFalse(ConfigSchema.booleanDefault("sim_country_only_enabled"))
        assertTrue(HostFeaturePlan(ConfigSnapshot(mapOf("sim_country_only_enabled" to true))).features.isEmpty())
        val initial = ConfigSnapshot(mapOf("sim_spoof_enabled" to true, "target_region" to "JP"))
        val plan = HostFeaturePlan(initial)
        val changed = ConfigSnapshot(initial.configuration() + mapOf("sim_country_only_enabled" to true))
        assertTrue(plan.requiresRestart("SimHook", changed))
        assertFalse(plan.apply(changed).boolean("sim_country_only_enabled"))
    }
    @Test fun customValidationDoesNotReplaceBlankDataWithInventedDefaultsOrUseTurkishCaseRules() {
        assertThrows(IllegalArgumentException::class.java) { CountryCatalog.custom("", "", "", false) }
        assertThrows(IllegalArgumentException::class.java) { CountryCatalog.custom("US-CA", "310410", "AT&T", false) }
        assertThrows(IllegalArgumentException::class.java) { CountryCatalog.custom("US", "", "", false) }
        assertEquals(mapOf("target_region" to "US", "sim_country_only_enabled" to true), CountryCatalog.custom("us", "", "", true))
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("IN", CountryCatalog.custom("in", "", "", true)["target_region"])
            assertEquals("in", SimHook.resolveCarrier("IN", null, null, true).countryIso)
        } finally { Locale.setDefault(before) }
    }
}
