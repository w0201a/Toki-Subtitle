package io.github.meiyongai.toki.subtitle.model

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class RegionDisplayNameTest {
    @Test fun chineseNamesCoverPresetsAndIsoFallbackWithoutInventingUnknownValues() {
        assertEquals("美国", RegionPresets.resolveDisplayName(" us "))
        assertEquals("", RegionPresets.resolveDisplayName(" "))
        assertEquals("Unknown", RegionPresets.resolveDisplayName("Unknown"))
        assertEquals("ZZ", RegionPresets.resolveDisplayName("ZZ"))
        val extra = Locale.getISOCountries().firstOrNull { RegionPresets.findByIso(it) == null }
        if (extra != null) assertNotEquals(extra, RegionPresets.resolveDisplayName(extra))
    }

    @Test fun regionLookupDoesNotDependOnTurkishDefaultLocale() {
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("IN", RegionPresets.findByIso("in")?.isoCode)
            assertEquals("印度", RegionPresets.resolveDisplayName("in"))
        } finally { Locale.setDefault(before) }
    }
}
