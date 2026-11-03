package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class LocalOnlyRequestTest {
    @Test fun safetyControlIsValidatedAndNotMaskedByFeatureAdmission() {
        val key = TranslationNetworkPolicy.KEY
        assertFalse(ConfigSchema.booleanDefault(key))
        val snapshot = ConfigSnapshot(mapOf(key to true))
        ConfigSchema.validate(snapshot.configuration())
        assertTrue(HostFeaturePlan(ConfigSnapshot(emptyMap())).apply(snapshot).boolean(key))
    }

    @Test fun aRequestStartedOfflineCannotBecomeOnlineWhenWifiReturns() {
        var online = false
        val operation = LocalOnlyRequest { online }
        online = true
        repeat(3) { assertFalse(operation.permitsNetwork()) }
        assertTrue(LocalOnlyRequest { online }.permitsNetwork())
    }

    @Test fun lossOfEligibilityIsLatchedForTheOperation() {
        var allowed = true
        val operation = LocalOnlyRequest { allowed }
        assertTrue(operation.permitsNetwork())
        allowed = false
        assertFalse(operation.permitsNetwork())
        allowed = true
        assertFalse(operation.permitsNetwork())
    }

    @Test fun missingPermissionsOrUnknownStateFailClosed() {
        val operation = LocalOnlyRequest { throw SecurityException("no permission") }
        assertFalse(operation.permitsNetwork())
        var calls = 0
        val laterFailure = LocalOnlyRequest { if (++calls >= 3) error("unknown") else true }
        assertTrue(laterFailure.permitsNetwork())
        assertFalse(laterFailure.permitsNetwork())
    }
}
