package io.github.meiyongai.toki.subtitle.hook

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class PlaybackRateScopeTest {
    @Test fun nestedModuleAndSelectionScopesAreIndependentAndFinallyRestored() {
        val scope = PlaybackRateScope()
        assertFalse(scope.applying); assertFalse(scope.selecting)
        assertThrows(IllegalStateException::class.java) {
            scope.module {
                assertTrue(scope.applying); assertFalse(scope.selecting)
                scope.nativeSelection {
                    assertTrue(scope.applying); assertTrue(scope.selecting)
                    scope.module { assertTrue(scope.applying) }
                    scope.nativeSelection { assertTrue(scope.selecting) }
                }
                assertFalse(scope.selecting)
                throw IllegalStateException("test")
            }
        }
        assertFalse(scope.applying); assertFalse(scope.selecting)
    }

    @Test fun anotherThreadDoesNotInheritModuleOrNativeSelectionScopes() {
        val scope = PlaybackRateScope()
        val observed = AtomicReference<Pair<Boolean, Boolean>>()
        scope.module { scope.nativeSelection {
            val thread = Thread { observed.set(scope.applying to scope.selecting) }
            thread.start(); thread.join(5000)
            assertFalse(thread.isAlive)
            assertEquals(false to false, observed.get())
            assertTrue(scope.applying); assertTrue(scope.selecting)
        } }
    }
}
