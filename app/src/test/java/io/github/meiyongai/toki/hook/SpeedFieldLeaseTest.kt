package io.github.meiyongai.toki.subtitle.hook

import java.lang.reflect.Field
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SpeedFieldLeaseTest {
    class Fields { companion object {
        @JvmField var enabled = false
        @JvmField var persist = 1.25f
        @JvmField var current0 = 1.5f
        @JvmField var current1 = 1.75f
    } }
    private fun fields(): Map<String, Field> = listOf("enabled", "persist", "current0", "current1")
        .associateWith { Fields::class.java.getDeclaredField(it) }
    @Before fun reset() { Fields.enabled = false; Fields.persist = 1.25f; Fields.current0 = 1.5f; Fields.current1 = 1.75f }

    @Test fun restoresActualOriginalFieldsInsteadOfUnitRate() {
        val lease = SpeedFieldLease(fields())
        lease.apply(2f, true); lease.apply(2.5f, true); lease.restore()
        assertFalse(Fields.enabled); assertEquals(1.25f, Fields.persist, 0f)
        assertEquals(1.5f, Fields.current0, 0f); assertEquals(1.75f, Fields.current1, 0f)
    }

    @Test fun hostMutationAfterModuleWriteIsNotClobberedOnRestore() {
        val lease = SpeedFieldLease(fields())
        lease.apply(2f, true); Fields.current0 = 1.3f; lease.restore()
        assertEquals(1.3f, Fields.current0, 0f); assertFalse(Fields.enabled)
    }

    @Test fun successfulNativeCallDoesNotAdoptUnchangedModuleFieldsAsNative() {
        val lease = SpeedFieldLease(fields())
        lease.apply(2f, true); Fields.persist = 1.8f
        lease.observeNativeState(); lease.apply(2.5f, true); lease.restore()
        assertFalse(Fields.enabled); assertEquals(1.8f, Fields.persist, 0f)
        assertEquals(1.5f, Fields.current0, 0f); assertEquals(1.75f, Fields.current1, 0f)
    }

    @Test fun moduleGeneratedStateMutationCannotReplaceNativeBaseline() {
        val lease = SpeedFieldLease(fields())
        lease.apply(2f, true); Fields.persist = 3f
        lease.apply(2f, true, observeExternalChanges = false); lease.restore()
        assertEquals(1.25f, Fields.persist, 0f)
    }

    @Test fun externalChangesBetweenConfigUpdatesBecomeRestorationBaseline() {
        val lease = SpeedFieldLease(fields())
        lease.apply(2f, true); Fields.persist = 1.4f
        lease.apply(2.5f, true); lease.restore()
        assertEquals(1.4f, Fields.persist, 0f)
    }

    @Test fun partialRestoreContinuesAggregatesErrorsAndRetainsRetryEvidence() {
        var failing = emptySet<String>()
        val lease = SpeedFieldLease(fields(), write = { field, value ->
            if (field.name in failing) throw IllegalStateException(field.name)
            field.set(null, value)
        })
        lease.apply(2f, true)
        failing = setOf("enabled", "persist")
        val error = assertThrows(IllegalStateException::class.java) { lease.restore() }
        assertEquals(1, error.suppressed.size)
        assertEquals(1.5f, Fields.current0, 0f); assertEquals(1.75f, Fields.current1, 0f)
        assertTrue(Fields.enabled); assertEquals(2f, Fields.persist, 0f)
        failing = emptySet(); lease.restore()
        assertFalse(Fields.enabled); assertEquals(1.25f, Fields.persist, 0f)
        Fields.current0 = 1.9f; lease.restore()
        assertEquals(1.9f, Fields.current0, 0f)
    }

    @Test fun partialApplyStillRestoresSuccessfullyWrittenFields() {
        val lease = SpeedFieldLease(fields(), write = { field, value ->
            if (field.name == "persist") throw IllegalStateException("blocked")
            field.set(null, value)
        })
        assertThrows(IllegalStateException::class.java) { lease.apply(2f, true) }
        lease.restore()
        assertFalse(Fields.enabled); assertEquals(1.25f, Fields.persist, 0f)
        assertEquals(1.5f, Fields.current0, 0f); assertEquals(1.75f, Fields.current1, 0f)
    }

    @Test fun invalidSpeedIsRejectedBeforeAnyFieldMutation() {
        val lease = SpeedFieldLease(fields())
        assertThrows(IllegalArgumentException::class.java) { lease.apply(Float.NaN, true) }
        assertThrows(IllegalArgumentException::class.java) { lease.apply(4f, true) }
        assertFalse(Fields.enabled); assertEquals(1.25f, Fields.persist, 0f)
    }
}
