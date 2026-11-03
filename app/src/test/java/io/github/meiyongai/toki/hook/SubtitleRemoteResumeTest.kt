package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class SubtitleRemoteResumeTest {
    @Test fun becomingOnlineResumesOnceRatherThanRetryingOnEveryFrame() {
        val gate = SubtitleRemoteResume()
        assertFalse(gate.observe(0) { false })
        assertTrue(gate.observe(1000) { true })
        assertFalse(gate.observe(2000) { true })
        assertFalse(gate.observe(3000) { true })
        assertFalse(gate.observe(4000) { false })
        assertTrue(gate.observe(5000) { true })
    }
    @Test fun availabilityIsSampledAtMostOncePerSecondAndFirstOnlineObservationIsNotARetry() {
        val gate = SubtitleRemoteResume()
        var samples = 0
        assertFalse(gate.observe(0) { samples++; true })
        for (now in 1L..999L) assertFalse(gate.observe(now) { samples++; false })
        assertEquals(1, samples)
        assertFalse(gate.observe(1000) { samples++; false })
        assertEquals(2, samples)
        assertTrue(gate.observe(2000) { true })
    }
    @Test fun explicitLocalOnlyAndUnreadyOrDisconnectedStatesDenyRemoteButOfflineContentDoesNot() {
        for (offlineItem in listOf(false, true)) {
            val state = OfflinePlaybackState()
            state.select(Any(), "video", offlineItem)
            assertTrue(TranslationNetworkPolicy.allowsRemote(true, false, true))
            assertFalse(TranslationNetworkPolicy.allowsRemote(true, true, true))
            assertFalse(TranslationNetworkPolicy.allowsRemote(true, false, false))
            assertFalse(TranslationNetworkPolicy.allowsRemote(false, false, true))
            assertEquals(offlineItem, state.offline)
        }
    }
}
