package io.github.meiyongai.toki.subtitle.hook

import android.view.MotionEvent
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Lock the actual LIVE pager and its listener together; clicks, horizontal gestures and programmatic paging retain native handling. */
internal object LiveSwipeLockHook {
    private const val FEATURE = "LiveSwipeLockHook"
    const val KEY_LIVE_SWIPE_LOCK = "live_swipe_lock_enabled"
    private val touches = LiveSwipeTouchController(
        { HookRuntime.count(FEATURE, "已阻止直播上下滑") },
        { HookRuntime.event(FEATURE, "直播原生触摸清理未完成：" + it.javaClass.simpleName) })
    fun init(module: XposedModule, classLoader: ClassLoader) {
        val contract = LiveSwipeLockContract.resolve(HostSymbols.resolve(classLoader, HostSymbol.LIVE_VERTICAL_PAGER))
        for ((method, entry) in listOf(contract.intercept to LiveSwipeTouchController.Entry.INTERCEPT,
            contract.touch to LiveSwipeTouchController.Entry.TOUCH)) {
            module.trackHook(FEATURE, method).intercept { chain ->
                val view = chain.thisObject as? ViewGroup
                val event = chain.args[0] as? MotionEvent
                if (view == null || event == null || !contract.pager.isInstance(view)) chain.proceed()
                else touches.route(view, event, ConfigClient.getBoolean(KEY_LIVE_SWIPE_LOCK), entry) { chain.proceed() as Boolean }
            }
        }
        contract.listener?.let { method ->
            module.trackHook(FEATURE, method).intercept { chain ->
                val view = chain.args[0] as? ViewGroup
                val event = chain.args[1] as? MotionEvent
                if (view != null && event != null && contract.ownsListener(chain.thisObject, view))
                    touches.route(view, event, ConfigClient.getBoolean(KEY_LIVE_SWIPE_LOCK), LiveSwipeTouchController.Entry.LISTENER) {
                        chain.proceed() as Boolean
                    } else chain.proceed()
            }
        }
        HookRuntime.onDispose(FEATURE) { touches.clear() }
        HookRuntime.detail(FEATURE, "已绑定直播实际翻页容器及触摸监听器；纵向锁定整段手势，保留点击、横向及程序切页")
    }
    internal fun touchMethod(type: Class<*>, name: String): Method {
        require(name == "onInterceptTouchEvent" || name == "onTouchEvent")
        return type.getDeclaredMethod(name, MotionEvent::class.java).apply {
            check(returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(modifiers)) {
                "直播触摸入口签名不符合契约：$name"
            }
            isAccessible = true
        }
    }
}
