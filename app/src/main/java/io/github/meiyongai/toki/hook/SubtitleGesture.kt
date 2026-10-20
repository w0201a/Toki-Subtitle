package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.Toast

/** 仅接管字幕文字范围内的手势，透明绘制层不参与播放器子视图和触摸分发。 */
internal class SubtitleGesture(private val activity: Activity) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private var target: SubtitleOverlay? = null
    private var startX = 0f
    private var startY = 0f
    private var origin = 0f to 0f
    private var position: Pair<Float, Float>? = null
    private var moved = false
    private var opened = false
    private val hold = Runnable {
        if (target != null && !moved && !activity.isFinishing && !activity.isDestroyed) {
            opened = true
            openSettings(activity)
        }
    }

    fun touch(event: MotionEvent, find: (Float, Float) -> SubtitleOverlay?): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            close()
            target = find(event.rawX, event.rawY) ?: return false
            startX = event.rawX; startY = event.rawY
            origin = target!!.position()
            main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            return true
        }
        val subtitle = target ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (!opened) {
                val dx = event.rawX - startX; val dy = event.rawY - startY
                if (!moved && kotlin.math.hypot(dx, dy) > ViewConfiguration.get(activity).scaledTouchSlop) {
                    moved = true; main.removeCallbacks(hold)
                }
                if (moved && subtitle.canDrag()) position = subtitle.drag(origin, dx, dy)
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(hold)
                position?.let { value ->
                    try {
                        io.github.meiyongai.toki.subtitle.provider.ConfigClient.putStrings(activity,
                            mapOf("subtitle_x" to value.first.toString(), "subtitle_y" to value.second.toString()))
                    } catch (_: Exception) {
                        subtitle.discardDrag()
                        Toast.makeText(activity, "字幕位置保存失败，请长按首页打开设置重试", Toast.LENGTH_SHORT).show()
                    }
                }
                target = null; position = null
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> close()
        }
        return true
    }

    override fun close() {
        main.removeCallbacks(hold)
        target?.discardDrag()
        target = null; position = null; moved = false; opened = false
    }

    companion object {
        fun openSettings(activity: Activity) {
            HostSettingsEntry.openSubtitle(activity)
        }
    }
}
