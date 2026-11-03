package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.RegionPresets
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import java.util.Locale

/** Resolve ONE effective configuration revision; null keeps the downstream native path. */
internal object EnvironmentRuntimeConfig {
    data class Coordinates(val latitude: Double, val longitude: Double)
    fun carrier(config: ConfigSnapshot): CarrierInfo? {
        if (!config.boolean("sim_spoof_enabled")) return null
        return SimHook.resolveCarrier(config.string("target_region", "JP").orEmpty(),
            config.string("sim_operator_code"), config.string("sim_operator_name"), config.boolean("sim_country_only_enabled"))
    }
    fun language(config: ConfigSnapshot): Locale? {
        if (!config.boolean("language_spoof_enabled")) return null
        val value = if (config.boolean("language_follow_region"))
            RegionPresets.resolveLanguageForIso(config.string("target_region", "JP").orEmpty())
        else config.string("custom_language", "ja-JP")
        return value?.let { Locale.forLanguageTag(it.replace('_', '-')) }?.takeIf { it.language.isNotEmpty() }
    }
    fun timeZone(config: ConfigSnapshot): String? {
        if (!config.boolean("timezone_spoof_enabled")) return null
        return if (config.boolean("timezone_follow_region"))
            RegionPresets.resolveTimeZoneForIso(config.string("target_region", "JP").orEmpty())
        else config.string("custom_timezone", "Asia/Tokyo")
    }
    fun coordinates(config: ConfigSnapshot): Coordinates? {
        if (!config.boolean("gps_spoof_enabled")) return null
        val latitude: Double?
        val longitude: Double?
        if (config.boolean("gps_follow_region")) {
            val code = config.string("target_region", "JP").orEmpty()
            latitude = RegionPresets.resolveLatitudeForIso(code); longitude = RegionPresets.resolveLongitudeForIso(code)
        } else {
            latitude = config.string("custom_latitude", "35.6762")?.toDoubleOrNull()
            longitude = config.string("custom_longitude", "139.6503")?.toDoubleOrNull()
        }
        if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return Coordinates(latitude, longitude)
    }
}

/** Runtime receipt is separate from saved/requested configuration; retain actual applied revision and activation. */
internal object EnvironmentApplicationStatus {
    data class Receipt(val revision: Long, val enabled: Boolean)
    private val receipts = mutableMapOf<String, Receipt>()
    @Synchronized fun record(feature: String, config: ConfigSnapshot, enabled: Boolean) {
        receipts[feature] = Receipt(config.revision, enabled)
    }
    @Synchronized fun get(feature: String): Receipt? = receipts[feature]
    @Synchronized fun clear(feature: String) { receipts.remove(feature) }
}
