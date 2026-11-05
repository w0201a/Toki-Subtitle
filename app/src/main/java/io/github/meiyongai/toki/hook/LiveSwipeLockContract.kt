package io.github.meiyongai.toki.subtitle.hook

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Resolve the pager actually owned by the semantically matched LIVE listener, rather than blocking only its callback. */
internal class LiveSwipeLockContract private constructor(val pager: Class<*>, val intercept: Method,
    val touch: Method, val listener: Method?, private val fragment: Field?, private val pagerField: Field?) {
    fun ownsListener(owner: Any?, view: View): Boolean = owner != null && pager.isInstance(view) &&
        fragment?.get(owner)?.let { pagerField?.get(it) === view } == true
    companion object {
        fun resolve(type: Class<*>): LiveSwipeLockContract {
            if (ViewGroup::class.java.isAssignableFrom(type)) return pager(type)
            check(View.OnTouchListener::class.java.isAssignableFrom(type)) { "直播触摸目标不是监听器或翻页容器" }
            val fragment = type.getDeclaredField("LL").apply {
                check(this.type.name == "com.bytedance.android.livesdk.LiveRoomFragment" && !Modifier.isStatic(modifiers))
                isAccessible = true
            }
            return listener(type, fragment, fragment.type.getDeclaredField("LLLLLLJ"))
        }
        internal fun listener(type: Class<*>, fragment: Field, field: Field): LiveSwipeLockContract {
            check(!Modifier.isStatic(fragment.modifiers) && !Modifier.isStatic(field.modifiers))
            check(field.declaringClass.isAssignableFrom(fragment.type))
            fragment.isAccessible = true; field.isAccessible = true
            val method = type.getDeclaredMethod("onTouch", View::class.java, MotionEvent::class.java).apply {
                check(returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(modifiers)); isAccessible = true
            }
            val value = pager(field.type)
            return LiveSwipeLockContract(value.pager, value.intercept, value.touch, method, fragment, field)
        }
        private fun pager(type: Class<*>): LiveSwipeLockContract {
            check(ViewGroup::class.java.isAssignableFrom(type)) { "直播字段不是翻页容器" }
            return LiveSwipeLockContract(type, LiveSwipeLockHook.touchMethod(type, "onInterceptTouchEvent"),
                LiveSwipeLockHook.touchMethod(type, "onTouchEvent"), null, null, null)
        }
    }
}
