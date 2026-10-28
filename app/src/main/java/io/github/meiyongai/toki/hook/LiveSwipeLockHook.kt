package io.github.meiyongai.toki.subtitle.hook

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** 在直播竖向翻页容器上拦截上下滑，保留点击、横向手势与程序化切页。 */
internal object LiveSwipeLockHook {
    private const val FEATURE = "LiveSwipeLockHook"
    const val KEY_LIVE_SWIPE_LOCK = "live_swipe_lock_enabled"
    private val gestures = java.util.Collections.synchronizedMap(WeakHashMap<Any, Gesture>())

    private class Gesture(var x: Float, var y: Float, var blocked: Boolean = false)

    /** 解析直播页唯一竖向翻页容器的两个触摸入口并校验完整契约。 */
    fun init(module: XposedModule, classLoader: ClassLoader) {
        val type = HostSymbols.resolve(classLoader, HostSymbol.LIVE_VERTICAL_PAGER)
        check(ViewGroup::class.java.isAssignableFrom(type)) { "直播翻页容器类型不符合契约" }
        val intercept = touchMethod(type, "onInterceptTouchEvent")
        val touch = touchMethod(type, "onTouchEvent")
        module.trackHook(FEATURE, intercept).intercept { chain ->
            val view = chain.thisObject as View
            val event = chain.args[0] as MotionEvent
            if (enabled() && shouldBlock(view, event)) false else chain.proceed()
        }
        module.trackHook(FEATURE, touch).intercept { chain ->
            val view = chain.thisObject as View
            val event = chain.args[0] as MotionEvent
            if (enabled() && shouldBlock(view, event)) false else chain.proceed()
        }
    }

    /** @return 当前会话是否开启直播上下滑限制。无参数。Callers: 两个触摸钩子。 */
    private fun enabled(): Boolean = ConfigClient.getBoolean(KEY_LIVE_SWIPE_LOCK)

    /**
     * 解析公开的 Android 触摸覆盖方法，不接受同名但签名不同的宿主方法。
     * @param type 已唯一解析的直播翻页容器。
     * @param name onInterceptTouchEvent 或 onTouchEvent。
     * @return 可挂载的方法。
     * Callers: init；LiveSwipeLockContractTest。
     */
    internal fun touchMethod(type: Class<*>, name: String): Method {
        require(name == "onInterceptTouchEvent" || name == "onTouchEvent")
        return type.getDeclaredMethod(name, MotionEvent::class.java).apply {
            check(returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(modifiers)) {
                "直播触摸入口签名不符合契约：$name"
            }
            isAccessible = true
        }
    }

    /**
     * 按当前手势状态决定是否阻断；只记录手指坐标，不读取或保存用户内容。
     * @param view 当前直播翻页容器实例。
     * @param event 原生触摸事件。
     * @return true 表示本事件不交给宿主翻页容器。
     * Callers: init 中注册的两个 Hook；LiveSwipeLockContractTest。
     */
    internal fun shouldBlock(view: View, event: MotionEvent): Boolean {
        val owner = view as Any
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestures[owner] = Gesture(event.getX(0), event.getY(0))
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val gesture = gestures[owner] ?: return false
                val dx = event.getX(0) - gesture.x
                val dy = event.getY(0) - gesture.y
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()
                if (!LiveSwipePolicy.blocks(dx, dy, slop)) return false
                if (!gesture.blocked) {
                    gesture.blocked = true
                    HookRuntime.count(FEATURE, "已阻止直播上下滑")
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gestures.remove(owner)
        }
        return false
    }
}
