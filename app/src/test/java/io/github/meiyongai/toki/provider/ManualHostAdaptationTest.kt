package io.github.meiyongai.toki.subtitle.provider

import org.junit.Assert.*
import org.junit.Test

class ManualHostAdaptationTest {
    private val global = ManualHostAdaptation.packages[0]
    private val trill = ManualHostAdaptation.packages[1]
    private fun request(previous: String?, host: String, token: String) =
        ManualHostAdaptation.values(previous, host, token).getValue(ManualHostAdaptation.KEY) as String

    @Test fun selectionOnlyTargetsTheChosenHost() {
        val raw = request(null, global, "one")
        assertTrue(ManualHostAdaptation.pending(raw, global, "old"))
        assertFalse(ManualHostAdaptation.requested(raw, trill))
        assertFalse(ManualHostAdaptation.pending(raw, trill, "old"))
        assertEquals("old", ManualHostAdaptation.token(raw, trill, "old"))
    }
    @Test fun secondSelectionPreservesOtherHostsRequest() {
        val raw = request(request(null, global, "one"), trill, "two")
        assertEquals("one", ManualHostAdaptation.token(raw, global, ""))
        assertEquals("two", ManualHostAdaptation.token(raw, trill, ""))
        assertTrue(ManualHostAdaptation.pending(raw, trill, "one"))
    }
    @Test fun anotherClickOnlyReplacesSelectedNonce() {
        val raw = request(request(request(null, global, "one"), trill, "two"), global, "three")
        assertEquals("three", ManualHostAdaptation.token(raw, global, ""))
        assertEquals("two", ManualHostAdaptation.token(raw, trill, ""))
    }
    @Test fun consumedRequestDoesNotRescanOnEveryStartup() {
        val raw = request(null, global, "one")
        assertFalse(ManualHostAdaptation.pending(raw, global, "one"))
        assertTrue(ManualHostAdaptation.pending(request(raw, global, "two"), global, "one"))
    }
    @Test fun legacyGlobalRetryStillTargetsBothHosts() {
        val raw = "d599bbf2-900e-4eef-bacc-11fcc92b588f"
        for (host in listOf(global, trill)) {
            assertTrue(ManualHostAdaptation.pending(raw, host, ""))
            assertFalse(ManualHostAdaptation.pending(raw, host, raw))
        }
        val migrated = request(raw, global, "next")
        assertEquals(raw, ManualHostAdaptation.token(migrated, trill, ""))
    }
    @Test fun malformedInputsNeverBecomeGlobalRequests() {
        val bad = listOf(null, "", "hosts-v1|", "hosts-v2|$global:one",
            "hosts-v1|unknown:one", "hosts-v1|$global:one;$global:two",
            "hosts-v1|$global:one;$trill:two;unknown:three",
            "hosts-v1|$global:one;", "hosts-v1|$global:",
            "hosts-v1|$global:bad token", "hosts-v1|$global:one:two",
            "hosts-v1|$global:${"a".repeat(129)}", "bad|legacy")
        for (raw in bad) for (host in listOf(global, trill)) {
            assertFalse("$raw/$host", ManualHostAdaptation.requested(raw, host))
            assertFalse(ManualHostAdaptation.pending(raw, host, "consumed"))
            assertEquals("consumed", ManualHostAdaptation.token(raw, host, "consumed"))
        }
    }
    @Test fun unknownHostIsNeverTargetedEvenByLegacyToken() {
        assertFalse(ManualHostAdaptation.pending("legacy", "unknown", ""))
        assertThrows(IllegalArgumentException::class.java) { request(null, "unknown", "one") }
    }
    @Test fun invalidNonceCannotBeSaved() {
        for (nonce in listOf("", "bad token", "a;b", "a:b", "a".repeat(129)))
            assertThrows(IllegalArgumentException::class.java) { request(null, global, nonce) }
    }
    @Test fun eachDefaultRequestHasANewNonce() {
        val first = ManualHostAdaptation.values(null, global).getValue(ManualHostAdaptation.KEY) as String
        val second = ManualHostAdaptation.values(first, global).getValue(ManualHostAdaptation.KEY) as String
        assertNotEquals(ManualHostAdaptation.token(first, global, ""), ManualHostAdaptation.token(second, global, ""))
    }
    @Test fun usesExistingSchemaAndChangesNoFeatureSettings() {
        val baseline = mapOf<String, Any>("feed_remove_ads" to true, "target_region" to "JP")
        val changes = ManualHostAdaptation.values(null, global, "one")
        assertEquals(setOf("adaptation_retry_token"), changes.keys)
        val checked = ConfigSchema.validate(baseline + changes)
        for ((key, value) in baseline) assertEquals(value, checked[key])
        assertEquals(changes[ManualHostAdaptation.KEY], checked[ManualHostAdaptation.KEY])
    }
}
