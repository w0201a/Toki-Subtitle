package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.view.MotionEvent
import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Narrow signatures and gate/service chain read from TikTok 47.6.3 DEX. Never writes global experiment storage. */
internal class HoldSpeedContract(loader: ClassLoader) {
    val component = loader.loadClass("com.ss.android.ugc.aweme.feed.longvideo.edgespeedup.EdgeSpeedupAssem")
    private val ability = loader.loadClass("com.ss.android.ugc.aweme.feed.longvideo.edgespeedup.EdgeSpeedupAbility")
    val delegate = loader.loadClass("X.0B6I")
    val touch = delegate.getDeclaredMethod("onTouch", View::class.java, MotionEvent::class.java).apply {
        check(returnType == Boolean::class.javaPrimitiveType && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    val owner = delegate.getDeclaredField("LLJL").apply {
        check(type == ability && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    val view = delegate.getDeclaredField("LLJJIJIL").apply {
        check(type == View::class.java && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    val runnable = delegate.getDeclaredField("LLJLL").apply {
        check(Runnable::class.java.isAssignableFrom(type)); isAccessible = true
    }
    val handler = delegate.getDeclaredMethod("LIZIZ").apply {
        check(Handler::class.java.isAssignableFrom(returnType) && !Modifier.isStatic(modifiers)); isAccessible = true
    }
    val threshold = instanceMethod(delegate, "LIZ", Float::class.javaPrimitiveType!!)
    val begin = instanceMethod(component, "gt", Void.TYPE, Float::class.javaPrimitiveType!!, Float::class.javaPrimitiveType!!)
    val finish = instanceMethod(component, "qj", Void.TYPE, Boolean::class.javaPrimitiveType!!)
    val pending = instanceMethod(component, "nn1", Void.TYPE, Boolean::class.javaPrimitiveType!!)
    val active = instanceMethod(component, "t52", Boolean::class.javaPrimitiveType!!)
    val created = instanceMethod(component, "onViewCreated", Void.TYPE, View::class.java)
    val attached = instanceMethod(component, "onViewAttached", Void.TYPE, View::class.java)
    val destroyed = instanceMethod(component, "onHostDestroy", Void.TYPE)
    val rebound = instanceMethod(component, "i4", Void.TYPE, Any::class.java)
    val downGate = loader.loadClass("X.0B6f").getDeclaredMethod("LIZ").apply {
        check(returnType == Boolean::class.javaPrimitiveType && Modifier.isStatic(modifiers)); isAccessible = true
    }
    val legacyGate: Method

    init {
        check(ability.isAssignableFrom(component) && View.OnTouchListener::class.java.isAssignableFrom(delegate))
        val facade = loader.loadClass("X.03Qx")
        val holder = facade.getDeclaredField("LIZIZ").apply {
            check(type == facade && Modifier.isStatic(modifiers)); isAccessible = true
        }.get(null)
        val providerType = loader.loadClass("X.03Yp")
        val provider = checkNotNull(instanceMethod(facade, "LJ", providerType).invoke(holder))
        check(providerType.isInstance(provider))
        val serviceType = loader.loadClass("X.03Yt")
        val service = checkNotNull(instanceMethod(providerType, "LJIJJ", serviceType).invoke(provider))
        check(serviceType.isInstance(service))
        legacyGate = service.javaClass.getMethod("LL").apply {
            check(returnType == Boolean::class.javaPrimitiveType && parameterCount == 0 &&
                !Modifier.isStatic(modifiers) && !Modifier.isAbstract(modifiers)); isAccessible = true
        }
    }

    companion object {
        internal fun instanceMethod(type: Class<*>, name: String, result: Class<*>, vararg arguments: Class<*>): Method =
            type.getMethod(name, *arguments).apply {
                check(returnType == result && !Modifier.isStatic(modifiers)); isAccessible = true
            }
    }
}
