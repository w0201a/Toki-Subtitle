package io.github.meiyongai.toki.subtitle.hook

import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.util.Locale

/** 标题、说明文案使用宿主富文本翻译，保留展开、话题、链接及查看原文。 */
internal object DescriptionTranslation {
    private const val OWNER = "DescriptionNativeTranslation"

    internal fun eligible(text: String?, language: String?): Boolean {
        if (text.isNullOrBlank() || !text.any(Char::isLetter)) return false
        val code = language?.lowercase(Locale.ROOT)?.replace('_', '-')
        if (code == "zh" || code?.startsWith("zh-") == true) return false
        // 未标记语言时避免将全中文文案重复送翻译；不按标题长度截断。
        return text.any { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.HAN }
    }

    fun install(module: XposedModule, loader: ClassLoader) {
        HookRuntime.install(OWNER) { installNative(module, loader) }
        HookRuntime.install("DescriptionTranslation") { DescriptionFallback.install(module, loader, "DescriptionTranslation") }
    }

    private fun installNative(module: XposedModule, loader: ClassLoader) {
        val aweme = loader.adaptiveClass("com.ss.android.ugc.aweme.feed.model.Aweme")
        val desc = aweme.adaptiveMethod("getDesc")
        val descLanguage = aweme.adaptiveMethod("getDescLanguage")
        val aid = aweme.adaptiveMethod("getAid")
        val photo = aweme.adaptiveMethod("getPhotoModeImageInfo")
        val photoTitle = photo.returnType.adaptiveMethod("getTitle")
        val facade = loader.adaptiveClass("X.03hV")
        val service = checkNotNull(facade.adaptiveDeclaredField("LIZIZ").get(null))
        val override = facade.adaptiveDeclaredMethod("LJL", String::class.java)
        val titleLanguage = runCatching { loader.adaptiveClass("X.0BAx").adaptiveDeclaredMethod("LIZ", aweme) }.getOrNull()
        val directTitleLanguage = if (titleLanguage == null) aweme.getMethod("getPhotoTitleLanguageCode") else null
        val gate = loader.adaptiveClass("com.ss.android.ugc.aweme.translation.service.CLATranslatabilityServiceImpl")
        fun enabled() = ConfigClient.getBoolean(VideoTranslateHook.KEY_VIDEO_TRANSLATE_ENABLED)
        fun accepts(value: Any, title: Boolean): Boolean = if (title) {
            val info = photo.invoke(value)
            info != null && eligible(photoTitle.invoke(info) as? String,
                (titleLanguage?.invoke(null, value) ?: directTitleLanguage?.invoke(value)) as? String)
        } else eligible(desc.invoke(value) as? String, descLanguage.invoke(value) as? String)
        fun automatic(value: Any): Boolean =
            (override.invoke(service, aid.invoke(value)) as? Enum<*>)?.name != "OVERRIDE_TO_ORIGINAL"
        for ((name, title, auto) in listOf(
            Triple("LJ", false, false), Triple("LJIILL", false, true),
            Triple("LIZLLL", true, false), Triple("LIZJ", true, true)
        )) {
            val method = if (auto) gate.adaptiveDeclaredMethod(name, aweme, java.lang.Boolean::class.java)
                else gate.adaptiveDeclaredMethod(name, aweme)
            check(method.returnType == Boolean::class.javaPrimitiveType)
            module.trackHook(OWNER, method).intercept { chain ->
                val original = chain.proceed() as Boolean
                val value = chain.args[0]
                if (!original && enabled() && value != null && accepts(value, title) && (!auto || automatic(value))) {
                    HookRuntime.count(OWNER, if (title) "photo-title-eligible" else "description-eligible")
                    true
                } else original
            }
        }
        val auto = facade.adaptiveDeclaredMethod("LJJIZ", aweme)
        module.trackHook(OWNER, auto).intercept { chain ->
            val original = chain.proceed() as Boolean
            val value = chain.args[0]
            if (!original && enabled() && value != null && automatic(value) &&
                (accepts(value, false) || accepts(value, true))) {
                HookRuntime.count(OWNER, "native-auto-translation-enabled")
                true
            } else original
        }
        // 观察原生请求实际是否发出，不记录标题正文或视频标识。
        try {
            val request = loader.adaptiveClass("X.0948").adaptiveDeclaredMethod("LJJIIJ", loader.adaptiveClass("X.0Agi"))
            module.trackHook(OWNER, request).intercept { chain ->
                if (enabled()) HookRuntime.event(OWNER, "native request started")
                chain.proceed()
            }
        } catch (error: ReflectiveOperationException) {
            HookRuntime.event(OWNER, "request diagnostics unavailable; eligibility hooks retained")
        } catch (error: IllegalStateException) {
            HookRuntime.event(OWNER, "request diagnostics contract unavailable; eligibility hooks retained")
        }
        HookRuntime.event(OWNER, "ready; native description and photo title; no length truncation")
    }
}
