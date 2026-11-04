package io.github.meiyongai.toki.subtitle.model
import io.github.meiyongai.toki.subtitle.hook.EnvironmentRuntimeConfig
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test
class LocationPresetsTest {
 @Test fun losAngelesUsesPacificZoneAndKnownCoordinatesWithoutPretendingToChangeIp() {
  val la=LocationPresets.search("US","洛杉矶").single()
  val values=LocationPresets.selection(la)+mapOf("gps_spoof_enabled" to true,"timezone_spoof_enabled" to true)
  ConfigSchema.validate(values)
  assertEquals("US",values["target_region"])
  assertEquals("America/Los_Angeles",EnvironmentRuntimeConfig.timeZone(ConfigSnapshot(values)))
  val coordinates=EnvironmentRuntimeConfig.coordinates(ConfigSnapshot(values))!!
  assertEquals(34.0522,coordinates.latitude,0.0001);assertEquals(-118.2437,coordinates.longitude,0.0001)
  assertEquals(false,values["gps_follow_region"]);assertEquals(false,values["timezone_follow_region"])
 }
 @Test fun allRepresentativesHaveValidPlatformTimezonesAndCountryCarrierDataWhenAvailable() {
  assertEquals(25,LocationPresets.all.size)
  LocationPresets.all.forEach {
   assertTrue(it.zone in java.util.TimeZone.getAvailableIDs());assertTrue(it.latitude in -90.0..90.0);assertTrue(it.longitude in -180.0..180.0)
   ConfigSchema.validate(LocationPresets.selection(it))
  }
 }
 @Test fun searchIsScopedToSelectedCountryAndMissingCitiesAreNotInvented() {
  assertEquals(1,LocationPresets.search("US","Los Angeles").size)
  assertTrue(LocationPresets.search("JP","Los Angeles").isEmpty())
  assertTrue(LocationPresets.search("AQ","").isEmpty())
 }
}
