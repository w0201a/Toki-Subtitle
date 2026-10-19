package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.view.View
import io.github.libxposed.api.XposedModule
import java.util.WeakHashMap

/** 已核验 47.0.3 的长按菜单条目，不替换原“字幕和翻译”。 */
internal object SubtitleMenu {
    private const val TITLE = 0x7e010001
    fun install(module: XposedModule, loader: ClassLoader, owner: String) {
        val factory = loader.loadClass("X.0oWb")
        val panel = loader.loadClass("X.0oQw")
        val data = loader.loadClass("X.0oTM")
        val icon = loader.loadClass("X.03wL")
        val callback = loader.loadClass("X.0oQA")
        val callbackBase = loader.loadClass("X.0oQW")
        val item = loader.loadClass("X.0oTK")
        val build = factory.getDeclaredMethod("LIZIZ", panel).apply { check(List::class.java.isAssignableFrom(returnType)) }
        val dataConstructor = data.getDeclaredConstructor(icon, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
        val callbackConstructor = callback.getDeclaredConstructor(factory, panel)
        val itemConstructor = item.getDeclaredConstructor(data, callbackBase, Boolean::class.javaPrimitiveType)
        val iconField = icon.getDeclaredField("LIZ")
        val callbacks = WeakHashMap<Any, Boolean>()
        val click = callback.getDeclaredMethod("LIZIZ", View::class.java)
        // 独立资源 ID 只在本模块新增条目中使用，不覆盖任何原生字符串。
        for (name in listOf("getText", "getString")) {
            module.trackHook(owner, Resources::class.java.getMethod(name, Int::class.javaPrimitiveType)).intercept { chain ->
                if (chain.args[0] == TITLE) "字幕设置" else chain.proceed()
            }
        }
        module.trackHook(owner, click).intercept { chain ->
            if (callbacks.containsKey(chain.thisObject)) {
                var context: Context? = (chain.args[0] as View).context
                while (context is ContextWrapper && context !is Activity) context = context.baseContext
                (context as? Activity)?.let(SubtitleGesture::openSettings)
                null
            } else chain.proceed()
        }
        module.trackHook(owner, build).intercept { chain ->
            val original = chain.proceed() as? List<*> ?: return@intercept null
            try {
                val picture = icon.getDeclaredConstructor().newInstance()
                iconField.setInt(picture, 2130774938)
                val action = callbackConstructor.newInstance(chain.thisObject, chain.args[0])
                callbacks[action] = true
                val model = dataConstructor.newInstance(picture, TITLE, false, "toki_subtitle_settings")
                val entry = itemConstructor.newInstance(model, action, true)
                HookRuntime.event("SubtitleMenu", "settings entry added")
                original + entry
            } catch (error: Exception) {
                HookRuntime.event("SubtitleMenu", "entry unavailable=${error.javaClass.simpleName}")
                original
            }
        }
        HookRuntime.onDispose(owner) { callbacks.clear() }
    }
}
