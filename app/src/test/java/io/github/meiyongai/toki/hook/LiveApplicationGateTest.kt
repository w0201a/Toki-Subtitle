package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class LiveApplicationGateTest {
    private fun snapshot(enabled: Boolean, revision: Long) = ConfigSnapshot(mapOf("immersive_full_screen" to enabled), revision)
    @Test fun noBusinessBeforeBothRegistrationCommitAndSessionAdmission() {
        val scope = HookRuntime.RegistrationScope(); var reads = 0
        val gate = LiveApplicationGate(scope, { reads++; snapshot(true, 1) })
        assertFalse(gate.ready()); assertFalse(gate.admit()); assertNull(gate.configuration())
        scope.begin(); assertFalse(gate.admit()); assertFalse(gate.ready())
        assertTrue(scope.commit()); assertFalse(gate.ready()); assertNull(gate.configuration()); assertEquals(0, reads)
        assertTrue(gate.admit()); assertTrue(gate.ready()); assertTrue(gate.configuration()!!.boolean("immersive_full_screen"))
    }
    @Test fun latestDisableReplacesAnEarlierEnabledNotificationBeforeFirstApplication() {
        val scope = HookRuntime.RegistrationScope(); scope.begin()
        var latest = snapshot(true, 3)
        val gate = LiveApplicationGate(scope, { latest })
        latest = snapshot(false, 4)
        scope.commit(); assertTrue(gate.admit())
        assertFalse(gate.configuration()!!.boolean("immersive_full_screen")); assertEquals(4L, gate.configuration()!!.revision)
        latest = snapshot(true, 5); assertEquals(5L, gate.configuration()!!.revision)
    }
    @Test fun nestedOrConcurrentRegistrationDoesNotCommitOnTheFirstPartialReturn() {
        val scope = HookRuntime.RegistrationScope(); scope.begin(); scope.begin()
        val gate = LiveApplicationGate(scope, { snapshot(true, 1) })
        assertFalse(scope.commit()); assertFalse(gate.admit()); assertFalse(scope.committed)
        assertTrue(scope.commit()); assertTrue(gate.admit())
        scope.begin(); assertFalse(gate.ready()); scope.commit(); assertTrue(gate.ready())
    }
    @Test fun failedRegistrationAndLateAdmissionCannotResurrectARevokedLease() {
        val scope = HookRuntime.RegistrationScope(); scope.begin(); scope.revoke()
        val gate = LiveApplicationGate(scope, { snapshot(true, 1) })
        assertFalse(scope.commit()); assertFalse(gate.admit()); assertFalse(gate.ready())
        assertThrows(IllegalStateException::class.java) { scope.begin() }
    }
    @Test fun closingAnUnadmittedOrActiveGateIsPermanentAndIdempotent() {
        val scope = HookRuntime.RegistrationScope(); scope.begin(); scope.commit()
        val gate = LiveApplicationGate(scope, { snapshot(true, 1) })
        assertTrue(gate.admit()); gate.close(); gate.close()
        assertFalse(gate.ready()); assertFalse(gate.admit()); assertNull(gate.configuration())
    }
    @Test fun revokedDuringSnapshotReadIsDiscardedAndConfigurationErrorsFailClosed() {
        val scope = HookRuntime.RegistrationScope(); scope.begin(); scope.commit()
        val gate = LiveApplicationGate(scope, { scope.revoke(); snapshot(true, 1) })
        gate.admit(); assertNull(gate.configuration())
        val other = HookRuntime.RegistrationScope(); other.begin(); other.commit()
        val errors = mutableListOf<Exception>()
        val broken = LiveApplicationGate(other, { throw IllegalStateException("configuration") }, errors::add)
        broken.admit(); assertNull(broken.configuration()); assertEquals(1, errors.size)
    }
    @Test fun anUnavailableCurrentSnapshotNeverFallsBackToAPreviousEnabledSnapshot() {
        val scope = HookRuntime.RegistrationScope(); scope.begin(); scope.commit()
        var latest: ConfigSnapshot? = snapshot(true, 8)
        val gate = LiveApplicationGate(scope, { latest }); gate.admit()
        assertTrue(gate.configuration()!!.boolean("immersive_full_screen"))
        latest = null; assertNull(gate.configuration())
        latest = snapshot(false, 9); assertFalse(gate.configuration()!!.boolean("immersive_full_screen"))
    }
}
