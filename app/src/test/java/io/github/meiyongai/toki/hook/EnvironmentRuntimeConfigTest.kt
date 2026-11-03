package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class EnvironmentRuntimeConfigTest {
    @Test fun disabledFeaturesReturnAbsenceRatherThanDefaultJapanPayloads() {
        val config = ConfigSnapshot(emptyMap())
        assertNull(EnvironmentRuntimeConfig.carrier(config)); assertNull(EnvironmentRuntimeConfig.language(config))
        assertNull(EnvironmentRuntimeConfig.timeZone(config)); assertNull(EnvironmentRuntimeConfig.coordinates(config))
    }
    @Test fun theSameSnapshotResolvesOneCarrierOrCoordinatePairAndDoesNotMutateEarlierResults() {
        val one = ConfigSnapshot(mapOf("sim_spoof_enabled" to true, "gps_spoof_enabled" to true, "target_region" to "JP"))
        val first = EnvironmentRuntimeConfig.carrier(one)!!; val position = EnvironmentRuntimeConfig.coordinates(one)!!
        val two = ConfigSnapshot(one.configuration() + ("target_region" to "US"))
        assertEquals("jp", first.countryIso); assertEquals("us", EnvironmentRuntimeConfig.carrier(two)?.countryIso)
        assertEquals(35.6762, position.latitude, 0.0001); assertEquals(139.6503, position.longitude, 0.0001)
        assertEquals(position, EnvironmentRuntimeConfig.coordinates(one))
        assertNotEquals(position, EnvironmentRuntimeConfig.coordinates(two))
    }
    @Test fun countryOnlyPreservesNativeOperatorIntentEvenWhenOldManualFieldsRemainStored() {
        val config = ConfigSnapshot(mapOf("sim_spoof_enabled" to true, "target_region" to "US", "sim_country_only_enabled" to true,
            "sim_operator_code" to "44010", "sim_operator_name" to "old manual value"))
        assertEquals(CarrierInfo("us", "", ""), EnvironmentRuntimeConfig.carrier(config))
    }
    @Test fun missingRegionPresetClearsFollowOverridesWhileExplicitManualValuesStillResolve() {
        val all = mapOf("language_spoof_enabled" to true, "timezone_spoof_enabled" to true, "gps_spoof_enabled" to true, "target_region" to "ZZ")
        assertNull(EnvironmentRuntimeConfig.language(ConfigSnapshot(all)))
        assertNull(EnvironmentRuntimeConfig.timeZone(ConfigSnapshot(all)))
        assertNull(EnvironmentRuntimeConfig.coordinates(ConfigSnapshot(all)))
        val manual = ConfigSnapshot(all + mapOf("language_follow_region" to false, "timezone_follow_region" to false, "gps_follow_region" to false,
            "custom_language" to "fr-FR", "custom_timezone" to "Europe/Paris", "custom_latitude" to "48.85", "custom_longitude" to "2.35"))
        assertEquals("fr-FR", EnvironmentRuntimeConfig.language(manual)?.toLanguageTag())
        assertEquals("Europe/Paris", EnvironmentRuntimeConfig.timeZone(manual))
        assertEquals(EnvironmentRuntimeConfig.Coordinates(48.85, 2.35), EnvironmentRuntimeConfig.coordinates(manual))
    }
    @Test fun incompleteOrInvalidCoordinatePairCannotExposeOneNewAndOneOldCoordinate() {
        val base = mapOf("gps_spoof_enabled" to true, "gps_follow_region" to false, "custom_latitude" to "12", "custom_longitude" to "34")
        for (bad in listOf("NaN", "Infinity", "181", "not a number")) {
            assertNull(EnvironmentRuntimeConfig.coordinates(ConfigSnapshot(base + ("custom_longitude" to bad))))
        }
        assertNull(EnvironmentRuntimeConfig.coordinates(ConfigSnapshot(base + ("custom_latitude" to "91"))))
    }
}
