package io.github.meiyongai.toki.subtitle.hook

import android.location.Location
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises actual payload application helpers, not libxposed interception or host cache invalidation. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class EnvironmentOverridesTest {
    private val off = ConfigSnapshot(emptyMap())
    @Test fun carrierAndLanguageEnableChangeDisableAndReenableWithoutGlobalLocaleMutation() {
        val native = Locale.getDefault()
        try {
            val first = ConfigSnapshot(mapOf("sim_spoof_enabled" to true, "language_spoof_enabled" to true, "target_region" to "JP"))
            SimHook.applyConfiguration(first); LocaleHook.applyConfiguration(first)
            assertEquals("jp", SimHook.getActiveCarrier()?.countryIso); assertTrue(LocaleHook.isFeatureEnabled())
            SimHook.applyConfiguration(ConfigSnapshot(first.configuration() + ("target_region" to "US")))
            assertEquals("us", SimHook.getActiveCarrier()?.countryIso)
            SimHook.applyConfiguration(off); LocaleHook.applyConfiguration(off)
            assertNull(SimHook.getActiveCarrier()); assertFalse(LocaleHook.isFeatureEnabled())
            assertEquals(native, Locale.getDefault())
            SimHook.applyConfiguration(first); assertEquals("jp", SimHook.getActiveCarrier()?.countryIso)
        } finally { SimHook.applyConfiguration(off); LocaleHook.applyConfiguration(off) }
    }
    @Test fun timezoneUsesIndependentReturnCopiesAndNeverChangesTheProcessDefault() {
        val native = TimeZone.getDefault()
        try {
            val jp = ConfigSnapshot(mapOf("timezone_spoof_enabled" to true, "target_region" to "JP"))
            TimeZoneHook.applyConfiguration(jp)
            val first = TimeZoneHook.javaOverride()!!; first.rawOffset = 0
            assertEquals(9 * 60 * 60 * 1000, TimeZoneHook.javaOverride()!!.rawOffset)
            val icu = TimeZoneHook.icuOverride()!!; icu.rawOffset = 0
            assertEquals(9 * 60 * 60 * 1000, TimeZoneHook.icuOverride()!!.rawOffset)
            assertEquals("Asia/Tokyo", TimeZoneHook.zoneOverride()?.id)
            TimeZoneHook.applyConfiguration(ConfigSnapshot(jp.configuration() + mapOf("timezone_follow_region" to false, "custom_timezone" to "Europe/Paris")))
            assertEquals("Europe/Paris", TimeZoneHook.javaOverride()?.id)
            assertEquals(native, TimeZone.getDefault())
            val alias = ConfigSnapshot(jp.configuration() + mapOf("timezone_follow_region" to false, "custom_timezone" to "EST"))
            TimeZoneHook.applyConfiguration(alias)
            assertEquals(TimeZone.getTimeZone("EST").toZoneId(), TimeZoneHook.zoneOverride())
            TimeZoneHook.applyConfiguration(off)
            assertNull(TimeZoneHook.javaOverride()); assertNull(TimeZoneHook.zoneOverride()); assertNull(TimeZoneHook.icuOverride())
        } finally { TimeZoneHook.applyConfiguration(off) }
    }
    @Test fun gpsCopiesLastKnownObjectsAndDisabledCallsReturnTheUntouchedOriginal() {
        val native = Location("gps").apply { latitude = 1.0; longitude = 2.0; accuracy = 3f }
        try {
            val jp = ConfigSnapshot(mapOf("gps_spoof_enabled" to true, "target_region" to "JP"))
            GpsHook.applyConfiguration(jp)
            val copy = GpsHook.overrideLastKnown(native, "gps")!!
            assertNotSame(native, copy); assertEquals(35.6762, copy.latitude, 0.0001)
            assertEquals(1.0, native.latitude, 0.0); assertEquals(2.0, native.longitude, 0.0)
            val us = ConfigSnapshot(jp.configuration() + ("target_region" to "US"))
            GpsHook.applyConfiguration(us)
            assertNotEquals(copy.latitude, GpsHook.overrideLastKnown(native, "gps")!!.latitude, 0.0001)
            GpsHook.applyConfiguration(off)
            assertSame(native, GpsHook.overrideLastKnown(native, "gps")); assertNull(GpsHook.overrideLastKnown(null, "gps"))
            assertFalse(GpsHook.isFeatureEnabled())
        } finally { GpsHook.applyConfiguration(off) }
    }
    @Test fun actualReceiptsReportAppliedRevisionAndDisabledOrMissingPayloadsSeparatelyFromRequests() {
        try {
            val value = ConfigSnapshot(mapOf("timezone_spoof_enabled" to true, "target_region" to "JP"), 14)
            TimeZoneHook.applyConfiguration(value)
            assertEquals(EnvironmentApplicationStatus.Receipt(14, true), EnvironmentApplicationStatus.get("TimeZoneHook"))
            TimeZoneHook.applyConfiguration(ConfigSnapshot(mapOf("timezone_spoof_enabled" to true, "target_region" to "ZZ"), 15))
            assertEquals(EnvironmentApplicationStatus.Receipt(15, false), EnvironmentApplicationStatus.get("TimeZoneHook"))
            EnvironmentApplicationStatus.clear("TimeZoneHook")
            assertNull(EnvironmentApplicationStatus.get("TimeZoneHook"))
        } finally { TimeZoneHook.applyConfiguration(off) }
    }
    @Test fun missingFollowMetadataRemovesPreviouslyActiveOverridesRatherThanKeepingStaleJapan() {
        val jp = ConfigSnapshot(mapOf("language_spoof_enabled" to true, "timezone_spoof_enabled" to true, "gps_spoof_enabled" to true, "target_region" to "JP"))
        try {
            LocaleHook.applyConfiguration(jp); TimeZoneHook.applyConfiguration(jp); GpsHook.applyConfiguration(jp)
            val missing = ConfigSnapshot(jp.configuration() + ("target_region" to "ZZ"))
            LocaleHook.applyConfiguration(missing); TimeZoneHook.applyConfiguration(missing); GpsHook.applyConfiguration(missing)
            assertFalse(LocaleHook.isFeatureEnabled()); assertFalse(TimeZoneHook.isFeatureEnabled()); assertFalse(GpsHook.isFeatureEnabled())
        } finally { LocaleHook.applyConfiguration(off); TimeZoneHook.applyConfiguration(off); GpsHook.applyConfiguration(off) }
    }
}
