package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
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
class VideoLayoutBindingsTest {
    private class Page {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val page = FrameLayout(activity)
        val root = View(activity)
        init {
            page.addView(root, FrameLayout.LayoutParams(1080, 2400)); activity.setContentView(page)
            activity.window.decorView.apply {
                measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY)); layout(0, 0, 1080, 2400)
            }
        }
        fun close() { controller.pause().stop().destroy() }
    }
    private fun drain() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ZERO)
    @Test fun coalescedReplaysAlwaysUseTheHostOriginalInsteadOfAccumulatingAReplacedResult() {
        val page = Page(); val owner = Any(); val raw = Any(); val adjusted = Any()
        val observed = mutableListOf<Any>()
        lateinit var bindings: VideoLayoutBindings
        bindings = VideoLayoutBindings({ target, value, reason ->
            observed += value
            bindings.original(target, adjusted, reason) // Same hook is entered when replaying, but may not overwrite raw.
        })
        bindings.bind(owner, page.root); bindings.original(owner, raw, "native")
        bindings.requestRefresh(); bindings.requestRefresh(); bindings.requestRefresh(); drain()
        assertEquals(1, observed.size); assertSame(raw, observed.single())
        bindings.requestRefresh(); drain()
        assertEquals(2, observed.size); assertTrue(observed.all { it === raw })
        bindings.close(); bindings.requestRefresh(); drain()
        assertEquals(2, observed.size)
        page.close()
    }
    @Test fun nativeEchoesOfRecentModuleOutputsResolveToTheOriginalWithoutScalingAgain() {
        val page = Page(); val owner = Any(); val raw = Any(); val firstOutput = Any(); val secondOutput = Any()
        val observed = mutableListOf<Any>()
        val bindings = VideoLayoutBindings({ _, value, _ -> observed += value })
        bindings.bind(owner, page.root)
        assertSame(raw, bindings.original(owner, raw, "native"))
        bindings.transformed(owner, firstOutput)
        bindings.transformed(owner, secondOutput)
        assertSame(raw, bindings.original(owner, firstOutput, "native cached echo"))
        assertSame(raw, bindings.original(owner, secondOutput, "native latest echo"))
        bindings.requestRefresh(); drain()
        assertSame(raw, observed.single())
        bindings.invalidate(owner)
        val fresh = Any()
        assertSame(fresh, bindings.original(owner, fresh, "new video"))
        bindings.close(); page.close()
    }
    @Test fun rebindingCannotReplayThePreviousVideosResultAndReleaseStopsUpdates() {
        val page = Page(); val owner = Any(); val old = Any(); val current = Any()
        val observed = mutableListOf<Any>()
        val bindings = VideoLayoutBindings({ _, value, _ -> observed += value })
        bindings.bind(owner, page.root); bindings.original(owner, old, "old")
        bindings.invalidate(owner); bindings.requestRefresh(); drain()
        assertTrue(observed.isEmpty())
        bindings.original(owner, current, "current"); bindings.requestRefresh(); drain()
        assertEquals(listOf(current), observed)
        bindings.forget(owner); bindings.requestRefresh(); drain()
        assertEquals(1, observed.size)
        bindings.close(); page.close()
    }
    @Test fun detachedOrHiddenRootsAreNotForceLaidOutFromAnOldCapture() {
        val page = Page(); val owner = Any()
        var updates = 0
        val bindings = VideoLayoutBindings({ _, _, _ -> updates++ })
        bindings.bind(owner, page.root); bindings.original(owner, Any(), "original")
        page.root.visibility = View.INVISIBLE
        bindings.requestRefresh(); drain(); assertEquals(0, updates)
        page.root.visibility = View.VISIBLE
        bindings.requestRefresh(); drain(); assertEquals(1, updates)
        page.page.removeView(page.root) // In-memory test view hierarchy only; no files/data are removed.
        bindings.requestRefresh(); drain(); assertEquals(1, updates)
        bindings.close(); page.close()
    }
}
