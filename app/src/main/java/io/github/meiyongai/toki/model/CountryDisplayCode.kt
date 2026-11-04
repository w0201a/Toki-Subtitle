package io.github.meiyongai.toki.subtitle.model

import java.util.Locale

/** Display aliases only: never a TikTok availability list or a change to SIM/carrier/server region settings. */
internal object CountryDisplayCode {
    private val iso3: Map<String, String> by lazy {
        Locale.getISOCountries().mapNotNull { code ->
            runCatching { Locale.Builder().setRegion(code).build().getISO3Country() }
                .getOrNull()?.takeIf { it.length == 3 }?.let { it.uppercase(Locale.ROOT) to code }
        }.toMap()
    }
    fun resolve(value: String): String {
        val raw = value.trim()
        if (raw.length == 2 && raw.all { it in 'A'..'Z' || it in 'a'..'z' }) {
            val code = raw.uppercase(Locale.ROOT)
            return if (code == "UK") "GB" else code
        }
        if (raw.length == 3 && raw.all { it in 'A'..'Z' || it in 'a'..'z' })
            iso3[raw.uppercase(Locale.ROOT)]?.let { return it }
        return raw
    }
}
