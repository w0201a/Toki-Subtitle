package io.github.meiyongai.toki.subtitle.hook

import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class ExtensionSymbolsTest {
    @Test fun searchHintsPreserveCacheButEvidenceChangesInvalidateIt() {
        val profile = Properties().apply { setProperty("class.X.A", "verified-shape") }
        val original = ExtensionSymbols.profileHash(profile)
        profile.setProperty("native.super.header", "Ljava/lang/Object;")
        profile.setProperty("native.methods.header", "()V")
        assertEquals(original, ExtensionSymbols.profileHash(profile))
        profile.setProperty("class.X.A", "changed-shape")
        assertNotEquals(original, ExtensionSymbols.profileHash(profile))
    }
    @Test fun cacheRequiresExactCodeProfileAndFormat() {
        fun cache() = Properties().apply {
            setProperty("cache.identity", "host")
            setProperty("cache.profile", "profile")
            setProperty("cache.format", ExtensionDexIndex.FORMAT)
            setProperty("cache.matcher", "relationships-v6")
            setProperty("cache.complete", "true")
        }
        assertTrue(ExtensionSymbols.cacheMatches(cache(), "host", "profile"))
        assertFalse(ExtensionSymbols.cacheMatches(cache(), "new-host", "profile"))
        assertFalse(ExtensionSymbols.cacheMatches(cache(), "host", "new-profile"))
        for (key in listOf("cache.identity", "cache.profile", "cache.format", "cache.matcher", "cache.complete")) {
            assertFalse(ExtensionSymbols.cacheMatches(cache().apply { remove(key) }, "host", "profile"))
        }
    }
    @Test fun retryRequestInvalidatesOldResultWithoutChangingCodeIdentity() {
        val stored = Properties().apply {
            setProperty("cache.identity", "host")
            setProperty("cache.profile", "profile")
            setProperty("cache.format", ExtensionDexIndex.FORMAT)
            setProperty("cache.matcher", "relationships-v6")
            setProperty("cache.complete", "true")
        }
        assertTrue(ExtensionSymbols.cacheMatches(stored, "host", "profile"))
        assertFalse(ExtensionSymbols.cacheMatches(stored, "host", "profile", "new-request"))
        stored.setProperty("cache.retryToken", "new-request")
        assertTrue(ExtensionSymbols.cacheMatches(stored, "host", "profile", "new-request"))
        assertFalse(ExtensionSymbols.cacheMatches(stored, "host", "profile", "another-request"))
    }
    @Test fun stableFrameworkReflectionRemainsAvailable() {
        assertEquals(String::class.java, javaClass.classLoader!!.adaptiveClass("java.lang.String"))
        assertEquals("length", String::class.java.adaptiveMethod("length").name)
        assertEquals("MAX_VALUE", Integer::class.java.adaptiveField("MAX_VALUE").name)
    }
}
