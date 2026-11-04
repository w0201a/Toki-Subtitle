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
        check(HdrSelectionContract.verify(checkNotNull(HookRuntime.hostContext))) {
            "HDR selection code does not match the inspected 47.6.3 player; no experimental gate is changed"
        }
        val contract = HdrSelectionContract(loader)
        val selection = HdrSelectionScope()
        val target = method(Class.forName("com.ss.android.ugc.aweme.video.simplayer.PlayerConfigImpl", false, loader),
            Class.forName("com.ss.android.ugc.aweme.video.config.ISimPlayerConfig", false, loader))
        module.trackHook(FEATURE, target).intercept { chain ->
            val native = chain.proceed() as Boolean
            val call = selection.call
            if (call != null) call.policyReads++
            val disabled = call?.disabled ?: ConfigClient.getBoolean(KEY)
            val off = requestedOff(native, disabled)
            HookRuntime.count(FEATURE, if (disabled) "原生选流请求关闭HDR" else "保留原生HDR设置")
            off
        }
        module.trackHook(FEATURE, contract.gate).intercept { chain ->
            selection.gate(chain.proceed() as Boolean)
        }
        module.trackHook(FEATURE, contract.select).intercept { chain ->
            val disabled = ConfigClient.getBoolean(KEY)
            // Off is exactly the native route. No scoped experiment override or inspection is needed.
            if (!disabled) return@intercept chain.proceed()
            val request = chain.args[0]
            val before = try { contract.counts(request) } catch (_: Exception) { null }
            selection.selecting(true) { call ->
                val result = chain.proceed()
                val after = try { contract.counts(request) } catch (_: Exception) { null }
                HookRuntime.count(FEATURE, "实际选流已进入关闭HDR范围")
                if (call.policyReads == 0) HookRuntime.count(FEATURE, "选流提前返回或策略仍未读取")
                if (before != null && before.sdr == 0 && before.hdr > 0)
                    HookRuntime.count(FEATURE, "未观察到SDR候选，不冒报降级成功")
                HookRuntime.detail(FEATURE, "选流回执：策略读取=${call.policyReads} 外层门读取=${call.gateReads} " +
                    "候选前=$before 候选后=$after；只证明选流返回，不代表解码／屏幕已变SDR")
                result
            }
        }
        // ART may inline the tiny policy/experiment getters into their callers. Recompile neither
        // host code nor global experiments: deoptimize only the inspected callers for live hooks.
        module.deoptimize(target)
        module.deoptimize(contract.select)
        HookRuntime.subscribe(FEATURE) { snapshot ->
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
        }
        HookRuntime.detail(FEATURE, "已接入核实选流边界与局部门控，去除已编译调用绕过；无SDR候选／已有缓存输出仍需实测")
    }
}
