package io.github.meiyongai.toki.subtitle.model

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CountryDisplayCodeTest {
    @Test fun alphaThreeCodesAndUkAliasUseCanonicalChineseDisplayWithoutChangingEnvironmentSelection() {
        for ((source,expected) in mapOf("usa" to "US", "GBR" to "GB", "jpn" to "JP", "DEU" to "DE", " UK " to "GB"))
            assertEquals(expected, CountryDisplayCode.resolve(source))
        assertEquals("美国", RegionPresets.resolveDisplayName("USA"));assertEquals("英国", RegionPresets.resolveDisplayName("UK"))
        assertEquals("GB", CountryCatalog.custom("gb", "", "", true)["target_region"])
        assertEquals("UK", CountryCatalog.custom("uk", "", "", true)["target_region"])
    }
    @Test fun unknownSubdivisionAndOrdinaryNamesArePreservedNotFabricatedAsCountries() {
        for (source in listOf("ZZZ", "US-CA", "unknown region", "中国")) assertEquals(source, CountryDisplayCode.resolve(source))
        assertEquals("", CountryDisplayCode.resolve("  "))
    }
    @Test fun displayNormalizationDoesNotDependOnTheHostLocale() {
        val original = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals("ID", CountryDisplayCode.resolve("idn"));assertEquals("GB", CountryDisplayCode.resolve("uk")) }
        finally { Locale.setDefault(original) }
    }
}
