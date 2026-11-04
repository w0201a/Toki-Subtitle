package io.github.meiyongai.toki.subtitle.hook

import android.os.Looper
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CaptionTimelineFetchTest {
    @Before fun prepareRuntime() { HookRuntime.start("caption-fetch-test") { _, _ -> } }

    // Deterministic executors: no network, sleeps, disk fixtures or live threads.
    private class ManualWorker(capacity: Int = 5) : ThreadPoolExecutor(1, 1, 0,
        TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity), AbortPolicy()) {
        var reject = false
        override fun execute(command: Runnable) {
            if (reject || isShutdown || !queue.offer(command)) throw RejectedExecutionException()
        }
        fun take(): Runnable = checkNotNull(queue.poll())
        fun runNext() = take().run()
    }
    private class Fixture(val id: String) : AutoCloseable {
        val foreground = ManualWorker(1)
        val background = ManualWorker(5)
        val urls = mutableListOf<String>()
        val translator = fakeOnlineTranslator({ true }, { _, _ -> AutoCloseable {} })
        var load: (String) -> List<CaptionTimeline.Cue> = { cue("hello") }
        val prefetch = CaptionPrefetch(translator, foreground, background) { url, _, _ ->
            urls += url
            load(url)
        }
        fun select(url: String = "https://cdn.tiktok.com/$id.vtt?signature=first") {
            CaptionPrefetch.note(id, url)
            prefetch.update(id, "hello") { true }
        }
        fun finish() { foreground.runNext(); advance(0) }
        override fun close() { prefetch.close(); translator.close(); CaptionPrefetch.note(id, null) }
    }
    companion object {
        private fun cue(text: String) = listOf(CaptionTimeline.Cue(0, 3000, text))
        private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Test fun missingCurrentTimelineRetriesWithoutAnotherCaptionOrVideoChange() {
        Fixture("retry-current").use { f ->
            f.load = { throw java.io.IOException("temporary") }
            f.select(); f.finish()
            assertNull(f.prefetch.cueAt(f.id, 100))
            advance(1499)
            assertEquals(0, f.foreground.queue.size)
            f.load = { cue("recovered") }
            advance(1)
            assertEquals(1, f.foreground.queue.size)
            f.finish()
            assertEquals("recovered", f.prefetch.cueAt(f.id, 100))
            advance(30_000)
            assertEquals(2, f.urls.size)
        }
    }

    @Test fun emptyTimelineBacksOffInsteadOfRetryingEveryFrame() {
        Fixture("retry-empty").use { f ->
            f.load = { emptyList() }
            f.select(); f.finish()
            repeat(30) { f.prefetch.update(f.id, "same") { true } }
            assertEquals(0, f.foreground.queue.size)
            advance(1500); f.finish() // Second failure: next retry is 3000 ms away.
            advance(1500)
            assertEquals(0, f.foreground.queue.size)
            advance(1500)
            assertEquals(1, f.foreground.queue.size)
            f.load = { cue("ready") }; f.finish()
            assertEquals("ready", f.prefetch.cueAt(f.id, 200))
        }
        assertEquals(listOf(1500L, 3000L, 6000L, 12000L, 15000L, 15000L),
            (1..6).map(CaptionPrefetch::retryDelay))
    }

    @Test fun signedUrlRefreshUsesNewestUrlOnRetryWithoutSpawningDuplicateDownloads() {
        Fixture("retry-signed-url").use { f ->
            f.load = { emptyList() }
            f.select()
            f.select("https://cdn.tiktok.com/${f.id}.vtt?signature=second")
            assertEquals(1, f.foreground.queue.size)
            f.finish()
            f.load = { cue("new signed url") }
            advance(1500); f.finish()
            assertTrue(f.urls.last().endsWith("signature=second"))
            assertEquals("new signed url", f.prefetch.cueAt(f.id, 200))
        }
    }

    @Test fun memoryTimelineSurvivesLeavingAndReturningWithRefreshedCredentials() {
        Fixture("return-signed-url").use { f ->
            f.select(); f.finish()
            f.prefetch.cancel()
            f.select("https://cdn.tiktok.com/${f.id}.vtt?signature=changed&expires=999")
            assertEquals("hello", f.prefetch.cueAt(f.id, 200))
            assertEquals(0, f.foreground.queue.size)
            assertEquals(1, f.urls.size)
        }
    }

    @Test fun aGenuinelyDifferentTrackCannotUseTheOldTimelineOrLateCallback() {
        Fixture("changed-track").use { f ->
            f.load = { cue("old") }
            f.select(); f.foreground.runNext() // Old result is posted but not delivered yet.
            f.select("https://cdn.tiktok.com/${f.id}-different.vtt")
            advance(0)
            assertNull(f.prefetch.cueAt(f.id, 200))
            assertEquals(1, f.foreground.queue.size)
            f.load = { cue("new") }; f.finish()
            assertEquals("new", f.prefetch.cueAt(f.id, 200))
        }
    }

    @Test fun cancellingBeforeOrAfterWorkerCompletionRejectsStaleResultsAndStopsRetries() {
        Fixture("cancel-timeline").use { f ->
            f.select()
            val old = f.foreground.take()
            f.prefetch.cancel(); old.run(); advance(0)
            assertTrue(f.urls.isEmpty())
            f.select(); f.foreground.runNext()
            f.prefetch.cancel(); advance(0)
            assertNull(f.prefetch.cueAt(f.id, 100))
            advance(30_000)
            assertEquals(0, f.foreground.queue.size)
        }
    }

    @Test fun rejectedCurrentTaskReleasesItsLoadingStateAndRetries() {
        Fixture("current-rejected").use { f ->
            f.foreground.reject = true
            f.select()
            assertTrue(f.urls.isEmpty())
            f.foreground.reject = false
            advance(1500); f.finish()
            assertEquals("hello", f.prefetch.cueAt(f.id, 100))
        }
    }

    @Test fun pendingFutureDownloadsCannotBlockTheCurrentVideoLane() {
        Fixture("priority-lane").use { f ->
            f.translator.upcomingTracks = { _, _ -> (1..5).map { index ->
                FeedCaptionLookAhead.Track("future-$index", "https://cdn.tiktok.com/future-$index.vtt")
            } }
            f.select()
            assertEquals(5, f.background.queue.size)
            assertEquals(1, f.foreground.queue.size)
            f.finish()
            assertEquals("hello", f.prefetch.cueAt(f.id, 100))
            assertEquals(5, f.background.queue.size)
            assertEquals(1, f.urls.size)
        }
    }

    @Test fun rejectedUpcomingTaskCanBeResubmittedInsteadOfStayingLoadingForever() {
        Fixture("future-rejected").use { f ->
            f.translator.upcomingTracks = { _, _ -> listOf(
                FeedCaptionLookAhead.Track("retry-future", "https://cdn.tiktok.com/retry-future.vtt")) }
            f.background.reject = true
            f.select(); f.finish()
            assertEquals(0, f.background.queue.size)
            f.background.reject = false
            advance(1500)
            assertEquals(1, f.background.queue.size)
            f.background.runNext(); advance(0)
            advance(1500)
            assertEquals(0, f.background.queue.size)
            assertEquals(2, f.urls.size)
        }
    }

    @Test fun stoppedPageDoesNotRetryUntilItBecomesCurrentAgain() {
        Fixture("hidden-page").use { f ->
            f.load = { emptyList() }
            f.select(); f.finish()
            f.prefetch.update(f.id, "hello") { false }
            advance(30_000)
            assertEquals(0, f.foreground.queue.size)
            f.load = { cue("visible again") }
            f.prefetch.update(f.id, "hello") { true }; f.finish()
            assertEquals("visible again", f.prefetch.cueAt(f.id, 100))
        }
    }
}
