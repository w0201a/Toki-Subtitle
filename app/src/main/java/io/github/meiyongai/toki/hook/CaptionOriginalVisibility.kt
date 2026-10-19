package io.github.meiyongai.toki.subtitle.hook

import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient

/** Keep original frames available independently of translator/track/overlay readiness. */
internal object CaptionOriginalVisibility {
    internal fun hidden(original: Boolean, translating: Boolean, originalFrame: Any?): Boolean =
        original && !(translating && originalFrame != false)

    fun install(module: XposedModule, loader: ClassLoader) = HookRuntime.install("CaptionOriginalVisibility") {
        val method = ExtensionSymbols.captionOriginalVisibility(loader)
            ?: loader.adaptiveClass("X.08ZA").adaptiveDeclaredMethod("LJJIJIIJI",
                loader.adaptiveClass("com.ss.android.ugc.aweme.feed.model.Aweme"), java.lang.Boolean::class.java)
        module.trackHook("CaptionOriginalVisibility", method).intercept { chain ->
            hidden(chain.proceed() as Boolean, ConfigClient.getBoolean("video_translate_enabled"), chain.args[1])
        }
    }
}
