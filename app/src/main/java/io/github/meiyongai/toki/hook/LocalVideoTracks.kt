package io.github.meiyongai.toki.subtitle.hook

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Validate every encoded sample from local descriptors; neither remote URI resolution nor DRM extraction is allowed. */
internal object LocalVideoTracks {
    const val MAX_SAMPLE = 16 * 1024 * 1024
    const val MAX_SAMPLES = 2_000_000L
    data class Track(val index: Int, val mime: String, val durationUs: Long, val rotation: Int,
        val count: Long, val bytes: Long, val firstUs: Long, val lastUs: Long, val fingerprint: String, val minimumUs: Long = 0)
    data class Source(val file: LocalVideoFile.Verified, val tracks: List<Track>)
    data class Plan(val video: Source, val audio: Source? = null, val expectedDurationMs: Int = 0) {
        val tracks: List<Pair<Source, Track>> get() = listOf(video).plus(listOfNotNull(audio)).flatMap { source -> source.tracks.map { source to it } }
        fun verify(active: () -> Boolean) { LocalVideoFile.verify(video.file, active); audio?.let { LocalVideoFile.verify(it.file, active) } }
    }
    class Digest(private val minimumUs: Long = 0) {
        private val hash = MessageDigest.getInstance("SHA-256")
        private val scratch = ByteArray(64 * 1024)
        var count = 0L; private set
        var bytes = 0L; private set
        var firstUs = Long.MAX_VALUE; private set
        var lastUs = Long.MIN_VALUE; private set
        fun sample(buffer: ByteBuffer, size: Int, time: Long) {
            require(size in 1..MAX_SAMPLE && time >= minimumUs)
            check(++count <= MAX_SAMPLES)
            bytes += size; firstUs = minOf(firstUs, time); lastUs = maxOf(lastUs, time)
            // Include sample boundaries; concatenated payload equality alone does not establish equivalent packetization.
            repeat(4) { hash.update((size ushr (8 * it)).toByte()) }
            val read = buffer.duplicate().apply { position(0); limit(size) }
            while (read.hasRemaining()) {
                val n = minOf(read.remaining(), scratch.size); read.get(scratch, 0, n); hash.update(scratch, 0, n)
            }
        }
        fun finish(): String = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun supportedVideo(mime: String): Boolean = mime in setOf("video/avc", "video/hevc", "video/mp4v-es", "video/3gpp", "video/av01")
    fun supportedAudio(mime: String): Boolean = mime == "audio/mp4a-latm"
    /** Negative AAC priming timestamps are valid only within the declared encoder delay. */
    fun minimumSampleTime(mime: String, sampleRate: Int, delaySamples: Int): Long {
        if (mime != "audio/mp4a-latm" || delaySamples == 0) return 0
        if (sampleRate !in 1..384_000 || delaySamples < 0 || delaySamples.toLong() > sampleRate.toLong() * 2)
            throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.TIMELINE)
        return -((delaySamples.toLong() * 1_000_000 + sampleRate - 1) / sampleRate)
    }
    fun minimumSampleTime(format: MediaFormat): Long {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: unavailable()
        val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 0
        val delay = if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) format.getInteger(MediaFormat.KEY_ENCODER_DELAY) else 0
        return minimumSampleTime(mime, rate, delay)
    }
    fun inspect(file: LocalVideoFile.Verified, active: () -> Boolean): Source {
        val formats = withExtractor(file, active) { extractor ->
            if (!extractor.psshInfo.isNullOrEmpty() || extractor.trackCount !in 1..8) unavailable()
            (0 until extractor.trackCount).map { index -> index to extractor.getTrackFormat(index) }
        }
        val media = formats.filter { (_, format) -> format.getString(MediaFormat.KEY_MIME)?.let { it.startsWith("video/") || it.startsWith("audio/") } == true }
        if (media.isEmpty() || media.size > 2 || media.count { it.second.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } > 1 ||
            media.count { it.second.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } > 1) unavailable()
        val tracks = media.map { (index, format) ->
            val mime = format.getString(MediaFormat.KEY_MIME) ?: unavailable()
            if (!supportedVideo(mime) && !supportedAudio(mime)) unavailable()
            if (format.containsKey("is-encrypted") && format.getInteger("is-encrypted") != 0) unavailable()
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (duration < 0 || duration > 24L * 60 * 60 * 1_000_000) unavailable()
            val rotation = if (mime.startsWith("video/") && format.containsKey("rotation-degrees")) format.getInteger("rotation-degrees") else 0
            if (rotation !in setOf(0, 90, 180, 270)) unavailable()
            val minimumUs = minimumSampleTime(format)
            val summary = Digest(minimumUs)
            withExtractor(file, active) { extractor ->
                extractor.selectTrack(index)
                var buffer = ByteBuffer.allocateDirect(1024 * 1024)
                while (extractor.sampleTrackIndex >= 0) {
                    LocalVideoFile.verify(file, active)
                    val size = checkedSampleSize(extractor, minimumUs)
                    if (summary.count == 0L && mime.startsWith("video/") && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0)
                        throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.INCOMPLETE)
                    if (buffer.capacity() < size) buffer = ByteBuffer.allocateDirect(size)
                    buffer.clear()
                    val count = extractor.readSampleData(buffer, 0)
                    if (count != size) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.INCOMPLETE)
                    summary.sample(buffer, count, extractor.sampleTime)
                    if (!extractor.advance()) break
                }
            }
            if (summary.count == 0L || summary.bytes <= 0 || summary.bytes > file.size) unavailable()
            Track(index, mime, duration, rotation, summary.count, summary.bytes, summary.firstUs, summary.lastUs, summary.finish(), minimumUs)
        }
        return Source(file, tracks)
    }
    fun plan(video: Source, audio: Source?, needsAudio: Boolean, expectedMs: Int): Plan {
        if (video.tracks.count { it.mime.startsWith("video/") } != 1) unavailable()
        val nativeAudio = video.tracks.any { it.mime.startsWith("audio/") }
        if (audio != null && (nativeAudio || audio.tracks.size != 1 || !audio.tracks.single().mime.startsWith("audio/") || audio.file.file == video.file.file)) unavailable()
        if (needsAudio && !nativeAudio && audio == null) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.INCOMPLETE)
        val plan = Plan(video, audio, expectedMs)
        val v = video.tracks.single { it.mime.startsWith("video/") }
        // Require measured sample coverage, not just a duration header which may describe uncached data.
        val expectedUs = expectedMs.toLong() * 1000
        if (expectedMs > 0 && v.lastUs - v.firstUs + 500_000L < expectedUs) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.INCOMPLETE)
        val sound = plan.tracks.map { it.second }.firstOrNull { it.mime.startsWith("audio/") }
        if (sound != null && (kotlin.math.abs(sound.firstUs - v.firstUs) > 1_000_000L ||
            kotlin.math.abs(sound.lastUs - v.lastUs) > maxOf(1_000_000L, (v.lastUs - v.firstUs) / 20)))
            throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.INCOMPLETE)
        return plan
    }
    fun checkedSampleSize(extractor: MediaExtractor, minimumUs: Long = 0): Int {
        if (extractor.sampleFlags and (MediaExtractor.SAMPLE_FLAG_ENCRYPTED or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) unavailable()
        val size = extractor.sampleSize
        if (size !in 1..MAX_SAMPLE.toLong()) unavailable()
        if (extractor.sampleTime < minimumUs) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.TIMELINE)
        return size.toInt()
    }
    fun <T> withDescriptor(descriptor: FileDescriptor, size: Long, action: (MediaExtractor) -> T): T {
        val extractor = MediaExtractor()
        try { extractor.setDataSource(descriptor, 0, size); return action(extractor) } finally { extractor.release() }
    }
    fun <T> withExtractor(file: LocalVideoFile.Verified, active: () -> Boolean, action: (MediaExtractor) -> T): T {
        LocalVideoFile.verify(file, active)
        file.restoration?.verify(true)
        val result = if (file.restoration == null) {
            FileInputStream(file.file).use { input -> withDescriptor(input.fd, file.size, action) }
        } else OfflineMdlDataSource(file, active).use { input ->
            val extractor = MediaExtractor()
            try { extractor.setDataSource(input); action(extractor) } finally { extractor.release() }
        }
        file.restoration?.verify(true)
        LocalVideoFile.verify(file, active); return result
    }
    fun unavailable(): Nothing = throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.UNSUPPORTED)
}
