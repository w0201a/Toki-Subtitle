package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
                        val saved = activity.contentResolver.call(Uri.parse("content://io.github.meiyongai.toki.subtitle.position"),
                            "position", null, Bundle().apply {
                                putFloat("subtitle_x", value.first); putFloat("subtitle_y", value.second)
                            })?.getBoolean("saved") == true
                        check(saved)
                    } catch (_: Exception) {
                        subtitle.discardDrag()
                        Toast.makeText(activity, "字幕位置保存失败，请在 Toki 内调整", Toast.LENGTH_SHORT).show()
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
            try {
                activity.startActivity(Intent().setComponent(ComponentName("io.github.meiyongai.toki.subtitle",
                    "io.github.meiyongai.toki.subtitle.ui.SubtitleSettingsActivity")))
            } catch (_: Exception) {
                Toast.makeText(activity, "无法打开字幕设置，请检查 Toki 字幕版是否已安装", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
