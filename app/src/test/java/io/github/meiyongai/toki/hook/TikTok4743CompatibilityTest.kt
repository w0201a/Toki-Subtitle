package io.github.meiyongai.toki.subtitle.hook

import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class TikTok4743CompatibilityTest {
    private val identity = "b3ba776d1e42021c278da5567f0d3666d24efab5ace46bf1a406f6c4e935b796"
    @Test fun targetCoreProfileHasNoUnresolvedFeatureAndMatchesCurrentRules() {
        val rules = javaClass.getResourceAsStream("/toki-host-rules.tsv")!!.bufferedReader().use { it.readText() }
        val profile = checkNotNull(HostBundledProfiles.load(identity, rules))
        assertFalse(profile.stringPropertyNames().any { it.startsWith("error.") })
        assertEquals(HostSymbol.entries.toSet(), HostSymbols.coverage(profile))
    }
    @Test fun fixedTableIsRestrictedToExactCodeAndContainsCriticalTranslationContracts() {
        val profile = Properties().apply { this@TikTok4743CompatibilityTest.javaClass.getResourceAsStream("/toki-4743-members.properties")!!.use(::load) }
        assertEquals(identity, profile.getProperty("host.identity"))
        for (key in listOf("class.X.0B1g", "class.X.0B14", "X.08ZO:method.LIZLLL()Ljava/lang/String;",
            "X.02tQ:method.LJJZ(LX/02u8;LX/02u8;)LX/02tV;", "X.0B14:field.LIZ", "X.0B14:field.LIZIZ")) {
            assertNotNull(key, profile.getProperty(key))
            assertNotEquals(key, "!unresolved", profile.getProperty(key))
        }
        ExtensionSymbols.selectIdentity("0".repeat(64))
        assertEquals(String::class.java, javaClass.classLoader!!.adaptiveClass("java.lang.String"))
        assertEquals("length", String::class.java.adaptiveMethod("length").name)
        ExtensionSymbols.selectIdentity(identity)
        // Missing mappings must fail explicitly, never fall through to old obfuscated names.
        assertThrows(IllegalStateException::class.java) { javaClass.classLoader!!.adaptiveClass("X.08a3") }
        ExtensionSymbols.selectIdentity("0".repeat(64))
    }
}
