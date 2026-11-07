package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class ManualCommentTranslationTest {
    private class Clock {
        private data class Task(val call: Runnable, val due: Long)
        private val pending = mutableListOf<Task>()
        private var now = 0L
        fun post(call: Runnable, delay: Long) { pending.add(Task(call, now + delay)) }
        fun remove(call: Runnable) { pending.removeAll { it.call === call } }
        fun advance(milliseconds: Long = 0) {
            val until = now + milliseconds
            var count = 0
            while (true) {
                val next = pending.minByOrNull { it.due } ?: break
                if (next.due > until) break
                check(++count < 1000)
                pending.remove(next); now = next.due; next.call.run()
            }
            now = until
        }
        val size: Int get() = pending.size
    }
    private class Host {
        val clock = Clock()
        val requests = mutableListOf<Pair<String, (String?) -> Unit>>()
        val states = mutableListOf<ManualCommentTranslation.State>()
        var closedSubscriptions = 0
        val session = ManualCommentTranslation({ text, complete ->
            requests.add(text to complete)
            AutoCloseable { closedSubscriptions++ }
        }, clock::post, clock::remove, states::add)
    }

    @Test fun sourceTranslationAndBilingualArePerDialogWithoutChangingOriginalText() {
        val host = Host()
        host.session.start("Original comment")
        host.clock.advance()
        assertEquals(listOf("Original comment"), host.requests.map { it.first })
        host.requests.single().second("评论译文")
        host.clock.advance()
        val result = host.session.snapshot()
        assertEquals("Original comment", result.text(ManualCommentTranslation.Mode.ORIGINAL))
        assertEquals("评论译文", result.text(ManualCommentTranslation.Mode.TRANSLATED))
        assertEquals("Original comment\n\n评论译文", result.text(ManualCommentTranslation.Mode.BILINGUAL))
        assertEquals(1, result.completed)
        assertEquals(0, result.failed)
        assertFalse(result.busy)
        host.session.close()
        assertEquals(0, host.clock.size)
    }

    @Test fun failedSegmentsRetainSourceAndRetryDoesNotRepeatSuccessfulSegments() {
        val host = Host()
        val source = "A".repeat(750)
        val chunks = ChunkedDescriptionTranslation.split(source)
        assertEquals(2, chunks.size)
        host.session.start(source); host.clock.advance()
        host.requests[0].second(null); host.clock.advance(500)
        assertEquals(chunks, host.requests.map { it.first })
        host.requests[1].second("第二段译文"); host.clock.advance(100)
        val failed = host.session.snapshot()
        assertEquals(1, failed.failed)
        assertEquals(chunks[0] + "\n第二段译文", failed.text(ManualCommentTranslation.Mode.TRANSLATED))
        assertEquals(source, failed.original)
        assertFalse(failed.busy)
        host.session.retryFailed(); host.clock.advance()
        assertEquals(listOf(chunks[0], chunks[1], chunks[0]), host.requests.map { it.first })
        host.requests[2].second("第一段译文"); host.clock.advance()
        assertEquals("第一段译文\n第二段译文", host.session.snapshot().translated)
        assertEquals(0, host.session.snapshot().failed)
        host.session.close()
    }

    @Test fun timeoutReleasesAStalledRequestAndExplicitRetryCanRecover() {
        val host = Host()
        host.session.start("hello"); host.clock.advance(6000)
        assertEquals(1, host.closedSubscriptions)
        assertEquals(1, host.session.snapshot().failed)
        assertFalse(host.session.snapshot().busy)
        host.session.retryFailed(); host.clock.advance()
        assertEquals(2, host.requests.size)
        host.requests[1].second("你好"); host.clock.advance()
        host.requests[0].second("迟到旧译文"); host.clock.advance()
        assertEquals("你好", host.session.snapshot().translated)
        host.session.close()
    }

    @Test fun closeAndRestartDiscardLateOrDuplicateCallbacksAndQueuedWork() {
        val host = Host()
        host.session.start("old"); host.clock.advance()
        host.session.start("new"); host.clock.advance()
        host.requests[0].second("旧译文"); host.clock.advance()
        assertNull(host.session.snapshot().translated)
        host.requests[1].second("新译文")
        host.requests[1].second("重复的错误译文")
        host.clock.advance()
        assertEquals("新译文", host.session.snapshot().translated)
        host.session.close()
        val notifications = host.states.size
        host.requests[1].second("关闭后译文"); host.clock.advance(10000)
        assertEquals(notifications, host.states.size)
        assertEquals(0, host.clock.size)
        assertEquals("", host.session.snapshot().original)
    }

    @Test fun explicitEmojiOrNumericCommentsAreNotSilentlyFilteredAndSurrogatePairsAreNotSplit() {
        val host = Host()
        val noWords = "123 🌍 !!"
        host.session.start(noWords); host.clock.advance()
        assertEquals(listOf(noWords), host.requests.map { it.first })
        host.requests.single().second(noWords); host.clock.advance()
        assertEquals(noWords, host.session.snapshot().text(ManualCommentTranslation.Mode.TRANSLATED))
        assertFalse(host.session.snapshot().busy)
        val text = "A".repeat(699) + "🌍" + "B".repeat(800)
        val chunks = ChunkedDescriptionTranslation.split(text)
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 700 && !Character.isHighSurrogate(it.last()) && !Character.isLowSurrogate(it.first()) })
        assertThrows(IllegalArgumentException::class.java) { host.session.start("") }
        assertThrows(IllegalArgumentException::class.java) { host.session.start("x".repeat(ManualCommentTranslation.MAX_SOURCE_LENGTH + 1)) }
        host.session.close()
    }

    @Test fun reentrantObserversCannotSendOrRequeueWorkAfterClose() {
        for (closeAt in listOf("start", "request", "complete")) {
            val clock = Clock()
            var requests = 0
            var callback: ((String?) -> Unit)? = null
            lateinit var session: ManualCommentTranslation
            session = ManualCommentTranslation({ _, done ->
                requests++; callback = done; AutoCloseable { }
            }, clock::post, clock::remove, { state ->
                if (closeAt == "start" && state.busy && requests == 0 && clock.size == 0 ||
                    closeAt == "request" && state.busy && clock.size > 0 ||
                    closeAt == "complete" && !state.busy) session.close()
            })
            session.start("comment")
            clock.advance()
            if (closeAt == "complete") { callback!!("译文"); clock.advance() }
            assertEquals(closeAt, if (closeAt == "complete") 1 else 0, requests)
            assertEquals(closeAt, 0, clock.size)
            assertEquals("", session.snapshot().original)
        }
    }

    @Test fun reentrantRestartAtRequestBoundaryUsesOnlyTheNewSource() {
        val clock = Clock()
        val requests = mutableListOf<String>()
        var restarted = false
        lateinit var session: ManualCommentTranslation
        session = ManualCommentTranslation({ text, _ ->
            requests += text; AutoCloseable { }
        }, clock::post, clock::remove, { state ->
            if (!restarted && state.original == "old" && clock.size > 0) {
                restarted = true; session.start("new")
            }
        })
        session.start("old"); clock.advance()
        assertEquals(listOf("new"), requests)
        assertEquals("new", session.snapshot().original)
        session.close()
        assertEquals(0, clock.size)
    }

    @Test fun menuReturnScopeIsSharedNestedAndRestoresAfterFailures() {
        assertFalse(CommentNativeMenuScope.active)
        CommentNativeMenuScope.native {
            assertTrue(CommentNativeMenuScope.active)
            assertThrows(IllegalStateException::class.java) { CommentNativeMenuScope.native<Unit> { error("native menu error") } }
            assertTrue(CommentNativeMenuScope.active)
        }
        assertFalse(CommentNativeMenuScope.active)
    }

    class Comment { fun getText(): String = "native source" }
    class Invalid { fun getText(): Int = 1 }
    @Test fun originalBodyContractAndIndependentFeatureDependenciesAreStrict() {
        assertEquals("native source", ManualCommentTranslateHook.textGetter(Comment::class.java).invoke(Comment()))
        assertThrows(IllegalStateException::class.java) { ManualCommentTranslateHook.textGetter(Invalid::class.java) }
        assertFalse(ConfigSchema.booleanDefault(ManualCommentTranslateHook.KEY))
        val request = ConfigSnapshot(mapOf(ManualCommentTranslateHook.KEY to true))
        ConfigSchema.validate(request.configuration())
        val plan = HostFeaturePlan(request)
        assertEquals(setOf("ManualCommentTranslateHook"), plan.features)
        assertEquals(setOf(HostSymbol.COMMENT_COPY, HostSymbol.COMMENT_TRANSLATION), plan.symbols)
        val late = HostFeaturePlan(ConfigSnapshot(emptyMap()))
        assertEquals(listOf("ManualCommentTranslateHook"), late.liveCandidates(request))
        assertFalse(late.apply(request).boolean(ManualCommentTranslateHook.KEY))
        assertTrue(late.admitLive("ManualCommentTranslateHook"))
        assertTrue(late.apply(request).boolean(ManualCommentTranslateHook.KEY))
        assertFalse(late.apply(request).boolean("comment_translate_enabled"))
    }

    class TextWithoutIdentity(private val body: String) {
        fun getText(): String = body
        fun getCid(): String? = throw AssertionError("Per-dialog translation must not require a comment ID")
    }
    @Test fun readableCommentWithoutIdIsEligibleForExplicitPerDialogTranslation() {
        val getter = ManualCommentTranslateHook.textGetter(TextWithoutIdentity::class.java)
        val comment = TextWithoutIdentity("an anonymous or not-yet-persisted comment")
        assertEquals(comment.getText(), ManualCommentTranslateHook.sourceText(comment, getter))
        assertNull(ManualCommentTranslateHook.sourceText(TextWithoutIdentity("   "), getter))
        assertNull(ManualCommentTranslateHook.sourceText(TextWithoutIdentity("x".repeat(ManualCommentTranslation.MAX_SOURCE_LENGTH + 1)), getter))
    }
    @Test fun backgroundBudgetDoesNotDiscardAValidResponseAfterSixSeconds() {
        val clock=Clock();var callback:((String?)->Unit)?=null;var disposed=0
        val task=ManualCommentTranslation({ _,done -> callback=done;AutoCloseable{disposed++} },clock::post,clock::remove,{},timeoutMs=20_000)
        task.start("background comment");clock.advance();clock.advance(6500)
        assertTrue(task.snapshot().busy);assertEquals(0,disposed)
        callback!!("后台评论译文");clock.advance()
        assertFalse(task.snapshot().busy);assertEquals(0,task.snapshot().failed)
        assertEquals("后台评论译文",task.snapshot().translated);assertEquals(1,disposed)
        task.close()
    }
    @Test fun backgroundBudgetStillTimesOutAndCannotAcceptAnExpiredResult() {
        val clock=Clock();var callback:((String?)->Unit)?=null;var disposed=0
        val task=ManualCommentTranslation({ _,done -> callback=done;AutoCloseable{disposed++} },clock::post,clock::remove,{},timeoutMs=20_000)
        task.start("stalled background comment");clock.advance();clock.advance(20_000)
        assertEquals(1,task.snapshot().failed);assertEquals(1,disposed)
        callback!!("too late");clock.advance();assertNull(task.snapshot().translated)
        task.close()
    }

}
