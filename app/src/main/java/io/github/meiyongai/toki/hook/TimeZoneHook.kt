package io.github.meiyongai.toki.subtitle.hook

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.time.ZoneId
import java.util.TimeZone

/**
 * 系统时区（TimeZone）伪装 Hook 核心处理器。
 *
 * 拦截目标应用进程内对 [java.util.TimeZone]、[java.time.ZoneId] 以及 [android.icu.util.TimeZone] 的时区获取逻辑，
 * 支持跟随目标地区自动计算标准时区标识（如 Asia/Tokyo），亦支持用户全自由度自定义指定。
 */
object TimeZoneHook {

    private const val TAG = "TokiTimeZoneHook"

    private class Values(val java: TimeZone, val zoneId: ZoneId, val icu: android.icu.util.TimeZone)
    @Volatile private var current: Values? = null

    /**
     * 从跨进程配置服务同步最新设定的时区伪装配置。
     *
     * 自动研判是否开启“跟随地区设置”，若开启则根据目标地区 ISO 代号反查推荐时区；
     * 否则读取用户自定义指定的 IANA 时区标识。
     *
     * @param context 宿主目标应用的上下文对象。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.hook.TokiModule.hookApplication`: 宿主启动时触发配置加载。
     */
    fun refreshConfig(context: Context) {
        ConfigClient.init(context)
        applyConfiguration(ConfigClient.snapshot())
    }

    internal fun applyConfiguration(config: ConfigSnapshot) {
        val id = EnvironmentRuntimeConfig.timeZone(config)
        // Construct all representations before publishing one complete payload.
        current = id?.let {
            val zone = TimeZone.getTimeZone(it)
            Values(zone, zone.toZoneId(), android.icu.util.TimeZone.getTimeZone(it))
        }
        EnvironmentApplicationStatus.record("TimeZoneHook", config, current != null)
        HookRuntime.detail("TimeZoneHook", "运行时配置已处理；revision=${config.revision}; active=${current != null}; 不写进程全局默认值；关闭后读取走原路径，宿主已有时间对象不强制重建")
    }

    /**
     * 确认当前读取策略；不再写入宿主进程全局默认时区。
     *
     * @param app 宿主 Application 实例。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.hook.TokiModule.hookApplication`: 宿主 Application 创建时注入生效。
     */
    fun applyToApplication(app: Application) {
        // No global mutation to undo: null makes each intercepted getter call downstream.
        Log.i(TAG, "时区读取策略已刷新，未写进程默认值")
    }

    /**
     * 获取当前时区伪装是否处于激活状态。
     *
     * @return 激活返回 true，停用返回 false。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.hook.TimeZoneHook.hookTimeZoneMethods`: 方法拦截分发时校验。
     */
    fun isFeatureEnabled(): Boolean = current != null

    internal fun javaOverride(): TimeZone? = current?.java?.clone() as? TimeZone
    internal fun zoneOverride(): ZoneId? = current?.zoneId
    internal fun icuOverride(): android.icu.util.TimeZone? = current?.icu?.clone() as? android.icu.util.TimeZone


    /**
     * 注册 Java 与 ICU 时区相关的 Hook 拦截器。
     *
     * @param module 当前注入的 [XposedModule] 实例。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.hook.TimeZoneHook.init`: 模块初始化流程。
     */
    private fun hookTimeZoneMethods(module: XposedModule) {
        // 1. Hook java.util.TimeZone.getDefault()
        val tzClass = TimeZone::class.java
        for (method in tzClass.declaredMethods) {
            if (Modifier.isStatic(method.modifiers) && method.name == "getDefault" && method.parameterCount == 0) {
                module.trackHook("TimeZoneHook", method).intercept { chain ->
                    val value = javaOverride()
                    if (value != null) {
                        value
                    } else {
                        chain.proceed()
                    }
                }
            }
        }

        // 2. Hook java.time.ZoneId.systemDefault()
        val zoneIdClass = ZoneId::class.java
        for (method in zoneIdClass.declaredMethods) {
            if (Modifier.isStatic(method.modifiers) && method.name == "systemDefault" && method.parameterCount == 0) {
                module.trackHook("TimeZoneHook", method).intercept { chain ->
                    val value = zoneOverride()
                    if (value != null) {
                        value
                    } else {
                        chain.proceed()
                    }
                }
            }
        }

        // 3. Hook android.icu.util.TimeZone.getDefault()
        val icuTzClass = android.icu.util.TimeZone::class.java
        for (method in icuTzClass.declaredMethods) {
            if (Modifier.isStatic(method.modifiers) && method.name == "getDefault" && method.parameterCount == 0) {
                module.trackHook("TimeZoneHook", method).intercept { chain ->
                    val value = icuOverride()
                    if (value != null) {
                        value
                    } else {
                        chain.proceed()
                    }
                }
            }
        }
        Log.i(TAG, "TimeZone 与 ZoneId 系统方法拦截器挂载成功")
    }

    /**
     * 模块初始化入口，安装系统时区伪装拦截机制。
     *
     * @param module 当前注入的 [XposedModule] 实例。
     *
     * Callers:
     * - `io.github.meiyongai.toki.subtitle.hook.TokiModule.onPackageLoaded`: 目标包加载时触发。
     */
    fun init(module: XposedModule) {
        HookRuntime.onDispose("TimeZoneHook") { current = null; EnvironmentApplicationStatus.clear("TimeZoneHook") }
        hookTimeZoneMethods(module)
        Log.i(TAG, "系统时区伪装子系统就绪")
    }
}
