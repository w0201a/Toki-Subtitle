package io.github.meiyongai.toki.subtitle.hook

import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Request native SDR selection; never relabel HDR frames, change panel capabilities or edit brightness. */
internal object HdrPlaybackHook {
    const val KEY = "hdr_playback_disabled"
    private const val FEATURE = "HdrPlaybackHook"
    internal fun requestedOff(nativeOff: Boolean, disabled: Boolean): Boolean = nativeOff || disabled

    internal fun method(type: Class<*>, contract: Class<*>): Method {
        check(contract.isInterface && contract.isAssignableFrom(type))
        return type.getMethod("isForceHdrOff").apply {
            check(returnType == Boolean::class.javaPrimitiveType && parameterCount == 0 &&
                !Modifier.isStatic(modifiers) && !Modifier.isAbstract(modifiers))
            isAccessible = true
        }
    }

    fun init(module: XposedModule, loader: ClassLoader) {
        val target = method(Class.forName("com.ss.android.ugc.aweme.video.simplayer.PlayerConfigImpl", false, loader),
            Class.forName("com.ss.android.ugc.aweme.video.config.ISimPlayerConfig", false, loader))
        module.trackHook(FEATURE, target).intercept { chain ->
            val native = chain.proceed() as Boolean
            val disabled = ConfigClient.getBoolean(KEY)
            val off = requestedOff(native, disabled)
            HookRuntime.count(FEATURE, if (disabled) "原生选流请求关闭HDR" else "保留原生HDR设置")
            // A return value proves only selection policy, not that a cached/playing decoder changed.
            HookRuntime.detail(FEATURE, "原生选流读取：请求关闭HDR=$disabled 返回关闭HDR=$off；当前画面需重新加载后验收")
            off
        }
        HookRuntime.subscribe(FEATURE) { snapshot ->
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
        }
        HookRuntime.detail(FEATURE, "原生HDR关闭策略已接入；重新进入视频使选流重读，已有播放器或预加载仍需实测")
    }
}
