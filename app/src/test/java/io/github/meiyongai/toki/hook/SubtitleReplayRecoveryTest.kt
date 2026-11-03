package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.os.Looper
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class SubtitleReplayRecoveryTest {
    private class Host {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val page = FrameLayout(activity)
        val video = SurfaceView(activity)
        val requests = mutableListOf<Pair<String, (String?) -> Unit>>()
        var disposed = 0
        val translator = fakeOnlineTranslator({ true }, { text, done ->
            requests += text to done; AutoCloseable { disposed++ }
        })
        val overlays = mutableListOf<SubtitleOverlay>()
        private val config = ConfigSnapshot(mapOf("subtitle_render_mode" to "custom", "subtitle_style_enabled" to true,
            "video_translate_enabled" to true, "subtitle_text_mode" to "translated"))
        init { page.addView(video, FrameLayout.LayoutParams(1080, 2400)); activity.setContentView(page); layout() }
        fun layout() = activity.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); layout(0, 0, 1080, 2400)
        }
        fun overlay(timeline: Boolean = false): SubtitleOverlay {
            val source = if (timeline) video else FrameLayout(activity).also {
                it.addView(TextView(activity).apply { text = "native" }); page.addView(it, FrameLayout.LayoutParams(600, 100)); layout()
            }
            return SubtitleOverlay(source, translator, timelineSource = timeline) { config }.also(overlays::add)
        }
        fun label(binding: SubtitleOverlay): String? = SubtitleOverlay::class.java.getDeclaredField("label").let {
            it.isAccessible = true; (it.get(binding) as? TextView)?.text?.toString()
        }
        fun mounted(binding: SubtitleOverlay): Boolean = SubtitleOverlay::class.java.getDeclaredField("layer").let {
            it.isAccessible = true; it.get(binding) != null
        }
        fun close() { overlays.forEach { it.close() }; translator.close(); controller.pause().stop().destroy() }
    }
    private fun advance(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun cancellingARequestDoesNotLeaveAnUnchangedVisibleCuePermanentlyRequested() {
        val host = Host()
        val binding = host.overlay()
        binding.update("EXPANDED", "same visible cue", "video"); binding.onPreDraw(); advance()
        assertEquals(1, host.requests.size)
        host.translator.cancel()
        binding.onPreDraw(); advance(250)
        assertEquals(2, host.requests.size)
        host.requests[0].second("迟到旧请求"); host.requests[1].second("当前译文"); advance()
        binding.onPreDraw()
        assertEquals("当前译文", host.label(binding))
        host.close()
    }
    @Test fun twoVisibleBindingsSharingARequestBothRecoverTheCompletedCacheWithoutDuplicateNetworkWork() {
        val host = Host()
        val first = host.overlay(); val second = host.overlay()
        first.update("EXPANDED", "shared source", "video"); first.onPreDraw(); advance()
        second.update("EXPANDED", "shared source", "video"); second.onPreDraw()
        assertEquals(1, host.requests.size)
        host.requests.single().second("共享译文"); advance()
        first.onPreDraw(); second.onPreDraw()
        assertEquals("共享译文", host.label(first))
        assertEquals("共享译文", host.label(second))
        assertEquals(1, host.requests.size)
        host.close()
    }
    @Test fun aPriorPassNativeCaptionCannotSuppressTheCurrentReplayTimelineOrDeliverItsOldResult() {
        val host = Host()
        val owner = Any()
        host.translator.selectVideo("video", owner)
        val native = host.overlay(); val timeline = host.overlay(true)
        native.update("EXPANDED", "last cue", "video"); native.onPreDraw(); advance()
        host.translator.noteNativeFrame("video")
        host.translator.playbackProgress("video", 20000)
        assertTrue(host.translator.hasNativeFrame("video"))
        val revision = host.translator.playbackRevision
        assertTrue(host.translator.playbackProgress("video", 0))
        assertEquals(revision + 1, host.translator.playbackRevision)
        assertFalse(host.translator.hasNativeFrame("video"))
        native.onPreDraw()
        assertFalse(host.mounted(native))
        timeline.update("EXPANDED", "first cue", "video"); timeline.onPreDraw(); advance(250)
        assertTrue(host.mounted(timeline))
        host.requests[0].second("不应覆盖的上一轮译文"); advance()
        assertNotEquals("不应覆盖的上一轮译文", host.label(timeline))
        native.update("EXPANDED", "first cue", "video"); native.onPreDraw(); timeline.onPreDraw()
        assertTrue(host.mounted(native))
        assertFalse(host.mounted(timeline))
        host.close()
    }
    @Test fun completedCueTranslationsRemainReusableAfterReplayWithoutRefetching() {
        val host = Host()
        host.translator.selectVideo("video", Any())
        val binding = host.overlay()
        binding.update("EXPANDED", "repeated cue", "video"); binding.onPreDraw(); advance()
        host.requests.single().second("已完成译文"); advance(); binding.onPreDraw()
        host.translator.playbackProgress("video", 10000)
        assertTrue(host.translator.playbackProgress("video", 0))
        binding.update("EXPANDED", "repeated cue", "video"); binding.onPreDraw(); advance(250)
        assertEquals("已完成译文", host.label(binding))
        assertEquals(1, host.requests.size)
        host.close()
    }
}
