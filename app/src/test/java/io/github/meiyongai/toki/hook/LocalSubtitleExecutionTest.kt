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
class LocalSubtitleExecutionTest {
    private class Host {
        var online = true
        var disposed = 0
        val requests = mutableListOf<Pair<String, (String?) -> Unit>>()
        val results = mutableListOf<String>()
        val events = mutableListOf<String>()
        val translator = NativeSubtitleTranslator({ true }, { text, done ->
            requests += text to done
            AutoCloseable { disposed++ }
        }, events::add, access = { LocalOnlyRequest { online } })
        fun request(text: String) = translator.request("video", text, { true }, results::add)
    }
    private fun advance(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun savedTranslationsRemainAvailableWithoutAnyRemoteFallback() {
        val host = Host()
        host.request("source"); advance()
        host.requests.single().second("已保存译文"); advance()
        host.translator.cancel()
        host.online = false
        host.request("source")
        host.request("missing translation")
        host.translator.prefetch(listOf("uncached ahead")) { true }
        advance(10000)
        assertEquals(listOf("已保存译文", "已保存译文"), host.results)
        assertEquals(1, host.requests.size)
        assertTrue(host.events.any { "no local engine" in it })
        host.online = true
        advance(10000)
        assertEquals(1, host.requests.size)
        host.translator.close()
    }

    @Test fun revokedQueuedRequestsDoNotResumeAfterConnectivityReturns() {
        val host = Host()
        host.request("queued source")
        host.online = false
        advance()
        assertTrue(host.requests.isEmpty())
        host.online = true
        advance(10000)
        assertTrue(host.requests.isEmpty())
        host.request("new explicit request"); advance()
        assertEquals(listOf("new explicit request"), host.requests.map { it.first })
        host.translator.close()
    }

    @Test fun reconnectCreatesANewRequestInsteadOfReusingAnIrrevocablyDeniedSubscription() {
        var online = true
        val tickets = mutableListOf<LocalOnlyRequest>()
        val requests = mutableListOf<(String?) -> Unit>()
        val results = mutableListOf<String>()
        var disposed = 0
        val translator = NativeSubtitleTranslator({ true }, { _, done ->
            requests += done; AutoCloseable { disposed++ }
        }, access = { LocalOnlyRequest { online }.also(tickets::add) })
        translator.request("same cue", { true }, results::add); advance()
        online = false
        assertFalse(tickets.first().permitsNetwork())
        online = true
        translator.request("same cue", { true }, results::add); advance(250)
        assertEquals(2, requests.size)
        assertEquals(1, disposed)
        requests[0]("旧订阅译文")
        requests[1]("重连后的译文")
        advance()
        assertEquals(listOf("重连后的译文"), results)
        translator.close()
    }

    @Test fun revokedActiveResultIsNotCachedDisplayedOrRetried() {
        val host = Host()
        host.request("active source"); advance()
        host.online = false
        host.requests.single().second("不应显示的迟到在线结果")
        advance(10000)
        assertTrue(host.results.isEmpty())
        assertEquals(1, host.disposed)
        host.online = true
        host.request("active source"); advance()
        assertEquals(2, host.requests.size)
        host.requests[1].second("新请求译文"); advance()
        assertEquals(listOf("新请求译文"), host.results)
        host.translator.close()
    }
}
