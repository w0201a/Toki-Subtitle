package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.model.LayoutElement
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.reflect.Modifier

/** Disable the home Minis sidebar through its availability and opening service. */
internal object MinisCleanupHook {
    internal fun methods(type: Class<*>, service: Class<*>) = type.declaredMethods.filter {
        !Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType &&
            service.methods.any { method -> method.name == it.name && method.parameterTypes.contentEquals(it.parameterTypes) }
    }.also { methods ->
        check(service.isAssignableFrom(type))
        check(methods.size == 3 && methods.count { it.parameterCount == 0 } == 1 &&
            methods.count { it.parameterTypes.contentEquals(arrayOf(Activity::class.java)) } == 1 &&
            methods.count { it.parameterCount == 1 && it.parameterTypes[0] != Activity::class.java && !it.parameterTypes[0].isPrimitive } == 1)
    }
    fun init(module: XposedModule, loader: ClassLoader) {
        val type = loader.loadClass("com.ss.android.ugc.aweme.sidebar.HomeSidebarServiceImpl")
        val service = loader.loadClass("com.ss.android.ugc.aweme.spi.IHomeSidebarService")
        for (method in methods(type, service)) {
            module.trackHook("LayoutMinis", method).intercept { chain ->
                if (ConfigClient.getBoolean(LayoutElement.MINIS.key)) {
                    HookRuntime.count("LayoutMinis", "已阻止Minis侧栏")
                    false
                } else chain.proceed()
            }
        }
    }
}
