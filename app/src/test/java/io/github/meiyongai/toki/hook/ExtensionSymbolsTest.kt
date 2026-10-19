package io.github.meiyongai.toki.subtitle.hook

import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class ExtensionSymbolsTest {
    @Test fun cacheRequiresExactCodeProfileAndFormat() {
        fun cache() = Properties().apply {
            setProperty("cache.identity", "host")
            setProperty("cache.profile", "profile")
            setProperty("cache.format", ExtensionDexIndex.FORMAT)
            setProperty("cache.complete", "true")
        }
        assertTrue(ExtensionSymbols.cacheMatches(cache(), "host", "profile"))
        assertFalse(ExtensionSymbols.cacheMatches(cache(), "new-host", "profile"))
        assertFalse(ExtensionSymbols.cacheMatches(cache(), "host", "new-profile"))
        for (key in listOf("cache.identity", "cache.profile", "cache.format", "cache.complete")) {
            assertFalse(ExtensionSymbols.cacheMatches(cache().apply { remove(key) }, "host", "profile"))
        }
    }
    @Test fun stableFrameworkReflectionRemainsAvailable() {
        assertEquals(String::class.java, javaClass.classLoader!!.adaptiveClass("java.lang.String"))
        assertEquals("length", String::class.java.adaptiveMethod("length").name)
        assertEquals("MAX_VALUE", Integer::class.java.adaptiveField("MAX_VALUE").name)
    }
}
