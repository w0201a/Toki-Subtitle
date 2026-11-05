package io.github.meiyongai.toki.subtitle.hook

import android.view.MotionEvent
import android.view.ViewGroup

/** Shared routing for the actual pager and its listener, including native cancellation and terminal-event draining. */
internal class LiveSwipeTouchController(private val onBlocked: () -> Unit = {},
    private val onCancelFailure: (Exception) -> Unit = {}) {
    enum class Entry { INTERCEPT, TOUCH, LISTENER }
    private val gestures = LiveSwipeGestureGate()
    private val cancelling = ThreadLocal.withInitial { false }
    fun clear() = gestures.clear()
    fun route(view: ViewGroup, event: MotionEvent, enabled: Boolean, entry: Entry, native: () -> Boolean): Boolean {
        if (cancelling.get()) return native()
        val decision = gestures.decide(view, event, enabled)
        if (decision.cancelNative) {
            val cancel = MotionEvent.obtain(event)
            try {
                cancel.action = MotionEvent.ACTION_CANCEL
                cancelling.set(true)
                // Touch resets any existing page offset; interception also releases native pointer/velocity state.
                try { view.onTouchEvent(cancel) } catch (error: Exception) { onCancelFailure(error) }
                try { view.onInterceptTouchEvent(cancel) } catch (error: Exception) { onCancelFailure(error) }
            } finally { cancelling.set(false); cancel.recycle() }
            onBlocked()
        }
        // Do not steal the stream from scrollable children. When the pager owns it, consume it through UP/CANCEL.
        return if (decision.blocked) entry != Entry.INTERCEPT else native()
    }
}
