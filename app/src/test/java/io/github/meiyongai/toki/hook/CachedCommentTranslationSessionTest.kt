package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class CachedCommentTranslationSessionTest {
    private class Clock {
        private data class Task(val call: Runnable, val at: Long)
        private val pending = mutableListOf<Task>()
        private var now = 0L
        fun post(call: Runnable, delay: Long) { pending += Task(call, now + delay) }
        fun remove(call: Runnable) { pending.removeAll { it.call === call } }
        fun advance(ms: Long = 0) {
            val until = now + ms
            var n = 0
            while (true) {
                val next = pending.minByOrNull { it.at } ?: break
                if (next.at > until) break
                check(++n < 1000); pending.remove(next); now = next.at; next.call.run()
            }
            now = until
        }
        val size get() = pending.size
    }
    private class Host {
        val clock = Clock()
        var valid = true
        val requests = mutableListOf<Pair<String, (String?) -> Unit>>()
        val states = mutableListOf<ManualCommentTranslation.State>()
        val stored = mutableListOf<String>()
        val transport: (String, (String?) -> Unit) -> AutoCloseable = { text, complete ->
            requests += text to complete; AutoCloseable { }
        }
        val session = CachedCommentTranslationSession(clock::post, clock::remove, { valid },
            { _, _, state -> states += state }, { _, target, text -> stored += target + ":" + text })
    }
    private fun row(source: String = "original", id: String = "comment") = OfflineCommentData.Row(
        id, "author", "handle", "uid", source, null, "", 0, 0)

    @Test fun completeTranslationIsStoredOnceButPartialResultsAreNot() {
        val host = Host()
        assertTrue(host.session.start(row("a".repeat(750)), "zh-hans", host.transport))
        host.clock.advance()
        assertFalse(host.session.start(row(id = "other"), "zh-hans", host.transport))
        host.requests[0].second("第一段"); host.clock.advance(100)
        host.requests[1].second(null); host.clock.advance(500)
        assertTrue(host.stored.isEmpty())
        assertEquals(1, host.states.last().failed)
        val retryRequests = mutableListOf<(String?) -> Unit>()
        assertTrue(host.session.start(row("a".repeat(750)), "zh-hans", { _, done -> retryRequests += done; AutoCloseable { } }))
        host.clock.advance()
        assertEquals(1, retryRequests.size)
        retryRequests.single()("第二段"); host.clock.advance()
        retryRequests.single()("重复错误结果"); host.clock.advance()
        assertEquals(listOf("zh-hans:第一段\n第二段"), host.stored)
        host.session.close()
        assertEquals(0, host.clock.size)
    }
    @Test fun accountOrVideoInvalidationCloseAndRestartDiscardOldResults() {
        val host = Host()
        host.session.start(row(), "zh-hans", host.transport); host.clock.advance()
        host.valid = false
        host.requests[0].second("不应保存"); host.clock.advance()
        assertTrue(host.stored.isEmpty())
        host.session.cancel(); host.valid = true
        host.session.start(row("new", "next"), "fr", host.transport); host.clock.advance()
        host.requests[0].second("更迟的旧结果")
        host.requests[1].second("nouveau"); host.clock.advance()
        assertEquals(listOf("fr:nouveau"), host.stored)
        host.session.close()
        host.requests[1].second("关闭后的结果"); host.clock.advance(10000)
        assertEquals(1, host.stored.size)
    }
    @Test fun unchangedAndTruncatedSourcesAreNotSavedAsNewTranslations() {
        val host = Host()
        assertFalse(host.session.start(row().copy(sourceTruncated = true), "zh-hans", host.transport))
        host.session.start(row(), "zh-hans", host.transport); host.clock.advance()
        host.requests.single().second("original"); host.clock.advance()
        assertTrue(host.stored.isEmpty())
        host.session.close()
    }
    @Test fun reentrantCloseDuringDisplayCannotPersistOrReschedule() {
        val clock = Clock()
        var saved = false
        var done: ((String?) -> Unit)? = null
        lateinit var session: CachedCommentTranslationSession
        session = CachedCommentTranslationSession(clock::post, clock::remove, { true },
            { _, _, state -> if (!state.busy) session.close() }, { _, _, _ -> saved = true })
        session.start(row(), "en", { _, callback -> done = callback; AutoCloseable { } }); clock.advance()
        done!!("translated"); clock.advance()
        assertFalse(saved)
        assertEquals(0, clock.size)
    }
    @Test fun optionalOnlineFeaturePreparesTranslationWithoutRequiringCommentCopyOrOfflineControls() {
        val key = CachedCommentTranslateHook.KEY
        assertFalse(ConfigSchema.booleanDefault(key))
        val request = ConfigSnapshot(mapOf(key to true))
        ConfigSchema.validate(request.configuration())
        val plan = HostFeaturePlan(request)
        assertEquals(setOf("CachedCommentTranslateHook"), plan.features)
        assertEquals(setOf(HostSymbol.COMMENT_TRANSLATION), plan.symbols)
        assertTrue(plan.directHostFeatures.isEmpty())
        val late = HostFeaturePlan(ConfigSnapshot(emptyMap()))
        assertEquals(listOf("CachedCommentTranslateHook"), late.liveCandidates(request))
        assertFalse(late.apply(request).boolean(key))
        assertTrue(late.admitLive("CachedCommentTranslateHook"))
        assertTrue(late.apply(request).boolean(key))
        assertFalse(late.apply(request).boolean(OfflineVideoHook.KEY))
    }
}
