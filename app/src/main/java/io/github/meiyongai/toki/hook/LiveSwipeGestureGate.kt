package io.github.meiyongai.toki.subtitle.hook

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.WeakHashMap

/** Shared sequence ownership. Track pointer IDs independently: native interception and touch can adopt different fingers. */
internal class LiveSwipeGestureGate {
    data class Decision(val blocked: Boolean = false, val cancelNative: Boolean = false)
    private data class Origin(val x: Float, val y: Float)
    private class Session(val downTime: Long, val origins: MutableMap<Int, Origin>,
        var armed: Boolean = true, var blocked: Boolean = false, var ended: Boolean = false)
    private val sessions = WeakHashMap<View, Session>()
    @Synchronized fun clear() = sessions.clear()
    @Synchronized fun decide(view: View, event: MotionEvent, enabled: Boolean): Decision {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            val old = sessions[view]
            if (old == null || old.downTime != event.downTime || old.ended) {
                if (enabled && event.pointerCount > 0) sessions[view] = Session(event.downTime,
                    mutableMapOf(event.getPointerId(0) to Origin(event.getX(0), event.getY(0)))) else sessions.remove(view)
            } else if (!enabled) old.armed = false
            return Decision()
        }
        val state = sessions[view]?.takeIf { it.downTime == event.downTime } ?: return Decision()
        if (!enabled) state.armed = false
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            state.ended = true
            // Every hooked entry must see the same terminal decision. The next DOWN replaces this tiny weak state.
            return Decision(state.blocked)
        }
        if (state.ended) return Decision()
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            for (index in 0 until event.pointerCount) {
                val id = event.getPointerId(index)
                if (action == MotionEvent.ACTION_POINTER_UP && index == event.actionIndex) state.origins.remove(id)
                else state.origins.putIfAbsent(id, Origin(event.getX(index), event.getY(index)))
            }
        }
        if (state.blocked) return Decision(true)
        if (!state.armed || action != MotionEvent.ACTION_MOVE) return Decision()
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
        val vertical = (0 until event.pointerCount).any { index ->
            val origin = state.origins[event.getPointerId(index)] ?: return@any false
            LiveSwipePolicy.blocks(event.getX(index) - origin.x, event.getY(index) - origin.y, slop)
        }
        if (!vertical) return Decision()
        state.blocked = true
        return Decision(blocked = true, cancelNative = true)
    }
}
