package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class HdrPlaybackHookTest {
    interface NativeConfig { fun isForceHdrOff(): Boolean }
    class NativePlayer : NativeConfig { override fun isForceHdrOff() = false }
    class Unrelated { fun isForceHdrOff() = false }
    @Test fun disabledByUserRequestsNativeHdrOffWithoutForcingHdrWhenToggleIsOff() {
        assertTrue(HdrPlaybackHook.requestedOff(false, true))
        assertTrue(HdrPlaybackHook.requestedOff(true, true))
        assertTrue(HdrPlaybackHook.requestedOff(true, false))
        assertFalse(HdrPlaybackHook.requestedOff(false, false))
        assertFalse(ConfigSchema.booleanDefault(HdrPlaybackHook.KEY))
    }
    @Test fun checksNativeInterfaceAndExactBooleanMethodBeforeInstalling() {
        val method = HdrPlaybackHook.method(NativePlayer::class.java, NativeConfig::class.java)
        assertEquals("isForceHdrOff", method.name)
        assertEquals(Boolean::class.javaPrimitiveType, method.returnType)
        assertThrows(IllegalStateException::class.java) {
            HdrPlaybackHook.method(Unrelated::class.java, NativeConfig::class.java)
        }
    }
    @Test fun lateEnableUsesNormalMirroredBooleanWithoutDexScanOrStartupFreeze() {
        ConfigSchema.validate(mapOf(HdrPlaybackHook.KEY to true))
        val plan = HostFeaturePlan(ConfigSnapshot(emptyMap()))
        val on = ConfigSnapshot(mapOf(HdrPlaybackHook.KEY to true), 2)
        assertTrue("HdrPlaybackHook" in plan.liveCandidates(on))
        assertTrue(HostFeaturePlan.liveSymbols(listOf("HdrPlaybackHook")).isEmpty())
        assertTrue(plan.admitLive("HdrPlaybackHook"))
        assertTrue(plan.apply(on).boolean(HdrPlaybackHook.KEY))
        assertFalse(plan.requiresRestart("HdrPlaybackHook", on))
        assertFalse(plan.apply(ConfigSnapshot(mapOf(HdrPlaybackHook.KEY to false), 3)).boolean(HdrPlaybackHook.KEY))
    }
}
