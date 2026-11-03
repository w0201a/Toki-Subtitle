package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class PlayerRateOverridesTest {
    private class Player(var rate: Float = 1f) {
        override fun equals(other: Any?) = other is Player
        override fun hashCode() = 1
    }
    private fun observe(rates: PlayerRateOverrides, player: Player, native: Float, replacement: Float? = null) {
        rates.request(player, native, replacement, { player.rate = it }) { target, value -> (target as Player).rate = value }
    }

    @Test fun unknownPlayersAreNotChangedAndSamePlayerRestoresItsRealNonUnitRate() {
        val rates = PlayerRateOverrides()
        val player = Player(1.25f)
        assertEquals(0, rates.apply(2f).written)
        assertEquals(1.25f, player.rate, 0f)
        observe(rates, player, 1.25f)
        assertEquals(1, rates.apply(2f).written)
        assertEquals(2f, player.rate, 0f)
        assertEquals(1, rates.apply(null).written)
        assertEquals(1.25f, player.rate, 0f)
        assertEquals(0, rates.apply(null).written)
    }

    @Test fun successfulNativeRequestReplacesOldRestorationIntent() {
        val rates = PlayerRateOverrides()
        val player = Player()
        observe(rates, player, 1f, 2f)
        observe(rates, player, 1.75f)
        assertEquals(0, rates.apply(null).written)
        rates.apply(2.5f); rates.apply(null)
        assertEquals(1.75f, player.rate, 0f)
    }

    @Test fun equalObjectsHaveIndependentIdentityAndTargetedUpdates() {
        val rates = PlayerRateOverrides()
        val first = Player(); val second = Player()
        assertEquals(first, second)
        observe(rates, first, 1.25f); observe(rates, second, 1.5f)
        assertEquals(1, rates.apply(2f, first).written)
        assertEquals(1.5f, second.rate, 0f)
        assertEquals(2, rates.apply(2.5f).written)
        rates.apply(null)
        assertEquals(1.25f, first.rate, 0f); assertEquals(1.5f, second.rate, 0f)
    }

    @Test fun pendingNativeRequestReservesCapacityBeforeOverride() {
        val rates = PlayerRateOverrides(capacity = 1)
        val first = Player(); val second = Player()
        rates.request(first, 1f, 2f, { value ->
            assertFalse(Thread.holdsLock(rates))
            observe(rates, second, 1f, 3f)
            first.rate = value
        }) { target, value -> (target as Player).rate = value }
        assertEquals(2f, first.rate, 0f)
        assertEquals(1f, second.rate, 0f)
        assertEquals(1, rates.apply(null).known)
    }

    @Test fun failedNativeRequestDoesNotCreateBaselineOrLeakReservedCapacity() {
        val rates = PlayerRateOverrides(capacity = 1)
        val first = Player(); val second = Player()
        assertThrows(IllegalStateException::class.java) {
            rates.request(first, 1f, 2f, { throw IllegalStateException("native setter failed") }) { _, _ -> }
        }
        assertEquals(0, rates.apply(null).known)
        observe(rates, second, 1.5f, 2f)
        assertEquals(1, rates.apply(null).written)
        assertEquals(1.5f, second.rate, 0f)
    }

    @Test fun oneFailedRestoreDoesNotBlockOtherPlayersAndCanBeRetried() {
        var fail = true
        val errors = mutableListOf<Exception>()
        val rates = PlayerRateOverrides({ errors += it })
        val first = Player(); val second = Player()
        rates.request(first, 1.25f, 2f, { first.rate = it }) { target, value ->
            if (fail) throw IllegalStateException("restore")
            (target as Player).rate = value
        }
        observe(rates, second, 1.5f, 2f)
        val result = rates.apply(null)
        assertEquals(1, result.failed); assertEquals(1, result.written)
        assertEquals(1.5f, second.rate, 0f); assertEquals(1, errors.size)
        fail = false
        assertEquals(1, rates.apply(null).written)
        assertEquals(1.25f, first.rate, 0f)
    }

    @Test fun changedNativeRevisionDuringModuleWriteIsReportedNotCommittedAsOldState() {
        val rates = PlayerRateOverrides()
        val player = Player()
        var injectNative = true
        rates.request(player, 1.25f, null, { player.rate = it }) { target, value ->
            assertFalse(Thread.holdsLock(rates))
            if (injectNative) { injectNative = false; observe(rates, player, 1.75f) }
            (target as Player).rate = value
        }
        assertEquals(1, rates.apply(2f).raced)
        assertEquals(1, rates.apply(null).written)
        assertEquals(1.75f, player.rate, 0f)
    }

    @Test fun forgottenAndClearedPlayersAreNeverRestoredWithInventedDefaults() {
        val rates = PlayerRateOverrides()
        val first = Player(); val second = Player()
        observe(rates, first, 1.25f, 2f); observe(rates, second, 1.5f, 2f)
        rates.forget(first)
        assertEquals(1, rates.apply(null).written)
        assertEquals(2f, first.rate, 0f)
        rates.clear()
        assertEquals(0, rates.apply(3f).known)
    }

    @Test fun invalidRequestsPassThroughWithoutInventingValidNativeEvidence() {
        val rates = PlayerRateOverrides()
        val player = Player()
        observe(rates, player, 1.5f)
        observe(rates, player, Float.NaN)
        assertEquals(0, rates.apply(null).known)
        assertThrows(IllegalArgumentException::class.java) { rates.apply(0f) }
        assertThrows(IllegalArgumentException::class.java) { rates.apply(Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { PlayerRateOverrides(capacity = 0) }
    }

    @Test fun pendingRequestsAndUnchangedRecordsAreNotCountedAsSuccessfulCommands() {
        val rates = PlayerRateOverrides()
        val player = Player()
        rates.request(player, 1f, 2f, { value ->
            val pending = rates.apply(3f)
            assertEquals(1, pending.deferred); assertEquals(0, pending.written)
            player.rate = value
        }) { target, value -> (target as Player).rate = value }
        val same = rates.apply(2f)
        assertEquals(1, same.unchanged); assertEquals(0, same.written)
        assertTrue(rates.observation(player).tracked)
        rates.apply(null)
        assertEquals(1, rates.apply(null).unchanged)
    }
}
