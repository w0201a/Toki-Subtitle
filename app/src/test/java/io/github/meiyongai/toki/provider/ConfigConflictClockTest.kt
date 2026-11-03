package io.github.meiyongai.toki.subtitle.provider

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ConfigConflictClockTest {
    @Test fun typedValueKeyRevisionAndImportsAllPartitionConflicts() {
        val base = mapOf("subtitle_size" to "20", ConfigStore.IMPORT_REVISION to 0L)
        val fingerprint = ConfigConflictClock.fingerprint(base, "subtitle_size", mapOf("subtitle_size" to 1L))
        assertEquals(64, fingerprint.length)
        assertNotEquals(fingerprint, ConfigConflictClock.fingerprint(base, "subtitle_size", mapOf("subtitle_size" to 2L)))
        assertNotEquals(fingerprint, ConfigConflictClock.fingerprint(base + ("subtitle_size" to "24"), "subtitle_size", mapOf("subtitle_size" to 1L)))
        assertNotEquals(fingerprint, ConfigConflictClock.fingerprint(base + (ConfigStore.IMPORT_REVISION to 3L), "subtitle_size", mapOf("subtitle_size" to 1L)))
        assertEquals(fingerprint, ConfigConflictClock.fingerprint(base + ("video_translate_enabled" to true), "subtitle_size", mapOf("subtitle_size" to 1L)))
    }

    @Test fun metadataRoundTripsButFutureNegativeAndNonIntegralRevisionsAreRejected() {
        val encoded = ConfigConflictClock.encode(mapOf("subtitle_size" to 4L))
        assertEquals(mapOf("subtitle_size" to 4L), ConfigConflictClock.revisions(encoded, 4L))
        for (value in listOf(5L, -1L, 1.5, "4", true)) {
            assertThrows(IllegalArgumentException::class.java) {
                ConfigConflictClock.revisions(ConfigConflictClock.encode(mapOf("subtitle_size" to value)), 4L)
            }
        }
        assertTrue(ConfigConflictClock.revisions(null, 0L).isEmpty())
        val fingerprint = ConfigConflictClock.fingerprint(emptyMap<String, Any>(), "subtitle_size", emptyMap())
        assertEquals(mapOf("subtitle_size" to fingerprint), ConfigConflictClock.baselines(
            ConfigConflictClock.encode(mapOf("subtitle_size" to fingerprint))))
    }
}
