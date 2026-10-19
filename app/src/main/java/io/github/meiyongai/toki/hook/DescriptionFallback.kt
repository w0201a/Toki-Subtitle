package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** 仅在最终标题布局边界显示译文；原生布局、视频模型和翻译状态均保持宿主管理。 */
internal object DescriptionFallback {
    fun install(module: XposedModule, loader: ClassLoader, owner: String) {
        val renderer = loader.loadClass("X.09Gd")
        val aweme = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme")
        val bind = renderer.getDeclaredMethod("LLFZ", aweme)
        val videoField = renderer.getDeclaredField("LLLZIIL")
        val viewField = renderer.getDeclaredField("LLLLJI")
        val vmField = renderer.getDeclaredField("LLJLLL")
        val expanded = vmField.type.getDeclaredField("LLJLL")
        val getDesc = aweme.getMethod("getDesc")
        val getId = aweme.getMethod("getAid")
        val facade = loader.loadClass("X.03hV")
        val service = facade.getDeclaredField("LIZIZ").get(null)
        val override = facade.getDeclaredMethod("LJL", String::class.java)
        val layoutView = loader.loadClass("X.09F7")
        val setLayout = layoutView.getDeclaredMethod("setTextLayout", Layout::class.java)
        val getLayout = layoutView.getDeclaredMethod("getTextLayout")
        val main = Handler(Looper.getMainLooper())
        val views = WeakHashMap<View, WeakReference<Any>>()
        val originals = WeakHashMap<View, Layout>()
        val generated = WeakHashMap<View, Layout>()
        fun enabled() = ConfigClient.getBoolean(VideoTranslateHook.KEY_VIDEO_TRANSLATE_ENABLED)
        fun source(target: Any): Pair<String, String>? {
            val video = videoField.get(target) ?: return null
            val text = getDesc.invoke(video) as? String ?: return null
            // 补偿按实际正文判断，避免宿主错误语言标记挡住外语长文案。
            if (!DescriptionTranslation.eligible(text, null)) return null
            return (getId.invoke(video) as? String ?: return null) to text
        }
        fun show(target: Any): Boolean {
            val current = source(target) ?: return false
            return enabled() && (override.invoke(service, current.first) as? Enum<*>)?.name != "OVERRIDE_TO_ORIGINAL"
        }
        fun refresh(target: Any) {
            val view = viewField.get(target) as? View ?: return
            if (!view.isAttachedToWindow) return
            val original = originals[view] ?: getLayout.invoke(view) as? Layout ?: return
            setLayout.invoke(view, original)
        }
        lateinit var translation: ChunkedDescriptionTranslation
        translation = ChunkedDescriptionTranslation(NativeSubtitleTranslator.transport(loader), { target ->
            try { refresh(target) } catch (error: Exception) {
                HookRuntime.event(owner, "layout refresh unavailable=${error.javaClass.simpleName}")
            }
        }, { HookRuntime.event(owner, "description $it") })
        module.trackHook(owner, bind).intercept { chain ->
            val target = checkNotNull(chain.thisObject)
            val previous = source(target)
            val next = chain.args[0]?.let { getId.invoke(it) }
            val view = viewField.get(target) as? View
            if (view != null) {
                if (previous?.first != next) {
                    translation.unbind(target)
                    originals.remove(view)
                    generated.remove(view)
                }
                views[view] = WeakReference(target)
            }
            val result = chain.proceed()
            source(target)?.let { current ->
                main.post {
                    if (source(target) == current && show(target)) {
                        translation.request(target, current.second)
                        refresh(target)
                    }
                }
            }
            result
        }
        module.trackHook(owner, setLayout).intercept { chain ->
            val view = chain.thisObject as? View ?: return@intercept chain.proceed()
            val target = views[view]?.get() ?: return@intercept chain.proceed()
            val original = chain.args[0] as? Layout ?: return@intercept chain.proceed()
            if (original !== generated[view]) originals[view] = original
            val current = source(target)
            val text = current?.let { translation.result(target, it.second) }
            if (text == null || !show(target)) return@intercept chain.proceed()
            val isExpanded = expanded.getBoolean(vmField.get(target))
            val layout = DescriptionTextLayout.build(original, text, isExpanded)
            generated[view] = layout
            HookRuntime.count(owner, "translated-layout-applied")
            HookRuntime.event(owner, "layout applied chars=${text.length} expanded=$isExpanded lines=${layout.lineCount}")
            chain.proceed(arrayOf(layout))
        }
        HookRuntime.subscribe(owner) {
            main.post {
                views.entries.toList().forEach { (view, ref) ->
                    val target = ref.get() ?: return@forEach
                    if (!enabled()) {
                        originals[view]?.let { setLayout.invoke(view, it) }
                        translation.unbind(target)
                    } else source(target)?.let { text ->
                        if (show(target)) translation.request(target, text.second)
                        refresh(target)
                    }
                }
            }
        }
        HookRuntime.onDispose(owner) { main.post {
            translation.close()
            originals.entries.toList().forEach { (view, layout) -> setLayout.invoke(view, layout) }
            views.clear(); originals.clear(); generated.clear()
        } }
        HookRuntime.event(owner, "description renderer ready; bind -> chunks -> final layout")
    }
}
