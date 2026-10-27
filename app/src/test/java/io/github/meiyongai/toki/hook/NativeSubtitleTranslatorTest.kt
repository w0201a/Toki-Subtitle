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
class NativeSubtitleTranslatorTest {
    private class Host {
        val requests = mutableListOf<Pair<String, (String?) -> Unit>>()
        var disposed = 0
        val translator = NativeSubtitleTranslator({ true }, { text, complete ->
            requests += text to complete
            AutoCloseable { disposed++ }
        })
    }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun slowOldCueCannotReplaceCurrentCueAndQueueKeepsOnlyNewest() {
        val host = Host()
        var cue = "first"
        val delivered = mutableListOf<String>()
        host.translator.request("first", { cue == "first" }, delivered::add)
        advance(0)
        cue = "discarded"
        host.translator.request("discarded", { cue == "discarded" }, delivered::add)
        cue = "latest"
        host.translator.request("latest", { cue == "latest" }, delivered::add)
        advance(0)
        assertEquals(listOf("first"), host.requests.map { it.first })
        host.requests[0].second("旧句译文")
        advance(0)
        assertTrue(delivered.isEmpty())
        advance(500)
        assertEquals(listOf("first", "latest"), host.requests.map { it.first })
        host.requests[1].second("当前译文")
        advance(0)
        assertEquals(listOf("当前译文"), delivered)
        assertEquals(2, host.disposed)
        // 已完成的新句重播命中缓存；取消的旧句不会投递或写入缓存。
        host.translator.request("latest", { true }, delivered::add)
        assertEquals(listOf("当前译文", "当前译文"), delivered)
        assertEquals(2, host.requests.size)
        host.translator.close()
    }

    @Test fun leavingPageBeforeDebounceSendsNothingAndCancelRejectsLateResult() {
        val host = Host()
        var visible = true
        val delivered = mutableListOf<String>()
        host.translator.request("offscreen", { visible }, delivered::add)
        visible = false
        advance(200)
        assertTrue(host.requests.isEmpty())
        host.translator.request("current", { true }, delivered::add)
        advance(0)
        host.translator.cancel()
        host.requests.single().second("迟到译文")
        advance(0)
        assertTrue(delivered.isEmpty())
        assertEquals(1, host.disposed)
        host.translator.close()
    }

    @Test fun stalledServiceTimesOutThenBacksOffWithoutBlockingNewCaptions() {
        val host = Host()
        val delivered = mutableListOf<String>()
        host.translator.request("first", { true }, delivered::add)
        advance(0)
        host.translator.request("second", { true }, delivered::add)
        advance(6000)
        assertEquals(1, host.disposed)
        assertTrue(delivered.isEmpty())
        assertEquals(listOf("first", "second"), host.requests.map { it.first })
        host.translator.close()
    }

    @Test fun transientFailureRetriesCurrentCueOnceThenStops() {
        val host = Host()
        host.translator.request("current", { true }) {}
        advance(0)
        host.requests[0].second(null)
        advance(999)
        assertEquals(1, host.requests.size)
        advance(1)
        assertEquals(2, host.requests.size)
        host.requests[1].second(null)
        advance(16000)
        assertEquals(2, host.requests.size)
        host.translator.close()
    }

    @Test fun aNewCueDoesNotInheritThePreviousCuesRetryBackoff() {
        val host = Host()
        var cue = "first"
        host.translator.request("first", { cue == "first" }) {}
        advance(0)
        host.requests[0].second(null)
        cue = "second"
        host.translator.request("second", { cue == "second" }) {}
        advance(250)
        assertEquals(listOf("first", "second"), host.requests.map { it.first })
        host.translator.close()
    }

    @Test fun successfulRetryIsDeliveredAndCached() {
        val host = Host()
        val delivered = mutableListOf<String>()
        host.translator.request("current", { true }, delivered::add)
        advance(0)
        host.requests[0].second(null)
        advance(1000)
        host.requests[1].second("重试译文")
        advance(0)
        assertEquals(listOf("重试译文"), delivered)
        host.translator.request("current", { true }, delivered::add)
        assertEquals(listOf("重试译文", "重试译文"), delivered)
        assertEquals(2, host.requests.size)
        host.translator.close()
    }

    @Test fun cacheHitDiscardsPreviousUnsentForeground() {
        val host = Host()
        val delivered = mutableListOf<String>()
        host.translator.request("cached", { true }, delivered::add)
        advance(0)
        host.requests[0].second("已缓存")
        advance(0)
        host.translator.request("obsolete pending", { true }, delivered::add)
        host.translator.request("cached", { true }, delivered::add)
        advance(1000)
        assertEquals(1, host.requests.size)
        assertEquals(listOf("已缓存", "已缓存"), delivered)
        host.translator.close()
    }

    @Test fun slowBackgroundPrefetchContinuesWhileCurrentCueUsesItsOwnChannel() {
        val host = Host()
        val delivered = mutableListOf<String>()
        host.translator.prefetch(listOf("future cue")) { true }
        advance(0)
        host.translator.request("visible cue", { true }, delivered::add)
        advance(500)
        assertEquals(listOf("future cue", "visible cue"), host.requests.map { it.first })
        assertEquals(0, host.disposed)
        host.requests[0].second("保留的预读")
        host.requests[1].second("可见译文")
        advance(0)
        assertEquals(listOf("可见译文"), delivered)
        host.translator.request("future cue", { true }, delivered::add)
        assertEquals(listOf("可见译文", "保留的预读"), delivered)
        assertEquals(2, host.requests.size)
        host.translator.close()
    }

    @Test fun currentCueStartsWithoutDebounceAndPrefetchFailureDoesNotDelayIt() {
        val host = Host()
        host.translator.prefetch(listOf("future cue")) { true }
        advance(0)
        host.requests.single().second(null)
        advance(0)
        val delivered = mutableListOf<String>()
        host.translator.request("current cue", { true }, delivered::add)
        advance(0)
        assertEquals(listOf("future cue", "current cue"), host.requests.map { it.first })
        host.requests[1].second("及时译文")
        advance(0)
        assertEquals(listOf("及时译文"), delivered)
        host.translator.close()
    }

    @Test fun backgroundRequestDoesNotReserveForegroundDispatchTime() {
        val host = Host()
        host.translator.prefetch(listOf("future")) { true }
        advance(0)
        host.translator.request("visible", { true }) {}
        advance(0)
        assertEquals(listOf("future", "visible"), host.requests.map { it.first })
        assertEquals(0, host.disposed)
        host.translator.request("next", { true }) {}
        advance(249)
        assertEquals(2, host.requests.size)
        host.translator.close()
    }

    @Test fun firstCurrentCueIsSentInTheSameMainLoopCycle() {
        val host = Host()
        host.translator.request("visible cue", { true }) {}
        advance(0)
        assertEquals(listOf("visible cue"), host.requests.map { it.first })
        host.translator.close()
    }

    @Test fun chineseTrackWinsAndDisablingTranslationKeepsOriginalChoice() {
        var enabled = true
        val translator = NativeSubtitleTranslator({ enabled }, { _, _ -> AutoCloseable {} })
        NativeSubtitleTranslator.noteTrack("test-chinese", "zh-Hans")
        NativeSubtitleTranslator.noteTrack("test-english", "en")
        assertFalse(translator.isEnabled("test-chinese"))
        assertTrue(translator.isEnabled("test-english"))
        enabled = false
        assertFalse(translator.isEnabled("test-english"))
        translator.close()
        NativeSubtitleTranslator.noteTrack("test-chinese", null)
        NativeSubtitleTranslator.noteTrack("test-english", null)
    }

    @Test fun foregroundReusesActiveReadAheadWithNormalizedWhitespace() {
        val host = Host()
        val delivered = mutableListOf<String>()
        host.translator.prefetch(listOf("hello  world", "hello\nworld")) { true }
        advance(0)
        host.translator.request("hello\u00a0world", { true }, delivered::add)
        host.requests.single().second("你好世界")
        advance(1000)
        assertEquals(listOf("你好世界"), delivered)
        assertEquals(1, host.requests.size)
        host.translator.close()
    }

    @Test fun reusedReadAheadCannotDeliverAfterForegroundChanges() {
        val host = Host()
        var cue = "first"
        val delivered = mutableListOf<String>()
        host.translator.prefetch(listOf("first")) { true }
        advance(0)
        host.translator.request("first", { cue == "first" }, delivered::add)
        cue = "second"
        host.translator.request("second", { cue == "second" }, delivered::add)
        host.requests[0].second("旧句")
        advance(500)
        assertTrue(delivered.isEmpty())
        assertEquals(listOf("first", "second"), host.requests.map { it.first })
        host.requests[1].second("新句")
        advance(0)
        assertEquals(listOf("新句"), delivered)
        host.translator.close()
    }

    @Test fun foregroundCueTakesPriorityOverRemainingReadAheadAndCachedCueIsImmediate() {
        val host = Host()
        var visible = true
        host.translator.prefetch(listOf("ahead one", "ahead two", "ahead three")) { visible }
        advance(0)
        assertEquals(listOf("ahead one"), host.requests.map { it.first })
        val delivered = mutableListOf<String>()
        host.translator.request("current cue", { visible }, delivered::add)
        host.requests[0].second("预读第一句")
        advance(250)
        assertEquals(listOf("ahead one", "current cue"), host.requests.take(2).map { it.first })
        host.requests[1].second("当前句")
        advance(500)
        assertEquals(listOf("当前句"), delivered)
        assertEquals("ahead two", host.requests[2].first)
        host.translator.request("ahead one", { visible }, delivered::add)
        assertEquals(listOf("当前句", "预读第一句"), delivered)
        visible = false
        host.requests[2].second("不可见时的译文")
        advance(1000)
        assertEquals(3, host.requests.size)
        host.translator.close()
    }

    @Test fun sameCueWithNewGenerationReusesInFlightRequestBeforeDiscardingOldGeneration() {
        val host = Host()
        var generation = 1
        val delivered = mutableListOf<String>()
        host.translator.request("same cue", { generation == 1 }) { delivered += "obsolete" }
        advance(0)
        generation = 2
        host.translator.request("same cue", { generation == 2 }, delivered::add)
        advance(500)
        assertEquals(1, host.requests.size)
        assertEquals(0, host.disposed)
        host.requests.single().second("复用译文")
        assertEquals(listOf("复用译文"), delivered)
        host.translator.close()
    }

    @Test fun switchingSubtitleTrackCannotReuseOrCacheThePreviousTracksTranslation() {
        val host = Host()
        val delivered = mutableListOf<String>()
        CaptionPrefetch.note("same-video", "https://cdn.example/en.vtt")
        host.translator.request("same-video", "shared phrase", { true }, delivered::add)
        advance(0)
        assertEquals(1, host.requests.size)

        CaptionPrefetch.note("same-video", "https://cdn.example/ru.vtt")
        host.requests[0].second("English translation")
        advance(0)
        assertTrue(delivered.isEmpty())

        host.translator.request("same-video", "shared phrase", { true }, delivered::add)
        advance(250)
        assertEquals(2, host.requests.size)
        host.requests[1].second("Russian translation")
        advance(0)
        assertEquals(listOf("Russian translation"), delivered)

        host.translator.request("same-video", "shared phrase", { true }, delivered::add)
        assertEquals(listOf("Russian translation", "Russian translation"), delivered)
        host.translator.close()
        CaptionPrefetch.note("same-video", null)
    }

    @Test fun readAheadRunsDuringSlowCurrentRequestAndNeverExceedsTwoChannels() {
        val host = Host()
        host.translator.request("slow current", { true }) {}
        advance(0)
        host.translator.prefetch(listOf("future one", "future two", "future three")) { true }
        advance(250)
        assertEquals(listOf("slow current", "future one"), host.requests.map { it.first })
        advance(1000)
        assertEquals(2, host.requests.size)
        host.requests[1].second("后续第一句")
        advance(0)
        assertEquals(listOf("slow current", "future one", "future two"), host.requests.map { it.first })
        host.translator.cancel()
        assertEquals(3, host.disposed)
        host.translator.close()
    }

    @Test fun severalPreparedVideosSwitchUsingCompletedCacheWithoutNewRequests() {
        val host = Host()
        val openings = listOf("video one opening", "video two opening", "video three opening", "video four opening")
        host.translator.prefetch(openings) { true }
        advance(0)
        openings.forEachIndexed { index, _ ->
            host.requests[index].second("译文$index")
            advance(250)
        }
        val delivered = mutableListOf<String>()
        openings.forEach { host.translator.request(it, { true }, delivered::add) }
        assertEquals(listOf("译文0", "译文1", "译文2", "译文3"), delivered)
        assertEquals(4, host.requests.size)
        host.translator.close()
    }

    @Test fun queuedReadAheadCannotDuplicateNewForegroundRequest() {
        val host = Host()
        host.translator.prefetch(listOf("current cue", "future cue")) { true }
        host.translator.request("current cue", { true }) {}
        advance(250)
        assertEquals(listOf("current cue", "future cue"), host.requests.map { it.first })
        host.translator.close()
    }

    @Test fun fiveFullVideoTracksRemainAvailableWhenSelectedWithoutNewTranslationRequests() {
        val host = Host()
        val inputs = (1..5).flatMap { video -> (1..8).map { CaptionTranslationInput("v$video", "cue $it") } }
        for (video in 1..5) host.translator.expect("v$video", (1..8).map { "cue $it" })
        host.translator.prefetchVideos(inputs) { true }
        advance(0)
        inputs.forEachIndexed { index, input ->
            assertEquals(input.text, host.requests[index].first)
            host.requests[index].second("${input.video}-${input.text}")
            advance(250)
        }
        assertEquals(40, host.requests.size)
        for (video in 1..5) assertTrue(host.translator.completed("v$video", (1..8).map { "cue $it" }))
        val delivered = mutableListOf<String>()
        host.translator.request("v5", "cue 8", { true }, delivered::add)
        assertEquals(listOf("v5-cue 8"), delivered)
        assertEquals(40, host.requests.size)
        host.translator.close()
    }
}
