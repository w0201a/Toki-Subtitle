package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CaptionTrackFingerprintTest {
    @Test fun signedUrlRefreshKeepsTrackIdentityButDifferentLanguageTracksDoNotAlias() {
        val first = CaptionPrefetch.trackFingerprint(
            "https://cdn.tiktokcdn.com/caption/en.vtt?lang=en&x-expires=100&x-signature=old")
        val refreshed = CaptionPrefetch.trackFingerprint(
            "https://cdn.tiktokcdn.com/caption/en.vtt?x-signature=new&lang=en&x-expires=200&x-tt-params=rotated")
        val otherTrack = CaptionPrefetch.trackFingerprint(
            "https://cdn.tiktokcdn.com/caption/ru.vtt?lang=ru&x-expires=100")

        assertEquals(first, refreshed)
        assertNotEquals(first, otherTrack)
    }
}
