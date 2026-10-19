package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.view.Window
import android.view.WindowManager
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient

/** Opt-in capture compatibility for the host's main window, independent of DEX adaptation. */
internal object ScreenCaptureHook {
    const val KEY = "screen_capture_compatibility"
    private const val OWNER = "ScreenCaptureHook"
    private const val MAIN = "com.ss.android.ugc.aweme.splash.SplashActivity"
    private fun accepts(window: Window): Boolean = window.context is Activity &&
        window.context.javaClass.name == MAIN && ConfigClient.getBoolean(KEY)

    internal fun compatibleFlags(flags: Int, enabled: Boolean): Int =
        if (enabled) flags and WindowManager.LayoutParams.FLAG_SECURE.inv() else flags

    fun init(module: XposedModule) {
        module.trackHook(OWNER, Window::class.java.getMethod("setFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType))
            .intercept { chain ->
                val window = chain.thisObject as Window
                if (!accepts(window)) return@intercept chain.proceed()
                chain.proceed(arrayOf(compatibleFlags(chain.args[0] as Int, true), chain.args[1]))
            }
        module.trackHook(OWNER, Window::class.java.getMethod("setAttributes", WindowManager.LayoutParams::class.java))
            .intercept { chain ->
                val window = chain.thisObject as Window
                val attributes = chain.args[0] as? WindowManager.LayoutParams
                if (!accepts(window) || attributes == null) return@intercept chain.proceed()
                val copy = WindowManager.LayoutParams().apply { copyFrom(attributes); flags = compatibleFlags(flags, true) }
                chain.proceed(arrayOf(copy))
            }
        module.trackHook(OWNER, Activity::class.java.getDeclaredMethod("onResume")).intercept { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as Activity
            if (accepts(activity.window)) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            result
        }
    }
}
