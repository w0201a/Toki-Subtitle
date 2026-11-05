package io.github.meiyongai.toki.subtitle.model

import java.io.Reader
import java.text.Normalizer
import java.util.Locale
import java.util.TimeZone

/** GeoNames admin1 codes identify regions; they are deliberately not labeled ISO subdivision codes. */
internal data class AdministrativeRegion(val code: String, val english: String, val chinese: String,
    val sourceId: Long, val city: String, val latitude: Double?, val longitude: Double?, val zone: String,
    val citySourceId: Long?) {
    val country: String get() = code.substringBefore('.')
    val hasLocation: Boolean get() = latitude != null && longitude != null && zone in AdministrativeRegions.zones
    fun name(chineseUi: Boolean): String = chinese.takeIf { chineseUi && it.isNotBlank() } ?: english
    internal val searchKey = AdministrativeRegions.normalize(listOf(code, english, chinese, city).joinToString(" "))
}

internal object AdministrativeRegions {
    val zones: Set<String> by lazy { TimeZone.getAvailableIDs().toSet() }
    internal fun normalize(text: String): String = Normalizer.normalize(text.trim(), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    fun read(reader: Reader): List<AdministrativeRegion> {
        val rows = reader.buffered().useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith('#') }.take(6001).map { line ->
            require(line.length <= 2048)
            val f = line.split('\t'); require(f.size == 9)
            require(f[0].matches(Regex("[A-Z]{2}\\.[A-Za-z0-9_-]{1,40}")) && f[1].isNotBlank())
            require(f.all { value -> value.none(Char::isISOControl) })
            val complete = f.drop(4).all(String::isNotBlank)
            require(complete || f.drop(4).all(String::isEmpty))
            val lat = if (complete) f[5].toDouble().also { require(it.isFinite() && it in -90.0..90.0) } else null
            val lon = if (complete) f[6].toDouble().also { require(it.isFinite() && it in -180.0..180.0) } else null
            AdministrativeRegion(f[0], f[1], f[2], f[3].toLong().also { require(it > 0) }, f[4], lat, lon,
                f[7], if (complete) f[8].toLong().also { require(it > 0) } else null)
        }.toList() }
        require(rows.size <= 6000 && rows.map { it.code }.toSet().size == rows.size)
        return rows
    }
    fun search(rows: List<AdministrativeRegion>, country: String, query: String): List<AdministrativeRegion> {
        val code = country.trim().uppercase(Locale.ROOT); val key = normalize(query)
        return rows.filter { it.country == code && (key.isEmpty() || key in it.searchKey) }
    }
    /** One existing-schema transaction; choosing a place does not turn on GPS, time-zone or SIM spoofing. */
    fun selection(item: AdministrativeRegion): Map<String, Any> {
        require(item.hasLocation)
        val option = CountryCatalog.countries.singleOrNull { it.isoCode == item.country }
        val country = option?.let(CountryCatalog::selection) ?: CountryCatalog.custom(item.country, "", "", true)
        return country + mapOf("custom_latitude" to item.latitude.toString(), "custom_longitude" to item.longitude.toString(),
            "custom_timezone" to item.zone, "gps_follow_region" to false, "timezone_follow_region" to false)
    }
}
