package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.reflect.Modifier

/** 使用原生评论批量翻译入口，保留原方法执行和宿主状态流。 */
object CommentTranslateHook {
    const val KEY_COMMENT_TRANSLATE_ENABLED = "comment_translate_enabled"
    fun refreshConfig(context: Context) = Unit

    fun init(module: XposedModule, loader: ClassLoader) {
        HookRuntime.install("CommentTranslationGate") {
        val gate = HostSymbols.resolve(loader, HostSymbol.COMMENT_TRANSLATION)
        val methods = gate.declaredMethods.filter {
            Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType &&
                (it.parameterCount == 0 || it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType)))
        }
        check(methods.count { it.parameterCount == 0 } == 2 && methods.count { it.parameterCount == 1 } == 1) {
            "Comment translation gate contract changed"
        }
        methods.forEach { method ->
            check(Modifier.isStatic(method.modifiers) && method.returnType == Boolean::class.javaPrimitiveType)
            module.trackHook("CommentTranslationGate", method).intercept { chain ->
                val original = chain.proceed() as Boolean
                original || ConfigClient.getBoolean(KEY_COMMENT_TRANSLATE_ENABLED)
            }
        }
        }
        if (!ConfigClient.getBoolean(VideoTranslateHook.KEY_VIDEO_TRANSLATE_ENABLED) &&
            !ConfigClient.getBoolean("subtitle_style_enabled")) {
            HookRuntime.install("CaptionFallback") { CaptionFallbackHook.install(module, loader, "CaptionFallback") }
            HookRuntime.install("SubtitleFeature") { SubtitleFeature.install(module, loader, "SubtitleFeature") }
        }
        CommentPollTranslation.install(module, loader)
    }
}
