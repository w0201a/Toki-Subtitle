package io.github.meiyongai.toki.subtitle.model

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import io.github.meiyongai.toki.subtitle.hook.EnvironmentRuntimeConfig
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class AdministrativeRegionsTest {
    private fun rows(): List<AdministrativeRegion> {
        val file = listOf(File("src/main/assets/geography/admin-regions.tsv"), File("app/src/main/assets/geography/admin-regions.tsv")).first { it.isFile }
        return AdministrativeRegions.read(file.reader(Charsets.UTF_8))
    }
    @Test fun bundledDirectoryHasStableUniqueRegionsAndSubstantialCountryCoverage() {
        val all = rows()
        assertEquals(3865, all.size); assertEquals(all.size, all.map { it.code }.toSet().size)
        assertEquals(228, all.map { it.country }.toSet().size)
        for ((country, expected) in mapOf("US" to 51, "CA" to 13, "JP" to 47, "AU" to 8, "DE" to 16, "BR" to 27))
            assertEquals(country, expected, AdministrativeRegions.search(all, country, "").size)
        assertEquals(83, all.count { it.latitude == null })
        assertTrue(all.count { it.hasLocation } > 3700)
    }
    @Test fun queryMatchesChineseEnglishCityCodeAndRemainsCountryScoped() {
        val all = rows()
        assertEquals("US.CA", AdministrativeRegions.search(all, "us", "加利福尼亚").single().code)
        assertEquals("US.CA", AdministrativeRegions.search(all, "US", "Sacramento").single().code)
        assertEquals("US.CA", AdministrativeRegions.search(all, "US", "US.CA").single().code)
        assertTrue(AdministrativeRegions.search(all, "JP", "Sacramento").isEmpty())
        assertTrue(AdministrativeRegions.search(all, "AQ", "").isEmpty())
        assertEquals(AdministrativeRegions.search(all, "US", "California"), AdministrativeRegions.search(all, "US", "  CALIFORNIA "))
    }
    @Test fun representativeLocationAndTimeZoneApplyAtomicallyWithoutSwitchingFeaturesOn() {
        val ca = rows().single { it.code == "US.CA" }
        val selected = AdministrativeRegions.selection(ca)
        ConfigSchema.validate(selected)
        assertFalse(selected.keys.any { it in setOf("gps_spoof_enabled", "timezone_spoof_enabled", "sim_spoof_enabled") })
        assertEquals("US", selected["target_region"])
        assertEquals(false, selected["gps_follow_region"]); assertEquals(false, selected["timezone_follow_region"])
        val snapshot = ConfigSnapshot(selected + mapOf("gps_spoof_enabled" to true, "timezone_spoof_enabled" to true))
        assertEquals("America/Los_Angeles", EnvironmentRuntimeConfig.timeZone(snapshot))
        assertEquals(38.58157, EnvironmentRuntimeConfig.coordinates(snapshot)!!.latitude, 0.00001)
        assertEquals(-121.4944, EnvironmentRuntimeConfig.coordinates(snapshot)!!.longitude, 0.00001)
    }
    @Test fun everySelectablePointHasValidConfigurationAndMissingPointsAreNotInvented() {
        rows().forEach { item ->
            if (item.hasLocation) ConfigSchema.validate(AdministrativeRegions.selection(item))
            else assertThrows(IllegalArgumentException::class.java) { AdministrativeRegions.selection(item) }
        }
    }
    @Test fun malformedOrDuplicateRowsAndPartialCoordinatesCannotEnterTheDirectory() {
        val good = "US.CA\tCalifornia\t加利福尼亚州\t5332921\tSacramento\t38.58157\t-121.4944\tAmerica/Los_Angeles\t5389489"
        for (bad in listOf(good + "\n" + good, good.replace("38.58157", "NaN"), good.replace("38.58157", "91"),
                good.replace("-121.4944", ""), good.replace("US.CA", "../CA"), good.replace("5332921", "-1"), "too\tfew")) {
            assertThrows(IllegalArgumentException::class.java) { AdministrativeRegions.read(bad.reader()) }
        }
        val unknownZone = AdministrativeRegions.read(good.replace("America/Los_Angeles", "Invalid/Zone").reader()).single()
        assertFalse(unknownZone.hasLocation)
        assertThrows(IllegalArgumentException::class.java) { AdministrativeRegions.selection(unknownZone) }
    }
    @Test fun unknownChineseNameKeepsPublishedNameAndDoesNotGuessTranslation() {
        val region = AdministrativeRegions.read("US.AB\tPublished name\t\t1\t\t\t\t\t".reader()).single()
        assertEquals("Published name", region.name(true)); assertFalse(region.hasLocation)
    }
}
