package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class OfflineCommentEntryPolicyTest {
    @Test fun allEligibilityConditionsAreRequiredNotJustTheOfflineFlag() {
        for (mask in 0 until 64) {
            val flags = (0..5).map { mask and (1 shl it) != 0 }
            assertEquals(mask.toString(), mask == 63, OfflineCommentEntryPolicy.intercept(flags[0], flags[1],
                "selected", "selected", flags[2], flags[3], flags[4], flags[5]))
        }
        assertFalse(OfflineCommentEntryPolicy.intercept(true, true, "one", "two", true, true, true, true))
        assertFalse(OfflineCommentEntryPolicy.intercept(true, true, "", "", true, true, true, true))
        assertFalse(OfflineCommentEntryPolicy.intercept(true, true, null, null, true, true, true, true))
        assertFalse(OfflineCommentEntryPolicy.intercept(true, true, "one", null, true, true, true, true))
    }
    @Test fun directButtonCanBeAdmittedWithoutEnablingLongPressOrTranslation() {
        val key = OfflineVideoHook.KEY_ENTRY
        assertFalse(ConfigSchema.booleanDefault(key))
        val request = ConfigSnapshot(mapOf(key to true))
        ConfigSchema.validate(request.configuration())
        val startup = HostFeaturePlan(request)
        assertEquals(setOf("OfflineVideoHook"), startup.features)
        assertEquals(setOf("OfflineVideoHook"), startup.directHostFeatures)
        assertTrue(startup.symbols.isEmpty())
        assertFalse(startup.apply(request).boolean(OfflineVideoHook.KEY))
        assertFalse(startup.apply(request).boolean(CachedCommentTranslateHook.KEY))
        val late = HostFeaturePlan(ConfigSnapshot(emptyMap()))
        assertEquals(listOf("OfflineVideoHook"), late.liveCandidates(request))
        assertFalse(late.apply(request).boolean(key))
        assertTrue(late.admitLive("OfflineVideoHook"))
        assertTrue(late.apply(request).boolean(key))
        assertFalse(late.apply(ConfigSnapshot(mapOf(key to false))).boolean(key))
    }
    class Valid { fun open() { } }
    class Wrong { fun open(): String = "not void" }
    @Test fun entryMethodContractsRejectChangedReturnTypesBeforeRegistering() {
        assertEquals(Void.TYPE, OfflineCommentEntryContract.instance(Valid::class.java, "open", Void.TYPE).returnType)
        assertThrows(IllegalStateException::class.java) { OfflineCommentEntryContract.instance(Wrong::class.java, "open", Void.TYPE) }
        assertThrows(NoSuchMethodException::class.java) { OfflineCommentEntryContract.instance(Valid::class.java, "missing", Void.TYPE) }
    }
}
