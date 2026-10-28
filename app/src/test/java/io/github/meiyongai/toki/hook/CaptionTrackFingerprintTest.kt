package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CaptionTrackFingerprintTest {
    @Test fun confirmedPlaybackTrackIsNotReplacedByStaleFeedMetadata() {
        val video = "confirmed-playback-track"
        val confirmed = "https://cdn.tiktokcdn.com/selected/en.vtt"
        CaptionPrefetch.note(video, confirmed)
        CaptionPrefetch.noteCandidate(video, "https://cdn.tiktokcdn.com/stale/ru.vtt")
        assertEquals(CaptionPrefetch.trackFingerprint(confirmed), CaptionPrefetch.identity(video))
        CaptionPrefetch.note(video, null)
    }
    @Test fun hundredCompletedTracksKeepTheirIdentitiesBeyondTheOldTwelveEntryLimit() {
        for (index in 1..105) CaptionPrefetch.note("retained-$index", "https://cdn.tiktokcdn.com/track/$index.vtt")
        assertEquals(CaptionPrefetch.trackFingerprint("https://cdn.tiktokcdn.com/track/1.vtt"),
            CaptionPrefetch.identity("retained-1"))
        for (index in 1..105) CaptionPrefetch.note("retained-$index", null)
    }
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
