package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.SurfaceView
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
@Config(manifest = Config.NONE, sdk = [35], qualifiers = "w1080dp-h2400dp-mdpi")
class SubtitleOverlayTest {
    @org.junit.Before fun explicitFramesDoNotAutoSpinThePausedMainLooper() {
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @Test fun nativeFrameOwnsDisplayAndTimelineFallbackCannotShowASecondCaption() {
        HookRuntime.start("duplicate-caption-test") { _, _ -> }
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val page = FrameLayout(activity)
        val video = SurfaceView(activity)
        page.addView(video, FrameLayout.LayoutParams(1080, 2400))
        val source = FrameLayout(activity)
        source.addView(TextView(activity).apply { text = "native cue" })
        page.addView(source, FrameLayout.LayoutParams(600, 100))
        activity.setContentView(page)
        activity.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1080, 2400)
            settleWindowFrame()
        }
        val fixtureRect = android.graphics.Rect()
        assertTrue("Window fixture must be visible before caption assertions: attached=${source.isAttachedToWindow}, shown=${page.isShown}, rect=$fixtureRect",
            source.isAttachedToWindow && page.isShown && page.getGlobalVisibleRect(fixtureRect))
        val settings = config(mapOf("subtitle_style_enabled" to true))
        val native = SubtitleOverlay(source, configuration = { settings })
        val timeline = SubtitleOverlay(video, timelineSource = true, configuration = { settings })
        val layer = SubtitleOverlay::class.java.getDeclaredField("layer").apply { isAccessible = true }
        native.update("EXPANDED", "native cue", "same-video")
        native.onPreDraw()
        timeline.update("EXPANDED", "timeline cue", "same-video")
        timeline.onPreDraw()
        assertNotNull(layer.get(native))
        assertNull(layer.get(timeline))
        native.update("HIDDEN", "", "same-video")
        native.onPreDraw()
        timeline.onPreDraw()
        assertNotNull(layer.get(timeline))
        native.close(); timeline.close()
        controller.pause().stop().destroy()
    }
    @Test fun staleNativeFrameYieldsToCurrentTimelineInBothModesAndFreshFrameTakesOverAgain() {
        for (mode in listOf("custom", "native")) {
            HookRuntime.start("stalled-caption-test-$mode") { _, _ -> }
            val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
            val activity = controller.get()
            val page = FrameLayout(activity)
            val video = SurfaceView(activity)
            page.addView(video, FrameLayout.LayoutParams(1080, 2400))
            val source = FrameLayout(activity)
            val nativeText = TextView(activity).apply { text = "old cue" }
            source.addView(nativeText)
            page.addView(source, FrameLayout.LayoutParams(600, 100))
            activity.setContentView(page)
            activity.window.decorView.apply {
                measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                layout(0, 0, 1080, 2400); settleWindowFrame()
            }
            val translator = fakeOnlineTranslator({ true }, { _, _ -> AutoCloseable { } })
            translator.selectVideo("video", video)
            val settings = ConfigSnapshot(mapOf("subtitle_style_enabled" to true, "subtitle_render_mode" to mode, "subtitle_text_mode" to "original"))
            val native = SubtitleOverlay(source, translator, configuration = { settings })
            val timeline = SubtitleOverlay(video, translator, timelineSource = true, configuration = { settings })
            val layer = SubtitleOverlay::class.java.getDeclaredField("layer").apply { isAccessible = true }
            translator.noteNativeFrame("video")
            native.update("EXPANDED", "old cue", "video"); native.onPreDraw()
            timeline.update("EXPANDED", "current cue", "video"); timeline.onPreDraw()
            assertNull("Fresh native frame keeps ownership in $mode", layer.get(timeline))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(2100))
            assertFalse(translator.hasNativeFrame("video"))
            timeline.onPreDraw()
            assertNull("An old timeline cue must not take over merely because a paused clock advanced in $mode", layer.get(timeline))
            assertEquals("Native renderer remains untouched while paused in $mode", if (mode == "native") 1f else 0f, source.alpha)
            // Playback progress now supplies a current local cue, authorizing the takeover.
            timeline.update("EXPANDED", "current cue", "video")
            timeline.onPreDraw(); native.onPreDraw()
            assertNotNull("Stale native frame must not block the timeline in $mode", layer.get(timeline))
            assertEquals(0f, source.alpha)
            assertTrue(layer.get(native) == null || (layer.get(native) as View).visibility == View.INVISIBLE)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(2100))
            timeline.onPreDraw(); native.onPreDraw()
            assertNotNull("A current mounted cue remains stable when playback pauses in $mode", layer.get(timeline))
            translator.noteNativeFrame("video")
            nativeText.text = "fresh cue"
            native.update("EXPANDED", "fresh cue", "video"); native.onPreDraw(); timeline.onPreDraw()
            assertNull("Current native frames regain ownership in $mode", layer.get(timeline))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofMillis(2100))
            timeline.onPreDraw(); native.onPreDraw()
            assertNull("Paused old timeline must not reclaim fresh native text in $mode", layer.get(timeline))
            if (mode == "native") assertEquals(1f, source.alpha)
            else assertEquals(View.VISIBLE, (layer.get(native) as View).visibility)
            native.close(); timeline.close(); translator.close()
            assertEquals(1f, source.alpha)
            controller.pause().stop().destroy()
        }
    }
    @Test fun bilingualOrderChangesImmediatelyWithoutANewFrameInBothTypographyModes() {
        for (mode in listOf("custom", "native")) {
            HookRuntime.start("bilingual-order-test-$mode") { _, _ -> }
            val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
            val activity = controller.get()
            val page = FrameLayout(activity)
            page.addView(SurfaceView(activity), FrameLayout.LayoutParams(1080, 2400))
            val source = FrameLayout(activity)
            source.addView(TextView(activity).apply { text = "original cue" })
            page.addView(source, FrameLayout.LayoutParams(600, 100))
            activity.setContentView(page)
            activity.window.decorView.apply {
                measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
                layout(0, 0, 1080, 2400); settleWindowFrame()
            }
            val events = arrayListOf<String>(); var requests = 0
            val translator = fakeOnlineTranslator({ true }, { _, done -> requests++; done("译文"); AutoCloseable { } }, events::add)
            translator.selectVideo("ordered-video", source); translator.noteNativeFrame("ordered-video")
            // This is a presentation test: supply a completed pair through the real translation/cache path,
            // independently of this synthetic window's transient request-eligibility/layout callbacks.
            var available: String? = null
            translator.request("ordered-video", "original cue", { true }) { available = it }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("Completed translation must be present before testing line layout", "译文", available)
            assertEquals(1, requests)
            var settings = ConfigSnapshot(mapOf("subtitle_style_enabled" to true, "subtitle_render_mode" to mode,
                "subtitle_text_mode" to "bilingual", "subtitle_bilingual_order" to "original_first"))
            val overlay = SubtitleOverlay(source, translator, configuration = { settings })
            overlay.update("EXPANDED", "original cue", "ordered-video")
            overlay.onPreDraw(); shadowOf(Looper.getMainLooper()).idle(); overlay.onPreDraw()
            val labelField = SubtitleOverlay::class.java.getDeclaredField("label").apply { isAccessible = true }
            val label = labelField.get(overlay) as TextView
            assertEquals("mode=$mode requests=$requests enabled=${translator.isEnabled("ordered-video")} events=$events", "original cue\n译文", label.text.toString())
            val before = overlay.frameRevision
            settings = ConfigSnapshot(settings.configuration() + ("subtitle_bilingual_order" to "translated_first"))
            overlay.onPreDraw()
            assertSame(label, labelField.get(overlay)); assertEquals(before, overlay.frameRevision)
            assertEquals("译文\noriginal cue", label.text.toString())
            assertEquals("Changing order does not fetch/translate the cue again", 1, requests)
            assertEquals(0f, source.alpha)
            overlay.close(); translator.close(); assertEquals(1f, source.alpha)
            controller.pause().stop().destroy()
        }
    }
    private fun settleWindowFrame() { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)) }

    private fun config(values: Map<String, Any>) =
        ConfigSnapshot(values + ("subtitle_render_mode" to "custom"))

    @Test fun nativeModeKeepsHostTextAndDoesNotMountCustomLayer() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val page = FrameLayout(activity)
        page.addView(SurfaceView(activity), FrameLayout.LayoutParams(1080, 2400))
        val source = FrameLayout(activity)
        source.addView(TextView(activity).apply { text = "native caption" })
        page.addView(source, FrameLayout.LayoutParams(600, 100))
        activity.setContentView(page)
        activity.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1080, 2400)
            settleWindowFrame()
        }
        val binding = SubtitleOverlay(source) {
            ConfigSnapshot(mapOf("subtitle_style_enabled" to true, "video_translate_enabled" to true,
                "subtitle_render_mode" to "native"))
        }
        binding.update("EXPANDED", "native caption", "native-video")

        binding.onPreDraw()

        assertEquals(1f, source.alpha)
        val layer = SubtitleOverlay::class.java.getDeclaredField("layer").apply { isAccessible = true }
        assertNull(layer.get(binding))
        binding.close()
        controller.pause().stop().destroy()
    }

    @Test fun timelineCaptionsDisplayTranslationsWithoutChangingVideoSurfaceAlpha() {
        HookRuntime.start("timeline-display-test") { _, _ -> }
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val page = FrameLayout(activity)
        val video = SurfaceView(activity)
        page.addView(video, FrameLayout.LayoutParams(1080, 2400))
        activity.setContentView(page)
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 2400)
        settleWindowFrame()
        val translator = fakeOnlineTranslator({ true }, { _, complete -> complete("时间轴译文"); AutoCloseable {} })
        val binding = SubtitleOverlay(video, translator, timelineSource = true) {
            config(mapOf("subtitle_style_enabled" to true, "video_translate_enabled" to true))
        }
        binding.update("EXPANDED", "timeline original", "video")
        binding.onPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        binding.onPreDraw()
        val overlay = SubtitleOverlay::class.java.getDeclaredField("layer").apply { isAccessible = true }.get(binding) as FrameLayout
        assertEquals("时间轴译文", (overlay.getChildAt(0) as TextView).text.toString())
        assertEquals(1f, video.alpha)
        assertEquals(1, page.childCount)
        binding.update("HIDDEN", "", "video")
        binding.onPreDraw()
        assertEquals(View.INVISIBLE, overlay.visibility)
        binding.close(); translator.close(); controller.pause().stop().destroy()
    }
    @Test fun visibleCreatorCaptionWithHiddenAutoStateTranslatesButInvisibleStaleTextDoesNot() {
        HookRuntime.start("test.host") { _, _ -> }
        val owner=Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity=owner.get()
        val page=FrameLayout(activity)
        page.addView(SurfaceView(activity),FrameLayout.LayoutParams(1080,2400))
        val source=FrameLayout(activity)
        val native=TextView(activity).apply { text="Creator provided caption" }
        source.addView(native,FrameLayout.LayoutParams(600,100))
        page.addView(source,FrameLayout.LayoutParams(600,100))
        activity.setContentView(page)
        activity.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY))
            layout(0,0,1080,2400)
            settleWindowFrame()
        }
        val replies=mutableListOf<(String?) -> Unit>()
        val translator=fakeOnlineTranslator({true},{ _,reply -> replies+=reply;AutoCloseable {} })
        val binding=SubtitleOverlay(source,translator) { config(mapOf("subtitle_style_enabled" to true)) }
        fun layer()=SubtitleOverlay::class.java.getDeclaredField("layer").let { it.isAccessible=true;it.get(binding) as? FrameLayout }
        binding.update("HIDDEN",native.text.toString(),"creator-video")
        binding.onPreDraw();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(1,replies.size)
        replies.single()("作者提供的字幕")
        binding.onPreDraw()
        assertEquals("作者提供的字幕",(layer()!!.getChildAt(0) as TextView).text.toString())
        // Hiding source with visibility must still hide the overlay even after Toki hid its alpha.
        source.visibility=View.GONE;binding.onPreDraw()
        assertEquals(View.INVISIBLE,layer()!!.visibility)
        source.visibility=View.VISIBLE;native.text="stale text";binding.onPreDraw()
        assertEquals(View.INVISIBLE,layer()!!.visibility)
        binding.close();translator.close();owner.pause().stop().destroy()
    }

    @Test fun nativeCleanHiddenCaptionContainerStillRendersAndTranslatesUntilVideoPageLeaves() {
        HookRuntime.start("test.host") { _, _ -> }
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val page = FrameLayout(activity)
        page.addView(SurfaceView(activity), FrameLayout.LayoutParams(1080, 2400))
        val controls = FrameLayout(activity)
        val source = FrameLayout(activity)
        controls.addView(source, FrameLayout.LayoutParams(300, 100))
        page.addView(controls, FrameLayout.LayoutParams(1080, 1600))
        activity.setContentView(page)
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 2400)
        settleWindowFrame()
        val requests = mutableListOf<(String?) -> Unit>()
        val translator = fakeOnlineTranslator({ true }, { _, complete ->
            requests += complete
            AutoCloseable {}
        })
        val binding = SubtitleOverlay(source, translator) { config(mapOf("subtitle_style_enabled" to true)) }
        fun layer(): FrameLayout = SubtitleOverlay::class.java.getDeclaredField("layer").let {
            it.isAccessible = true; it.get(binding) as FrameLayout
        }
        // 原生清屏可能同时改变 visibility 与 alpha，源字幕容器不再是显示依据。
        controls.visibility = View.INVISIBLE
        controls.alpha = 0f
        source.visibility = View.GONE
        binding.update("EXPANDED", "caption in clean mode", "clean-test")
        binding.onPreDraw()
        assertEquals(View.VISIBLE, layer().visibility)
        assertSame(page, SubtitleOverlay::class.java.getDeclaredField("layerHost").let {
            it.isAccessible = true; it.get(binding)
        })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        requests.single()("清屏字幕译文")
        binding.onPreDraw()
        assertEquals("清屏字幕译文", (layer().getChildAt(0) as TextView).text.toString())
        page.visibility = View.INVISIBLE
        binding.onPreDraw()
        assertEquals(View.INVISIBLE, layer().visibility)
        page.visibility = View.VISIBLE
        binding.update("HIDDEN", "")
        binding.onPreDraw()
        assertEquals(View.INVISIBLE, layer().visibility)
        assertEquals(View.GONE, source.visibility)
        assertEquals(0f, controls.alpha)
        binding.close()
        translator.close()
        controller.pause().stop().destroy()
    }

    @Test fun translationDoesNotRequireCustomStyleAndKeepsTheNativeCaptionTypography() {
        HookRuntime.start("test.host") { _, _ -> }
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val page = FrameLayout(activity)
        page.addView(SurfaceView(activity), FrameLayout.LayoutParams(1080, 2400))
        val source = FrameLayout(activity)
        val native = TextView(activity).apply {
            text = "Original caption"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18f)
            setTextColor(android.graphics.Color.BLUE)
        }
        source.addView(native)
        page.addView(source, FrameLayout.LayoutParams(500, 100))
        activity.setContentView(page)
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 2400)
        settleWindowFrame()
        var enabled = true
        val requests = mutableListOf<(String?) -> Unit>()
        val translator = fakeOnlineTranslator({ enabled }, { _, complete -> requests += complete; AutoCloseable {} })
        val binding = SubtitleOverlay(source, translator) { config(mapOf(
            "video_translate_enabled" to enabled, "subtitle_style_enabled" to false,
            "subtitle_size" to "90", "subtitle_color" to "#FFFF0000", "subtitle_x" to "90")) }
        fun layer(): FrameLayout? = SubtitleOverlay::class.java.getDeclaredField("layer").let { it.isAccessible = true; it.get(binding) as? FrameLayout }
        binding.update("EXPANDED", "Original caption", "translation-without-style")
        binding.onPreDraw()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        assertEquals(1, requests.size)
        requests.single()("已翻译字幕")
        binding.onPreDraw()
        val label = checkNotNull(layer()).getChildAt(0) as TextView
        assertEquals("已翻译字幕", label.text.toString())
        assertEquals(18f, label.textSize, .01f)
        assertEquals(android.graphics.Color.BLUE, label.currentTextColor)
        assertEquals("Original caption", native.text.toString())
        assertFalse(binding.canDrag())
        assertEquals(2, page.childCount)
        enabled = false
        binding.onPreDraw()
        assertNull(layer())
        assertEquals(1f, source.alpha)
        binding.close(); translator.close()
        controller.pause().stop().destroy()
    }

    @Test fun dragSwitchAndEachAxisLockAreAppliedWithoutChangingLockedCoordinate() {
        HookRuntime.start("test.host") { _, _ -> }
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val page = FrameLayout(activity)
        val source = FrameLayout(activity)
        page.addView(source, FrameLayout.LayoutParams(300, 100))
        activity.setContentView(page)
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 2400)
        settleWindowFrame()
        var enabled = true
        var lockX = false
        var lockY = false
        val binding = SubtitleOverlay(source) { config(mapOf("subtitle_style_enabled" to true,
            "subtitle_drag_enabled" to enabled, "subtitle_lock_x" to lockX, "subtitle_lock_y" to lockY)) }
        binding.update("EXPANDED", "Drag subtitle")
        binding.onPreDraw()
        lockX = true
        val vertical = checkNotNull(binding.drag(0f to 0f, 200f, 200f))
        assertEquals(0f, vertical.first)
        assertTrue(vertical.second > 0f)
        binding.discardDrag()
        lockX = false; lockY = true
        val horizontal = checkNotNull(binding.drag(0f to 0f, 200f, 200f))
        assertTrue(horizontal.first > 0f)
        assertEquals(0f, horizontal.second)
        lockX = true
        assertFalse(binding.canDrag())
        assertNull(binding.drag(0f to 0f, 200f, 200f))
        lockX = false; lockY = false; enabled = false
        assertFalse(binding.canDrag())
        assertNull(binding.drag(0f to 0f, 200f, 200f))
        binding.close()
        controller.pause().stop().destroy()
    }

    @Test fun lateTranslationCannotCrossVideoAndStyleOffOnRecoversWithoutPlayerMutation() {
        HookRuntime.start("test.host") { _, _ -> }
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val page = FrameLayout(activity)
        val video = SurfaceView(activity)
        val source = FrameLayout(activity)
        page.addView(video, FrameLayout.LayoutParams(1080, 2400))
        page.addView(source, FrameLayout.LayoutParams(300, 100))
        activity.setContentView(page)
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 1080, 2400)
        settleWindowFrame()
        var style = true
        val requests = mutableListOf<Pair<String, (String?) -> Unit>>()
        val translator = fakeOnlineTranslator({ true }, { text, complete ->
            requests += text to complete
            AutoCloseable {}
        })
        val binding = SubtitleOverlay(source, translator) { config(mapOf("subtitle_style_enabled" to style)) }
        fun shown(): String = SubtitleOverlay::class.java.getDeclaredField("label").let {
            it.isAccessible = true
            (it.get(binding) as TextView).text.toString()
        }
        fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        binding.update("EXPANDED", "first video", "test-video-a")
        binding.onPreDraw()
        advance(180)
        assertEquals("first video", shown())
        binding.update("EXPANDED", "second video", "test-video-b")
        binding.onPreDraw()
        requests[0].second("上一条视频的译文")
        advance(0)
        binding.onPreDraw()
        assertEquals("second video", shown())
        advance(500)
        assertEquals("second video", requests[1].first)
        requests[1].second("当前视频译文")
        advance(0)
        binding.onPreDraw()
        assertEquals("当前视频译文", shown())
        style = false
        binding.onPreDraw()
        translator.cancel()
        style = true
        binding.onPreDraw()
        assertEquals("当前视频译文", shown())
        assertEquals(2, requests.size)
        assertEquals(2, page.childCount)
        assertSame(video, page.getChildAt(0))
        assertEquals(View.VISIBLE, video.visibility)
        assertEquals(1f, video.alpha)
        binding.close()
        translator.close()
        controller.pause().stop().destroy()
    }

    @Test fun overlayEscapesSmallParentAndRestoresOriginalWhenDisabled() {
        HookRuntime.start("test.host") { _, _ -> }
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val page = FrameLayout(activity)
        val video = SurfaceView(activity)
        page.addView(video, FrameLayout.LayoutParams(1080, 2400))
        val small = FrameLayout(activity)
        val source = FrameLayout(activity)
        val original = TextView(activity).apply { id = 2131366837; text = "native"; alpha = .7f }
        source.addView(original)
        small.addView(source, FrameLayout.LayoutParams(300, 100))
        page.addView(small, FrameLayout.LayoutParams(300, 100))
        activity.setContentView(page)
        fun layout() {
            val decor = activity.window.decorView
            decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 1080, 2400)
            settleWindowFrame()
        }
        layout()
        var enabled = true
        val binding = SubtitleOverlay(source) { config(mapOf("subtitle_style_enabled" to enabled,
            "subtitle_x" to "100", "subtitle_y" to "100", "subtitle_background_mode" to "hide")) }
        fun overlay(): FrameLayout? = SubtitleOverlay::class.java.getDeclaredField("layer").let {
            it.isAccessible = true
            it.get(binding) as? FrameLayout
        }
        binding.update("HIDDEN", "")
        binding.onPreDraw()
        assertNull(overlay())
        assertEquals(2, page.childCount)
        assertSame(video, page.getChildAt(0))
        binding.update("EXPANDED", "Visible caption")
        binding.onPreDraw()
        layout()
        binding.onPreDraw()
        assertEquals(0f, source.alpha)
        assertEquals(2, page.childCount)
        assertSame(video, page.getChildAt(0))
        assertSame(page, video.parent)
        assertEquals(View.VISIBLE, video.visibility)
        assertEquals(1f, video.alpha)
        val text = checkNotNull(overlay()).getChildAt(0) as TextView
        assertEquals("Visible caption", text.text.toString())
        assertEquals(android.graphics.Color.TRANSPARENT, checkNotNull((text.background as android.graphics.drawable.GradientDrawable).color).defaultColor)
        assertTrue(text.left > small.width)
        assertTrue(text.top > small.height)
        val screenPosition = IntArray(2)
        text.getLocationOnScreen(screenPosition)
        assertTrue(binding.hit(activity, screenPosition[0] + text.width / 2f, screenPosition[1] + text.height / 2f))
        assertFalse(binding.hit(activity, -100f, -100f))
        val dragResult = checkNotNull(binding.drag(binding.position(), -20000f, -20000f))
        assertTrue(dragResult.first.isFinite() && dragResult.second.isFinite())
        assertTrue(text.left >= 0 && text.top >= 0)
        assertTrue(text.right <= page.width && text.bottom <= page.height)
        assertEquals(2, page.childCount)
        assertSame(video, page.getChildAt(0))
        binding.discardDrag()
        binding.onPreDraw()
        val gesture = SubtitleGesture(activity)
        val down = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_DOWN, 50f, 50f, 0)
        assertTrue(gesture.touch(down) { _, _ -> binding })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(android.view.ViewConfiguration.getLongPressTimeout().toLong()))
        // Settings stay in the host; a hold must not launch an external Activity.
        assertNull(shadowOf(activity).nextStartedActivity)
        gesture.close()
        assertTrue(gesture.touch(down) { _, _ -> binding })
        gesture.close()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(android.view.ViewConfiguration.getLongPressTimeout().toLong()))
        assertNull(shadowOf(activity).nextStartedActivity)
        down.recycle()
        // 不重新发送字幕帧，重复离开/返回，验证宿主去重时仍能恢复。
        repeat(3) {
            page.removeView(small)
            page.addView(small, 1, FrameLayout.LayoutParams(300, 100))
            layout()
            binding.onPreDraw()
            layout()
            binding.onPreDraw()
            val returned = checkNotNull(overlay())
            assertEquals("Visible caption", (returned.getChildAt(0) as TextView).text.toString())
            assertEquals(0f, source.alpha)
            assertEquals(2, page.childCount)
            assertSame(video, page.getChildAt(0))
        }
        enabled = false
        binding.onPreDraw()
        assertEquals(.7f, original.alpha)
        assertEquals(1f, source.alpha)
        assertEquals(2, page.childCount)
        assertNull(overlay())
        enabled = true
        binding.onPreDraw()
        layout()
        binding.onPreDraw()
        assertEquals("Visible caption", (checkNotNull(overlay()).getChildAt(0) as TextView).text.toString())
        binding.update("EXPANDED", "")
        binding.onPreDraw()
        assertEquals(View.INVISIBLE, overlay()?.visibility)
        assertEquals(1f, source.alpha)
        assertEquals(2, page.childCount)
        assertSame(video, page.getChildAt(0))
        binding.close()
        assertNull(overlay())
        assertEquals(2, page.childCount)
        controller.pause().stop().destroy()
    }
}
