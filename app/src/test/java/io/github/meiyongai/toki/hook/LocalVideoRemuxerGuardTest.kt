package io.github.meiyongai.toki.subtitle.hook

import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Portable guards run without pretending desktop media shadows implement a platform muxer. */
class LocalVideoRemuxerGuardTest {
    @get:Rule val folder = TemporaryFolder()
    private fun plan(): LocalVideoTracks.Plan {
        val input = folder.newFile().apply { writeBytes(OfflineExportFixtures.VIDEO) }
        val verified = LocalVideoFile.inspect(input, input.length(), input.length(), listOf(folder.root))
        val track = LocalVideoTracks.Track(0, "video/avc", 1_000_000, 0, 1, 1, 0, 0, "0".repeat(64))
        return LocalVideoTracks.Plan(LocalVideoTracks.Source(verified, listOf(track)))
    }
    @Test fun cancellationStopsBeforeCreatingMuxerOrWritingOutput() {
        val plan = plan()
        RandomAccessFile(folder.newFile(), "rw").use { output ->
            val error = assertThrows(LocalVideoFile.Unavailable::class.java) { LocalVideoRemuxer.write(plan, output.fd) { false } }
            assertEquals(LocalVideoFile.Failure.CHANGED, error.reason); assertEquals(0L, output.length())
        }
    }
    @Test fun modifiedInputStopsBeforeCreatingMuxerOrWritingOutput() {
        val plan = plan(); plan.video.file.file.appendBytes(byteArrayOf(0))
        RandomAccessFile(folder.newFile(), "rw").use { output ->
            val error = assertThrows(LocalVideoFile.Unavailable::class.java) { LocalVideoRemuxer.write(plan, output.fd) { true } }
            assertEquals(LocalVideoFile.Failure.CHANGED, error.reason); assertEquals(0L, output.length())
        }
    }
}
