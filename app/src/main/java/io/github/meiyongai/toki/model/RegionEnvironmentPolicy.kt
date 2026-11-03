package io.github.meiyongai.toki.subtitle.model

import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot

/** Diagnose missing follow-region data without treating an explicitly configured manual environment as unavailable. */
internal object RegionEnvironmentPolicy {
    private val keys = mapOf(
        "LocaleHook" to ("language_spoof_enabled" to "language_follow_region"),
        "TimeZoneHook" to ("timezone_spoof_enabled" to "timezone_follow_region"),
        "GpsHook" to ("gps_spoof_enabled" to "gps_follow_region"),
    )
    fun owns(feature: String): Boolean = feature in keys
    fun missingPreset(feature: String, snapshot: ConfigSnapshot): Boolean {
        val (enabled, follow) = keys[feature] ?: return false
        return snapshot.boolean(enabled) && snapshot.boolean(follow) &&
            RegionPresets.findByIso(snapshot.string("target_region", "JP").orEmpty()) == null
    }
}
