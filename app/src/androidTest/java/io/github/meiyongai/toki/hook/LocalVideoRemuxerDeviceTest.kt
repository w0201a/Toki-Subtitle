package io.github.meiyongai.toki.subtitle.hook

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4


/** Required real Android MP4 integration: these assertions must not be replaced by desktop shadow success. */
@RunWith(AndroidJUnit4::class)

class LocalVideoRemuxerDeviceTest {
    @get:Rule val folder = TemporaryFolder(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)
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
    @Test fun retainedHeaderMdlUsesSameVirtualSourceForValidationAndActualMuxing() {
        val original = OfflineExportFixtures.VIDEO
        val key = "synthetic-native-key"
        val record = 96 + key.length
        val node = ByteArray(record + 160)
        val b = java.nio.ByteBuffer.wrap(node).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        fun i(at: Int, value: Int) { b.putInt(at, value) }
        fun tag(at: Int, value: String) { value.toByteArray().copyInto(node, at) }
        i(0, node.size); tag(4, "ttmd"); i(12, 1); b.putLong(16, original.size.toLong())
        i(24, 32); i(28, record + 8); i(32, 56); tag(36, "mfni")
        b.putLong(56, original.size.toLong()); b.putLong(64, original.size.toLong())
        i(88, key.length + 8); tag(92, "fkey"); tag(96, key)
        i(record, node.size); tag(record + 4, "ttmd"); i(record + 8, 1); i(record + 12, 128)
        original.copyInto(node, record + 24, 0, 128)
        i(node.size - 8, node.size); tag(node.size - 4, "ttmd")
        val masked = original.copyOf().apply { fill(0, 0, 128) }
        val media = folder.newFile("native.mdl").apply { writeBytes(masked) }
        val sidecar = folder.newFile("native.mdlnodeconf").apply { writeBytes(node) }
        val verified = OfflineMdlFile.inspect(media, sidecar, key, listOf(folder.root))
        OfflineMdlDataSource(verified) { true }.use { input ->
            val bytes = ByteArray(80)
            assertEquals(80, input.readAt(100, bytes, 0, bytes.size))
            assertArrayEquals(original.copyOfRange(100, 180), bytes)
            assertEquals(0, input.readAt(original.size.toLong(), bytes, 0, 0))
            assertEquals(-1, input.readAt(original.size.toLong(), bytes, 0, 1))
        }
        val plan = LocalVideoTracks.plan(LocalVideoTracks.inspect(verified) { true }, null, false, 1000)
        assertTrue(LocalVideoRemuxer.preservesContainer(plan))
        val result = folder.newFile("native-result.mp4")
        RandomAccessFile(result, "rw").use { output ->
            LocalVideoRemuxer.write(plan, output.fd) { true }
            LocalVideoRemuxer.verifyOutput(plan, output.fd, output.length()) { true }
        }
        assertArrayEquals(original, result.readBytes())
        assertArrayEquals(masked, media.readBytes()); assertArrayEquals(node, sidecar.readBytes())
    }
}
