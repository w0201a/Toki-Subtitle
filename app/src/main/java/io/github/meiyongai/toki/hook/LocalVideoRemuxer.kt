package io.github.meiyongai.toki.subtitle.hook

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer

/** Repackage already-complete local samples into one playable MP4. Never append files or synthesize a missing track. */
internal object LocalVideoRemuxer {
    private class Reader(val source: LocalVideoTracks.Source, val track: LocalVideoTracks.Track,
        private val active: () -> Boolean) : AutoCloseable {
        private val input = FileInputStream(source.file.file)
        val extractor = MediaExtractor()
        val digest = LocalVideoTracks.Digest()
        var buffer: ByteBuffer = ByteBuffer.allocate(0)
        var outputIndex = -1
        val available: Boolean get() = extractor.sampleTrackIndex >= 0
        val time: Long get() = extractor.sampleTime
        init {
            try {
                LocalVideoFile.verify(source.file, active)
                extractor.setDataSource(input.fd, 0, source.file.size)
                if (!extractor.psshInfo.isNullOrEmpty()) LocalVideoTracks.unavailable()
                val format = extractor.getTrackFormat(track.index)
                if (format.getString(MediaFormat.KEY_MIME) != track.mime) LocalVideoTracks.unavailable()
                extractor.selectTrack(track.index)
            } catch (error: Throwable) {
                try { extractor.release() } finally { input.close() }
                throw error
            }
        }
        fun write(muxer: MediaMuxer, origin: Long) {
            LocalVideoFile.verify(source.file, active)
            val count = LocalVideoTracks.checkedSampleSize(extractor)
            if (buffer.capacity() < count) buffer = ByteBuffer.allocateDirect(count)
            buffer.clear()
            if (extractor.readSampleData(buffer, 0) != count) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.INCOMPLETE)
            val timestamp = extractor.sampleTime
            val flag = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            digest.sample(buffer, count, timestamp)
            val presentation = timestamp - origin
            if (presentation < 0) LocalVideoTracks.unavailable()
            val info = MediaCodec.BufferInfo().apply { set(0, count, presentation, flag) }
            buffer.position(0); buffer.limit(count)
            muxer.writeSampleData(outputIndex, buffer, info)
            extractor.advance()
        }
        fun verify() {
            LocalVideoFile.verify(source.file, active)
            if (digest.count != track.count || digest.bytes != track.bytes || digest.firstUs != track.firstUs ||
                digest.lastUs != track.lastUs || digest.finish() != track.fingerprint)
                throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.CHANGED)
        }
        override fun close() { try { extractor.release() } finally { input.close() } }
    }
    fun write(plan: LocalVideoTracks.Plan, output: FileDescriptor, active: () -> Boolean) {
        plan.verify(active)
        val readers = mutableListOf<Reader>()
        var muxer: MediaMuxer? = null
        var failed: Throwable? = null
        try {
            plan.tracks.forEach { (source, track) -> readers += Reader(source, track, active) }
            val origin = readers.minOf { it.track.firstUs }
            val writer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val rotation = readers.single { it.track.mime.startsWith("video/") }.track.rotation
            writer.setOrientationHint(rotation)
            readers.forEach { reader -> reader.outputIndex = writer.addTrack(reader.extractor.getTrackFormat(reader.track.index)) }
            writer.start()
            while (true) {
                plan.verify(active)
                // Compare each reader's next decode-order sample; do not sort/reorder samples within a track (B-frames).
                val next = readers.filter { it.available }.minByOrNull { it.time } ?: break
                next.write(writer, origin)
            }
            readers.forEach { it.verify() }
            writer.stop() // A failed final MP4 index must never be published as success.
            plan.verify(active)
        } catch (error: Throwable) { failed = error; throw error }
        finally {
            var closing: Throwable? = null
            try { muxer?.release() } catch (error: Throwable) { closing = error }
            readers.forEach { reader -> try { reader.close() } catch (error: Throwable) { if (closing == null) closing = error } }
            val error = closing
            if (error != null) { failed?.addSuppressed(error) ?: throw error }
        }
    }
    /** Independent readback of the finished destination: every expected sample must survive the mux. */
    fun verifyOutput(plan: LocalVideoTracks.Plan, output: FileDescriptor, size: Long, active: () -> Boolean) {
        check(size in 1..16L * 1024 * 1024 * 1024)
        plan.verify(active)
        val expected = plan.tracks.map { it.second }
        val origin = expected.minOf { it.firstUs }
        LocalVideoTracks.withDescriptor(output, size) { extractor ->
            if (!extractor.psshInfo.isNullOrEmpty() || extractor.trackCount != expected.size) LocalVideoTracks.unavailable()
            val remaining = expected.toMutableList()
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val track = remaining.singleOrNull { it.mime == format.getString(MediaFormat.KEY_MIME) } ?: LocalVideoTracks.unavailable()
                remaining.remove(track)
                LocalVideoTracks.withDescriptor(output, size) { samples ->
                    samples.selectTrack(index)
                    var buffer = ByteBuffer.allocateDirect(1024 * 1024)
                    val digest = LocalVideoTracks.Digest()
                    while (samples.sampleTrackIndex >= 0) {
                        plan.verify(active)
                        val count = LocalVideoTracks.checkedSampleSize(samples)
                        if (buffer.capacity() < count) buffer = ByteBuffer.allocateDirect(count)
                        buffer.clear()
                        if (samples.readSampleData(buffer, 0) != count) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.INCOMPLETE)
                        digest.sample(buffer, count, samples.sampleTime)
                        if (!samples.advance()) break
                    }
                    if (digest.count != track.count || digest.bytes != track.bytes || digest.finish() != track.fingerprint ||
                        kotlin.math.abs(digest.firstUs - (track.firstUs - origin)) > 1000 ||
                        kotlin.math.abs(digest.lastUs - (track.lastUs - origin)) > 1000)
                        throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.CHANGED)
                }
                if (track.mime.startsWith("video/")) {
                    val rotation = if (format.containsKey("rotation-degrees")) format.getInteger("rotation-degrees") else 0
                    if (rotation != track.rotation) LocalVideoTracks.unavailable()
                }
            }
            check(remaining.isEmpty())
        }
        plan.verify(active)
    }
}
