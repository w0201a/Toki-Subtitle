package io.github.meiyongai.toki.subtitle.hook

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class LocalVideoTracksTest {
    private fun track(mime: String, first: Long = 0, last: Long = 800_000) =
        LocalVideoTracks.Track(0, mime, 1_000_000, 0, 5, 100, first, last, "fingerprint")
    private fun source(name: String, vararg tracks: LocalVideoTracks.Track) =
        LocalVideoTracks.Source(LocalVideoFile.Verified(File(name), 1000, 0), tracks.toList())
    @Test fun aCompleteMuxedSourceNeedsNoSeparateAudioAndSilentVideoCanRemainSilent() {
        val video = source("v", track("video/avc"), track("audio/mp4a-latm"))
        assertEquals(2, LocalVideoTracks.plan(video, null, true, 1000).tracks.size)
        assertEquals(1, LocalVideoTracks.plan(source("silent", track("video/avc")), null, false, 1000).tracks.size)
    }
    @Test fun knownRequiredAudioCannotBeDroppedAndDuplicateOrWrongTrackPlansFail() {
        val video = source("v", track("video/avc"))
        val audio = source("a", track("audio/mp4a-latm"))
        assertEquals(2, LocalVideoTracks.plan(video, audio, true, 1000).tracks.size)
        val absent = assertThrows(LocalVideoFile.Unavailable::class.java) { LocalVideoTracks.plan(video, null, true, 1000) }
        assertEquals(LocalVideoFile.Failure.INCOMPLETE, absent.reason)
        assertThrows(LocalVideoFile.Unavailable::class.java) { LocalVideoTracks.plan(video, source("v", track("audio/mp4a-latm")), true, 1000) }
        assertThrows(LocalVideoFile.Unavailable::class.java) { LocalVideoTracks.plan(video, source("wrong", track("video/avc")), true, 1000) }
        assertThrows(LocalVideoFile.Unavailable::class.java) { LocalVideoTracks.plan(audio, null, false, 1000) }
    }
    @Test fun sampleCoverageMustMatchTheVideoAndAudioCannotComeFromAnUnrelatedTimeline() {
        val video = source("v", track("video/avc"))
        assertThrows(LocalVideoFile.Unavailable::class.java) { LocalVideoTracks.plan(video, null, false, 5000) }
        assertThrows(LocalVideoFile.Unavailable::class.java) {
            LocalVideoTracks.plan(video, source("a", track("audio/mp4a-latm", 3_000_000, 3_800_000)), true, 1000)
        }
    }
    @Test fun fingerprintsIncludeSampleBoundariesAndAcceptDecodeOrderBFramePresentationTimes() {
        fun digest(split: Boolean): String {
            val result = LocalVideoTracks.Digest()
            if (split) {
                result.sample(ByteBuffer.wrap(byteArrayOf(1)), 1, 100)
                result.sample(ByteBuffer.wrap(byteArrayOf(2, 3)), 2, 50)
                assertEquals(50L, result.firstUs); assertEquals(100L, result.lastUs)
            } else result.sample(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 3, 50)
            return result.finish()
        }
        assertNotEquals(digest(true), digest(false)); assertEquals(digest(true), digest(true))
    }
    @Test fun codecPolicyDoesNotClaimUnsupportedTracksCanBeMuxedWithoutTranscoding() {
        assertTrue(LocalVideoTracks.supportedVideo("video/avc")); assertTrue(LocalVideoTracks.supportedVideo("video/hevc"))
        assertTrue(LocalVideoTracks.supportedAudio("audio/mp4a-latm"))
        assertFalse(LocalVideoTracks.supportedAudio("audio/opus")); assertFalse(LocalVideoTracks.supportedVideo("video/unknown"))
    }
    @Test fun declaredAacPrimingUsesSampleRateWithRoundingAndDoesNotRelaxOtherTracks() {
        assertEquals(-161134L, LocalVideoTracks.minimumSampleTime("audio/mp4a-latm", 22050, 3553))
        assertEquals(0L, LocalVideoTracks.minimumSampleTime("audio/mp4a-latm", 22050, 0))
        assertEquals(0L, LocalVideoTracks.minimumSampleTime("video/hevc", 22050, 3553))
        assertEquals(0L, LocalVideoTracks.minimumSampleTime("audio/unknown", 22050, 3553))
        for ((rate, delay) in listOf(0 to 3553, -1 to 1, 22050 to -1, 22050 to 44101, 384001 to 1)) {
            assertEquals(LocalVideoFile.Failure.TIMELINE, assertThrows(LocalVideoFile.Unavailable::class.java) {
                LocalVideoTracks.minimumSampleTime("audio/mp4a-latm", rate, delay)
            }.reason)
        }
    }
    @Test fun negativePrimingSamplesAreHashedOnlyWithinDeclaredDelay() {
        val floor = LocalVideoTracks.minimumSampleTime("audio/mp4a-latm", 22050, 3553)
        val digest = LocalVideoTracks.Digest(floor)
        digest.sample(ByteBuffer.wrap(byteArrayOf(1)), 1, -161133)
        digest.sample(ByteBuffer.wrap(byteArrayOf(2)), 1, 0)
        assertEquals(2L, digest.count); assertEquals(-161133L, digest.firstUs)
        assertThrows(IllegalArgumentException::class.java) { digest.sample(ByteBuffer.wrap(byteArrayOf(3)), 1, floor - 1) }
        assertThrows(IllegalArgumentException::class.java) { LocalVideoTracks.Digest().sample(ByteBuffer.wrap(byteArrayOf(3)), 1, -1) }
    }
}
