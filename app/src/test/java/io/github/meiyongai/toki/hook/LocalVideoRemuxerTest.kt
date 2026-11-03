package io.github.meiyongai.toki.subtitle.hook

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Requires working platform MediaExtractor/MediaMuxer support. Source-only until execution is authorized. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LocalVideoRemuxerTest {
    @get:Rule val folder = TemporaryFolder()
    private fun local(name: String, bytes: ByteArray): LocalVideoFile.Verified {
        val file = folder.newFile(name).apply { writeBytes(bytes) }
        return LocalVideoFile.inspect(file, file.length(), file.length(), listOf(folder.root))
    }
    @Test fun completeFragmentedVideoAndItsAudioBecomeOneVerifiedMp4WithoutChangingEitherSource() {
        val video = local("video", OfflineExportFixtures.FRAGMENTED_VIDEO)
        val audio = local("audio", OfflineExportFixtures.AUDIO)
        assertTrue(video.fragmented)
        val plan = LocalVideoTracks.plan(LocalVideoTracks.inspect(video) { true }, LocalVideoTracks.inspect(audio) { true }, true, 1000)
        val output = folder.newFile("result.mp4")
        RandomAccessFile(output, "rw").use { destination ->
            LocalVideoRemuxer.write(plan, destination.fd) { true }
            LocalVideoRemuxer.verifyOutput(plan, destination.fd, destination.length()) { true }
        }
        val result = LocalVideoTracks.inspect(LocalVideoFile.inspect(output, output.length(), output.length(), listOf(folder.root))) { true }
        assertEquals(setOf("video/avc", "audio/mp4a-latm"), result.tracks.map { it.mime }.toSet())
        assertArrayEquals(OfflineExportFixtures.FRAGMENTED_VIDEO, video.file.readBytes())
        assertArrayEquals(OfflineExportFixtures.AUDIO, audio.file.readBytes())
    }
    @Test fun ordinaryCompleteLocalVideoAlsoUsesSampleReadbackAndCancellationStopsBeforeMuxing() {
        val video = local("video", OfflineExportFixtures.VIDEO)
        val plan = LocalVideoTracks.plan(LocalVideoTracks.inspect(video) { true }, null, false, 1000)
        RandomAccessFile(folder.newFile("out.mp4"), "rw").use { destination ->
            val cancelled = assertThrows(LocalVideoFile.Unavailable::class.java) { LocalVideoRemuxer.write(plan, destination.fd) { false } }
            assertEquals(LocalVideoFile.Failure.CHANGED, cancelled.reason)
            assertEquals(0L, destination.length())
            LocalVideoRemuxer.write(plan, destination.fd) { true }
            LocalVideoRemuxer.verifyOutput(plan, destination.fd, destination.length()) { true }
        }
    }
    @Test fun changedSourceAndOutputMissingARequiredTrackNeverPassFinalReadback() {
        val video = local("video", OfflineExportFixtures.VIDEO); val audio = local("audio", OfflineExportFixtures.AUDIO)
        val plan = LocalVideoTracks.plan(LocalVideoTracks.inspect(video) { true }, LocalVideoTracks.inspect(audio) { true }, true, 1000)
        RandomAccessFile(video.file, "r").use { input ->
            assertThrows(LocalVideoFile.Unavailable::class.java) { LocalVideoRemuxer.verifyOutput(plan, input.fd, input.length()) { true } }
        }
        video.file.appendBytes(byteArrayOf(0))
        assertThrows(LocalVideoFile.Unavailable::class.java) { plan.verify { true } }
    }
}
