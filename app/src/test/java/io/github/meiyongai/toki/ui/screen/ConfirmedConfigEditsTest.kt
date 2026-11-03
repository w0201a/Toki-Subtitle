package io.github.meiyongai.toki.subtitle.ui.screen

import org.junit.Assert.*
import org.junit.Test

class ConfirmedConfigEditsTest {
    @Test fun oneWriteThenOneReloadConfirmsTheWholeRelatedTransaction() {
        val calls = mutableListOf<String>(); var saved = emptyMap<String, Any>(); var reported: Exception? = IllegalStateException()
        val edits = ConfirmedConfigEdits({ values -> calls += "write"; saved = values }, { calls += "reload" }, { reported = it })
        val values = mapOf("target_region" to "US", "sim_operator_code" to "310410", "sim_operator_name" to "AT&T")
        assertTrue(edits.save(values)); assertEquals(values, saved); assertEquals(listOf("write", "reload"), calls); assertNull(reported)
    }
    @Test fun failedWriteReloadsCommittedValuesAndDoesNotReportSuccess() {
        var shown = "optimistic"; var error: Exception? = null; var calls = 0
        val edits = ConfirmedConfigEdits({ calls++; throw IllegalStateException("not committed") }, { shown = "previous committed" }, { error = it })
        assertFalse(edits.save(mapOf("target_region" to "US")))
        assertEquals(1, calls); assertEquals("previous committed", shown); assertNotNull(error)
    }
    @Test fun readbackFailureIsVisibleAndSuccessfulRetryClearsError() {
        var unavailable = true; var error: Exception? = null
        val edits = ConfirmedConfigEdits({}, { if (unavailable) throw IllegalStateException("readback unavailable") }, { error = it })
        assertFalse(edits.save(emptyMap())); assertNotNull(error)
        unavailable = false; assertTrue(edits.save(emptyMap())); assertNull(error)
    }
}
