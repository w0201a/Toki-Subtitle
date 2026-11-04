package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HdrSelectionScopeTest {
    @Test fun unrelatedGetterReadsAndDisabledSelectionKeepNativeDecision() {
        val scope = HdrSelectionScope()
        assertFalse(scope.gate(false)); assertTrue(scope.gate(true)); assertNull(scope.call)
        scope.selecting(false) { call ->
            assertFalse(scope.gate(false)); assertTrue(scope.gate(true))
            assertEquals(2, call.gateReads)
        }
        assertNull(scope.call)
    }
    @Test fun onlyEnabledSynchronousSelectionCanBypassTheOuterGate() {
        val scope = HdrSelectionScope()
        scope.selecting(true) { call ->
            assertSame(call, scope.call); assertTrue(scope.gate(false))
            assertTrue(HdrPlaybackHook.requestedOff(false, call.disabled))
        }
        assertFalse(scope.gate(false)); assertNull(scope.call)
    }
    @Test fun nestedDecisionsAndExceptionsRestoreThePreviousScope() {
        val scope = HdrSelectionScope()
        scope.selecting(true) { outer ->
            assertThrows(IllegalArgumentException::class.java) {
                scope.selecting(false) { assertFalse(scope.gate(false)); throw IllegalArgumentException() }
            }
            assertSame(outer, scope.call); assertTrue(scope.gate(false))
        }
        assertNull(scope.call)
        assertThrows(IllegalStateException::class.java) { scope.selecting(true) { throw IllegalStateException() } }
        assertFalse(scope.gate(false))
    }
    @Test fun playerThreadScopesCannotLeakIntoOtherThreads() {
        val scope = HdrSelectionScope(); val executor = Executors.newSingleThreadExecutor()
        try {
            scope.selecting(true) {
                assertFalse(executor.submit<Boolean> { scope.gate(false) }.get(5, TimeUnit.SECONDS))
                assertTrue(scope.gate(false))
            }
        } finally { executor.shutdownNow() }
    }
}
