package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class HoldSpeedGestureScopeTest {
    @Test fun nestedModeScopeRestoresAfterReturnAndException() {
        val scope = NativeSpeedGestureScope()
        assertFalse(scope.active)
        assertEquals(7, scope.enter {
            assertTrue(scope.active)
            assertThrows(IllegalStateException::class.java) {
                scope.enter<Unit> { assertTrue(scope.active); error("failed native call") }
            }
            assertTrue(scope.active)
            7
        })
        assertFalse(scope.active)
        assertThrows(IllegalArgumentException::class.java) { scope.enter<Unit> { throw IllegalArgumentException("failure") } }
        assertFalse(scope.active)
    }

    @Test fun unrelatedThreadsNeverSeeAnotherGesturesGateOverrides() {
        val scope = NativeSpeedGestureScope()
        var otherActive = true
        scope.enter {
            val worker = Thread { otherActive = scope.active }
            worker.start(); worker.join(3000)
            assertFalse(worker.isAlive)
            assertTrue(scope.active)
        }
        assertFalse(otherActive)
        assertFalse(scope.active)
    }

    @Test fun sessionsHaveBoundedIdentityOwnershipAndDeterministicCleanup() {
        val sessions = HoldSpeedSessions(1)
        val first = Any(); val second = Any(); val owner = Any(); val view = Any()
        val initial = checkNotNull(sessions.begin(first, owner, view))
        var cleanup = 0
        initial.cleanup = AutoCloseable { cleanup++ }
        assertSame(owner, initial.owner.get())
        assertSame(view, initial.view.get())
        assertSame(initial, sessions.forOwner(owner))
        assertNull(sessions.begin(second, Any(), Any()))
        val replacement = checkNotNull(sessions.begin(first, owner, view))
        assertEquals(1, cleanup)
        assertFalse(replacement.cancelled)
        replacement.cancelled = true
        replacement.cleanup = AutoCloseable { cleanup++ }
        sessions.removeOwner(owner)
        assertNull(sessions[first])
        assertEquals(2, cleanup)
        sessions.clear()
        assertEquals(2, cleanup)
    }

    @Test fun differentDelegatesAndOwnersCannotAdoptEachOthersSession() {
        val sessions = HoldSpeedSessions()
        val first = Any(); val second = Any(); val owner1 = Any(); val owner2 = Any()
        val s1 = sessions.begin(first, owner1, Any())
        val s2 = sessions.begin(second, owner2, Any())
        assertSame(s1, sessions.forOwner(owner1))
        assertSame(s2, sessions.forOwner(owner2))
        sessions.remove(first)
        assertNull(sessions.forOwner(owner1))
        assertSame(s2, sessions.forOwner(owner2))
        sessions.clear()
        assertTrue(sessions.entries().isEmpty())
    }

    class NativeLike {
        fun begin(x: Float, y: Float) = Unit
        fun finish(cancelled: Boolean) = Unit
        fun active(): Boolean = false
        companion object { @JvmStatic fun wrong(): Boolean = true }
    }

    @Test fun methodContractsRejectStaticWrongArgumentsAndWrongReturnTypes() {
        assertEquals("begin", HoldSpeedContract.instanceMethod(NativeLike::class.java, "begin", Void.TYPE,
            Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!).name)
        assertThrows(IllegalStateException::class.java) {
            HoldSpeedContract.instanceMethod(NativeLike::class.java, "wrong", Boolean::class.javaPrimitiveType!!)
        }
        assertThrows(IllegalStateException::class.java) {
            HoldSpeedContract.instanceMethod(NativeLike::class.java, "active", Void.TYPE)
        }
        assertThrows(NoSuchMethodException::class.java) {
            HoldSpeedContract.instanceMethod(NativeLike::class.java, "begin", Void.TYPE, View::class.java)
        }
    }

    @Test fun optionIsDefaultOffAndIndependentOfFixedSpeedAndNavigation() {
        assertFalse(ConfigSchema.booleanDefault(HoldSpeedGestureHook.KEY))
        ConfigSchema.validate(mapOf(HoldSpeedGestureHook.KEY to true))
        val request = ConfigSnapshot(mapOf(HoldSpeedGestureHook.KEY to true))
        val plan = HostFeaturePlan(request)
        assertEquals(setOf("HoldSpeedGestureHook"), plan.directHostFeatures)
        assertTrue(plan.symbols.isEmpty())
        val late = HostFeaturePlan(ConfigSnapshot(emptyMap()))
        assertEquals(listOf("HoldSpeedGestureHook"), late.liveCandidates(request))
        assertFalse(late.apply(request).boolean(HoldSpeedGestureHook.KEY))
        assertTrue(late.admitLive("HoldSpeedGestureHook"))
        assertTrue(late.apply(request).boolean(HoldSpeedGestureHook.KEY))
        assertFalse(late.apply(request).boolean("fixed_speed_enabled"))
        assertFalse(late.apply(request).boolean("speed_expand_enabled"))
    }
    @Test fun positiveNativeThresholdIsPreservedAndDisabledZeroHasABoundedLocalFallback() {
        assertEquals(200f, HoldSpeedThreshold.pixels(200f, 2f, 16f), 0f)
        assertEquals(144f, HoldSpeedThreshold.pixels(0f, 2f, 16f), 0f)
        assertEquals(144f, HoldSpeedThreshold.pixels(-1f, 2f, 16f), 0f)
        assertEquals(144f, HoldSpeedThreshold.pixels(Float.NaN, 2f, 16f), 0f)
        assertEquals(180f, HoldSpeedThreshold.pixels(10f, 1f, 60f), 0f)
        assertThrows(IllegalArgumentException::class.java) { HoldSpeedThreshold.pixels(0f, 0f, 8f) }
    }

}
