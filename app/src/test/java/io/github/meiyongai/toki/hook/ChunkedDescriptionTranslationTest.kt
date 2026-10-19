package io.github.meiyongai.toki.subtitle.hook

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ChunkedDescriptionTranslationTest {
    private fun advance(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun splitsLongTextWithoutDroppingCharactersOrBreakingSurrogatePairs() {
        val source = "A".repeat(699) + "🎉" + " Long Russian description. ".repeat(150)
        val chunks = ChunkedDescriptionTranslation.split(source)
        assertEquals(source, chunks.joinToString(""))
        assertTrue(chunks.size > 2)
        chunks.forEach {
            assertTrue(it.length <= 700)
            assertFalse(Character.isHighSurrogate(it.last()))
            assertFalse(Character.isLowSurrogate(it.first()))
        }
    }

    @Test fun publishesCompletedChunksWithOriginalForPendingSegmentsAndPreservesOrder() {
        val requests = mutableListOf<Pair<String, (String?) -> Unit>>()
        var ready = 0
        val translator = ChunkedDescriptionTranslation({ text, callback ->
            requests += text to callback
            AutoCloseable { }
        }, { ready++ })
        val key = Any()
        val source = "A".repeat(750)
        translator.request(key, source)
        advance()
        requests[0].second("前半部分")
        advance(250)
        assertEquals("前半部分\n" + "A".repeat(50), translator.result(key, source))
        assertEquals(1, ready)
        requests[1].second("后半部分")
        advance(250)
        assertEquals("前半部分\n后半部分", translator.result(key, source))
        assertEquals(2, ready)
        translator.request(key, source)
        advance(500)
        assertEquals(2, requests.size)
        translator.close()
    }

    @Test fun failedSegmentRetriesAfterOtherSegmentsWithoutHidingTheirTranslations() {
        val requests = mutableListOf<Pair<String, (String?) -> Unit>>()
        val translator = ChunkedDescriptionTranslation({ text, callback ->
            requests += text to callback
            AutoCloseable { }
        }, {})
        val key = Any()
        val source = "A".repeat(750)
        translator.request(key, source)
        advance()
        requests[0].second(null)
        advance(1000)
        requests[1].second("后半")
        advance(250)
        assertEquals("A".repeat(700) + "\n后半", translator.result(key, source))
        assertEquals(3, requests.size)
        requests[2].second("前半")
        advance()
        assertEquals("前半\n后半", translator.result(key, source))
        translator.close()
    }

    @Test fun changingVideoRejectsOldChunkResults() {
        val requests = mutableListOf<Pair<String, (String?) -> Unit>>()
        var ready = 0
        val translator = ChunkedDescriptionTranslation({ text, callback ->
            requests += text to callback
            AutoCloseable { }
        }, { ready++ })
        val key = Any()
        translator.request(key, "Old description")
        advance()
        translator.request(key, "New description")
        advance()
        requests[0].second("旧译文")
        advance()
        assertEquals(0, ready)
        requests[1].second("新译文")
        advance(250)
        assertEquals("新译文", translator.result(key, "New description"))
        assertNull(translator.result(key, "Old description"))
        translator.close()
    }

    @Test fun closeDoesNotPublishOriginalTextAsSuccessfulTranslation() {
        var ready = 0
        val translator = ChunkedDescriptionTranslation({ _, _ -> AutoCloseable { } }, { ready++ })
        val key = Any()
        translator.request(key, "A".repeat(750))
        advance()
        translator.close()
        advance(10000)
        assertEquals(0, ready)
    }
}
