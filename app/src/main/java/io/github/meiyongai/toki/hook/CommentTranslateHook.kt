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
        val gate = HostSymbols.resolve(loader, HostSymbol.COMMENT_TRANSLATION)
        val methods = listOf(gate.getDeclaredMethod("LIZJ"), gate.getDeclaredMethod("LIZIZ"),
            gate.getDeclaredMethod("LIZ", Boolean::class.javaPrimitiveType))
        methods.forEach { method ->
            check(Modifier.isStatic(method.modifiers) && method.returnType == Boolean::class.javaPrimitiveType)
            module.trackHook("CommentTranslateHook", method).intercept { chain ->
                val original = chain.proceed() as Boolean
                original || ConfigClient.getBoolean(KEY_COMMENT_TRANSLATE_ENABLED)
            }
        }
        if (!ConfigClient.getBoolean(VideoTranslateHook.KEY_VIDEO_TRANSLATE_ENABLED) &&
            !ConfigClient.getBoolean("subtitle_style_enabled")) {
            CaptionFallbackHook.install(module, loader, "CommentTranslateHook")
            SubtitleFeature.install(module, loader, "CommentTranslateHook")
        }
        CommentPollTranslation.install(module, loader)
    }
}
