package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CaptionCacheCodecTest {
    @Test fun multilineCaptionsAndControlCharactersRoundTripExactly() {
        val source = "first line\nsecond line\u0001V:not-a-record"
        val translation = "你好\n世界\u0001C:not-a-record 🌏"
        val original = mapOf("video" to VideoCaptionCache.Video("track",
            linkedMapOf(source to translation), linkedSetOf(source)))
        val saved = CaptionCacheCodec.decode(CaptionCacheCodec.encode(original))
        assertEquals("track", saved.getValue("video").track)
        assertEquals(translation, saved.getValue("video").values[source])
        assertEquals(setOf(source), saved.getValue("video").expected)
    }

    @Test fun unknownExpectedTrackAndEmptyArchiveRemainDistinct() {
        val original = mapOf("video" to VideoCaptionCache.Video("track", linkedMapOf("a" to "b")))
        val saved = CaptionCacheCodec.decode(CaptionCacheCodec.encode(original))
        assertNull(saved.getValue("video").expected)
        assertTrue(CaptionCacheCodec.decode(CaptionCacheCodec.encode(emptyMap())).isEmpty())
    }

    @Test fun truncatedUnsupportedAndTrailingDataAreRejected() {
        val bytes = CaptionCacheCodec.encode(mapOf("video" to VideoCaptionCache.Video("track")))
        for (size in bytes.indices) rejects { CaptionCacheCodec.decode(bytes.copyOf(size)) }
        rejects { CaptionCacheCodec.decode(bytes + byteArrayOf(0)) }
        val future = bytes.copyOf().apply { this[7] = 2 }
        rejects { CaptionCacheCodec.decode(future) }
    }

    @Test fun oversizedArchivesAndTextAreRejectedBeforeRestoration() {
        rejects { CaptionCacheCodec.decode(ByteArray(CaptionCacheCodec.MAX_BYTES + 1)) }
        rejects { CaptionCacheCodec.encode((1..107).associate { "v$it" to VideoCaptionCache.Video("") }) }
        rejects { CaptionCacheCodec.encode(mapOf("v" to VideoCaptionCache.Video("", linkedMapOf("a" to "x".repeat(16001))))) }
    }

    private fun rejects(action: () -> Unit) {
        try { action() } catch (_: Exception) { return }
        fail("Invalid archive was accepted")
    }
}
