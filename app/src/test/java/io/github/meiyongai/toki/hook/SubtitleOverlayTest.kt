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
@Config(manifest = Config.NONE, sdk = [35])
class SubtitleOverlayTest {
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
        }
        val replies=mutableListOf<(String?) -> Unit>()
        val translator=NativeSubtitleTranslator({true},{ _,reply -> replies+=reply;AutoCloseable {} })
        val binding=SubtitleOverlay(source,translator) { ConfigSnapshot(mapOf("subtitle_style_enabled" to true)) }
        fun layer()=SubtitleOverlay::class.java.getDeclaredField("layer").let { it.isAccessible=true;it.get(binding) as? FrameLayout }
        binding.update("HIDDEN",native.text.toString(),"creator-video")
        binding.onPreDraw();shadowOf(Looper.getMainLooper()).idle()
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
        val requests = mutableListOf<(String?) -> Unit>()
        val translator = NativeSubtitleTranslator({ true }, { _, complete ->
            requests += complete
            AutoCloseable {}
        })
        val binding = SubtitleOverlay(source, translator) { ConfigSnapshot(mapOf("subtitle_style_enabled" to true)) }
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
        shadowOf(Looper.getMainLooper()).idle()
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
        var enabled = true
        val requests = mutableListOf<(String?) -> Unit>()
        val translator = NativeSubtitleTranslator({ enabled }, { _, complete -> requests += complete; AutoCloseable {} })
        val binding = SubtitleOverlay(source, translator) { ConfigSnapshot(mapOf(
            "video_translate_enabled" to enabled, "subtitle_style_enabled" to false,
            "subtitle_size" to "90", "subtitle_color" to "#FFFF0000", "subtitle_x" to "90")) }
        fun layer(): FrameLayout? = SubtitleOverlay::class.java.getDeclaredField("layer").let { it.isAccessible = true; it.get(binding) as? FrameLayout }
        binding.update("EXPANDED", "Original caption", "translation-without-style")
        binding.onPreDraw()
        shadowOf(Looper.getMainLooper()).idle()
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
        var enabled = true
        var lockX = false
        var lockY = false
        val binding = SubtitleOverlay(source) { ConfigSnapshot(mapOf("subtitle_style_enabled" to true,
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
        var style = true
        val requests = mutableListOf<Pair<String, (String?) -> Unit>>()
        val translator = NativeSubtitleTranslator({ true }, { text, complete ->
            requests += text to complete
            AutoCloseable {}
        })
        val binding = SubtitleOverlay(source, translator) { ConfigSnapshot(mapOf("subtitle_style_enabled" to style)) }
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
        }
        layout()
        var enabled = true
        val binding = SubtitleOverlay(source) { ConfigSnapshot(mapOf("subtitle_style_enabled" to enabled,
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
        assertEquals(android.graphics.Color.TRANSPARENT, (text.background as android.graphics.drawable.ColorDrawable).color)
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
