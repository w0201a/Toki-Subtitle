package io.github.meiyongai.toki.subtitle.hook

import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class HostCoreScannerTest {
    @Test fun independentPassOnlyRechecksMissingTargetsAndRetainsVerifiedMembers() {
        val rules = "A\tfirst\nB\tsecond\n"
        val result = HostCoreScanner.recover(rules, {
            Properties().apply { setProperty("A", "X.Verified"); setProperty("member.A.read", "read()V"); setProperty("error.B", "no native candidates") }
        }, { subset ->
            assertEquals("B\tsecond\n", subset)
            Properties().apply { setProperty("B", "X.Recovered"); setProperty("member.B.read", "actual()V") }
        })
        assertEquals("X.Verified", result.getProperty("A"))
        assertEquals("read()V", result.getProperty("member.A.read"))
        assertEquals("X.Recovered", result.getProperty("B"))
        assertNull(result.getProperty("error.B"))
    }
    @Test fun secondaryFailureDoesNotDiscardAlreadyVerifiedTargets() {
        val result = HostCoreScanner.recover("A\tfirst\nB\tsecond\n", {
            Properties().apply { setProperty("A", "X.Ready"); setProperty("error.B", "missing") }
        }, { throw java.io.IOException() })
        assertEquals("X.Ready", result.getProperty("A"))
        assertTrue(result.getProperty("error.B").contains("IOException"))
    }
    @Test fun unavailableNativeEngineFallsBackAndAmbiguityStaysUnavailable() {
        val result = HostCoreScanner.recover("A\trule\n", { throw UnsatisfiedLinkError() }, {
            Properties().apply { setProperty("error.A", "ambiguous") }
        })
        assertNull(result.getProperty("A"))
        assertEquals("ambiguous", result.getProperty("error.A"))
    }
}
