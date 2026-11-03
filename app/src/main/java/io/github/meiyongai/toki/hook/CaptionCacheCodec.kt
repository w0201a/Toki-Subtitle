package io.github.meiyongai.toki.subtitle.hook

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Length-prefixed, bounded storage: caption newlines and control characters are data, not delimiters. */
internal object CaptionCacheCodec {
    private const val MAGIC = 0x544f4b32
    private const val VERSION = 1
    internal const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_VIDEOS = 106
    private const val MAX_CUES = 4000
    private const val MAX_TEXT_BYTES = 16000

    fun encode(videos: Map<String, VideoCaptionCache.Video>): ByteArray {
        require(videos.size <= MAX_VIDEOS)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(videos.size)
            videos.forEach { (id, video) ->
                require(id.isNotBlank() && id.length <= 256 && video.track.length <= 256)
                out.text(id)
                out.text(video.track)
                val expected = video.expected
                require(expected == null || expected.size <= MAX_CUES)
                out.writeInt(expected?.size ?: -1)
                expected?.forEach { out.text(it); require(bytes.size() <= MAX_BYTES) }
                require(video.values.size <= MAX_CUES)
                out.writeInt(video.values.size)
                video.values.forEach { (source, translated) ->
                    out.text(source); out.text(translated)
                    require(bytes.size() <= MAX_BYTES)
                }
                require(bytes.size() <= MAX_BYTES)
            }
        }
        return bytes.toByteArray()
    }

    /** Reject the complete archive on corruption, unsupported versions, duplicate records or oversize input. */
    fun decode(bytes: ByteArray): Map<String, VideoCaptionCache.Video> {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == MAGIC && input.readInt() == VERSION)
            val count = input.readInt().also { require(it in 0..MAX_VIDEOS) }
            val videos = linkedMapOf<String, VideoCaptionCache.Video>()
            repeat(count) {
                val id = input.text().also { require(it.isNotBlank() && it.length <= 256 && it !in videos) }
                val track = input.text().also { require(it.length <= 256) }
                val expectedSize = input.readInt().also { require(it in -1..MAX_CUES) }
                val expected = if (expectedSize < 0) null else linkedSetOf<String>().apply {
                    repeat(expectedSize) { require(add(input.text())) }
                }
                val valuesSize = input.readInt().also { require(it in 0..MAX_CUES) }
                val values = linkedMapOf<String, String>()
                repeat(valuesSize) {
                    val source = input.text()
                    require(source !in values)
                    values[source] = input.text()
                }
                videos[id] = VideoCaptionCache.Video(track, values, expected)
            }
            require(input.available() == 0)
            videos
        }
    }

    private fun DataOutputStream.text(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TEXT_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.text(): String {
        val size = readInt()
        require(size in 0..MAX_TEXT_BYTES && size <= available())
        val bytes = ByteArray(size)
        readFully(bytes)
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}
