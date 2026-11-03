package io.github.meiyongai.toki.subtitle.model

import java.text.Normalizer
import java.util.Locale

/** A country directory is not a carrier database or a TikTok server/account availability whitelist. */
data class CountryOption(val isoCode: String, val englishName: String, val chineseName: String, val preset: RegionPreset?) {
    val flag: String get() = RegionPresets.isoToFlagEmoji(isoCode)
    fun displayName(locale: Locale): String {
        if (locale.language == "zh") return chineseName
        return Locale.Builder().setRegion(isoCode).build().getDisplayCountry(locale)
            .takeUnless { it.isBlank() || it == isoCode } ?: englishName
    }
}

object CountryCatalog {
    val countries: List<CountryOption> by lazy {
        (Locale.getISOCountries().toSet() + RegionPresets.ALL_PRESETS.map { it.isoCode }).map { code ->
            val country = Locale.Builder().setRegion(code).build()
            val preset = RegionPresets.findByIso(code)
            CountryOption(code, preset?.englishName ?: country.getDisplayCountry(Locale.ENGLISH),
                RegionPresets.resolveDisplayName(code), preset)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.englishName })
    }
    private fun normalized(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    fun search(query: String, locale: Locale): List<CountryOption> {
        val key = normalized(query)
        if (key.isEmpty()) return countries
        return countries.filter { item -> listOf(item.isoCode, item.englishName, item.chineseName, item.displayName(locale))
            .any { normalized(it).contains(key) } }
    }
    /** Complete preset selection and country-only selection are one transaction, never three partial writes. */
    fun selection(item: CountryOption): Map<String, Any> = buildMap {
        put("target_region", item.isoCode)
        put("sim_country_only_enabled", item.preset == null)
        item.preset?.let { put("sim_operator_code", it.operatorCode); put("sim_operator_name", it.operatorName) }
    }
    fun custom(iso: String, operator: String, name: String, countryOnly: Boolean): Map<String, Any> = buildMap {
        val code = iso.trim().uppercase(Locale.ROOT)
        require(code.matches(Regex("[A-Z]{2}"))) { "Country code must contain two letters" }
        put("target_region", code); put("sim_country_only_enabled", countryOnly)
        if (!countryOnly) {
            require(operator.trim().matches(Regex("[0-9]{5,6}")) && name.trim().isNotBlank())
            put("sim_operator_code", operator.trim()); put("sim_operator_name", name.trim())
        }
    }
}
