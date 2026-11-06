package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CommentResponseTranslationQueueTest {
    private class Clock {
        private var time = 0L
        private val tasks = mutableMapOf<Runnable, Long>()
        fun post(task: Runnable, delay: Long) { tasks[task] = time + delay }
        fun remove(task: Runnable) { tasks.remove(task) }
        fun advance(delta: Long = 0) {
            val end = time + delta
            var count = 0
            while (true) {
                val next = tasks.minByOrNull { it.value } ?: break
                if (next.value > end) break
                check(++count < 10000); time = next.value; tasks.remove(next.key); next.key.run()
            }
            time = end
        }
    }
    @Test fun everyLoadedRowAndReplyIsTranslatedWithoutAnyViewBinding() {
        val clock = Clock(); val requests = mutableListOf<String>(); val results = mutableMapOf<Int, String>()
        val queue = CommentResponseTranslationQueue<Int>({ text, done -> requests += text; done("译文 $text"); AutoCloseable { } },
            clock::post, clock::remove, { row, text -> results[row] = text })
        repeat(75) { assertTrue(queue.offer("row$it", it, "source$it") { true }) }
        clock.advance(20_000)
        assertEquals(75, requests.size); assertEquals(75, results.size)
        assertEquals("译文 source74", results[74]); assertEquals(0, queue.queued)
        queue.close()
    }
    @Test fun accountOrSettingChangeRejectsLateResultsAndDoesNotStartPendingRows() {
        val clock = Clock(); var allowed = true; var done: ((String?) -> Unit)? = null
        val results = mutableListOf<String>(); var requests = 0
        val queue = CommentResponseTranslationQueue<Int>({ _, complete -> requests++; done = complete; AutoCloseable { } },
            clock::post, clock::remove, { _, text -> results += text })
        queue.offer("first", 1, "source") { allowed }; queue.offer("second", 2, "next") { allowed }
        clock.advance(); assertEquals(1, requests)
        allowed = false; done!!("old account")
        clock.advance(1000)
        assertTrue(results.isEmpty()); assertEquals(1, requests)
        queue.close()
    }
    @Test fun duplicateResponseRowsShareWorkAndQueueIsBounded() {
        val clock = Clock(); var requests = 0
        val queue = CommentResponseTranslationQueue<Int>({ _, _ -> requests++; AutoCloseable { } },
            clock::post, clock::remove, { _, _ -> }, capacity = 2)
        assertTrue(queue.offer("one", 1, "one") { true })
        assertTrue(queue.offer("one", 1, "one") { true })
        assertTrue(queue.offer("two", 2, "two") { true })
        assertFalse(queue.offer("three", 3, "three") { true })
        clock.advance(); assertEquals(1, requests)
        queue.cancel(); assertEquals(0, queue.queued)
    }
}
