package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class OfflineVideoHitTest {
    @Test fun videoSurfaceQualifiesButUnrelatedButtonsAndInvalidPointsDoNot() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val video = View(activity)
        root.addView(video, FrameLayout.LayoutParams(400, 600))
        activity.setContentView(root)
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 500, 800)
        val location = IntArray(2); video.getLocationOnScreen(location)
        val x = location[0] + 40f; val y = location[1] + 40f
        assertTrue(OfflineVideoHook.videoHit(video, x, y))
        val button = View(activity).apply { isClickable = true }
        root.addView(button, FrameLayout.LayoutParams(100, 100))
        decor.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 500, 800)
        assertFalse(OfflineVideoHook.videoHit(video, x, y))
        assertFalse(OfflineVideoHook.videoHit(video, Float.NaN, y))
        video.visibility = View.GONE
        assertFalse(OfflineVideoHook.videoHit(video, x, y))
        controller.pause().stop().destroy()
    }
}
