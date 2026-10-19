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
class PollTranslationQueueTest {
    private class Host {
        val requests = mutableListOf<Pair<String, (String?) -> Unit>>()
        val displayed = mutableMapOf<Pair<Any, Int>, String>()
        var disposed = 0
        val queue = PollTranslationQueue({ text, callback ->
            requests += text to callback
            AutoCloseable { disposed++ }
        }, { key, index, text -> displayed[key to index] = text })
    }
    private fun advance(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun translatesQuestionAndEveryOptionSequentiallyWithoutChangingInputs() {
        val host = Host()
        val key = Any()
        val texts = listOf("Which one?", "First", "Second")
        host.queue.bind(key, texts)
        advance()
        assertEquals(listOf("Which one?"), host.requests.map { it.first })
        listOf("选哪个？", "第一个", "第二个").forEachIndexed { index, translated ->
            host.requests[index].second(translated)
            advance(250)
            assertEquals(translated, host.displayed[key to index])
        }
        assertEquals(listOf("Which one?", "First", "Second"), texts)
        assertEquals(texts, host.requests.map { it.first })
        host.queue.close()
    }

    @Test fun reusedCellRejectsLateTranslationAndDropsOldOptions() {
        val host = Host()
        val key = Any()
        host.queue.bind(key, listOf("Old question", "Old option"))
        advance()
        host.queue.bind(key, listOf("New question", "New option"))
        advance()
        host.requests[0].second("旧题")
        advance()
        assertTrue(host.displayed.isEmpty())
        host.requests[1].second("新题")
        advance(250)
        assertEquals("新题", host.displayed[key to 0])
        assertEquals(listOf("Old question", "New question", "New option"), host.requests.map { it.first })
        host.queue.close()
    }

    @Test fun disablingRestoresOriginalAndCancelsInflightThenReusesCachedTitle() {
        val host = Host()
        val key = Any()
        host.queue.bind(key, listOf("Question", "Option"))
        advance()
        host.requests[0].second("题目")
        advance(250)
        host.queue.setEnabled(false)
        assertEquals("Question", host.displayed[key to 0])
        assertEquals("Option", host.displayed[key to 1])
        host.requests[1].second("迟到选项")
        advance(1000)
        assertEquals("Option", host.displayed[key to 1])
        host.queue.setEnabled(true)
        advance()
        assertEquals("题目", host.displayed[key to 0])
        assertEquals(listOf("Question", "Option", "Option"), host.requests.map { it.first })
        host.queue.close()
    }

    @Test fun recyclingCancelsRequestsAndDoesNotTouchAnotherCell() {
        val host = Host()
        val first = Any()
        val second = Any()
        host.queue.bind(first, listOf("First poll", "Unused option"))
        host.queue.bind(second, listOf("Second poll"))
        advance()
        host.queue.unbind(first)
        advance()
        host.requests[0].second("已回收")
        host.requests[1].second("第二题")
        advance(250)
        assertEquals(mapOf((second to 0) to "第二题"), host.displayed)
        assertEquals(listOf("First poll", "Second poll"), host.requests.map { it.first })
        host.queue.close()
    }

    @Test fun timeoutAndErrorKeepOriginalAndContinueToOtherOptions() {
        val host = Host()
        val key = Any()
        host.queue.bind(key, listOf("Timed out", "Failed", "Success"))
        advance(7000)
        assertEquals(listOf("Timed out", "Failed"), host.requests.map { it.first })
        host.requests[1].second(null)
        advance(1000)
        host.requests[2].second("成功")
        advance(250)
        assertEquals(mapOf((key to 2) to "成功"), host.displayed)
        assertEquals(3, host.disposed)
        host.queue.close()
    }

    @Test fun synchronousHostResultAndDisplayFailureDoNotCrashOrKeepSendingForBrokenCell() {
        val requests = mutableListOf<String>()
        var disposed = 0
        val queue = PollTranslationQueue({ text, callback ->
            requests += text
            callback("译文")
            AutoCloseable { disposed++ }
        }, { _, _, _ -> throw IllegalStateException("detached host widget") })
        val key = Any()
        queue.bind(key, listOf("Question", "Option"))
        advance(1000)
        assertEquals(listOf("Question"), requests)
        assertEquals(1, disposed)
        queue.close()
    }
}
