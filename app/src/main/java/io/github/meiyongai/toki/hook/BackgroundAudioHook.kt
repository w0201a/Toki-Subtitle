package io.github.meiyongai.toki.subtitle.hook

import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.provider.ExecutionEvidence

/** 解锁原生后台音频实验；播放启停及媒体会话仍由宿主管理。 */
object BackgroundAudioHook {
    const val KEY_ENABLED = "background_audio_unlock"

    /**
     * 放行后台音频实验，保留已启用的原生实验分组。
     * @param module 当前模块。
     * @param loader 宿主类加载器。
     * @return Unit。
     * Callers: TokiModule.installHost。
     */
    fun init(module: XposedModule, loader: ClassLoader) {
        val owner = HostSymbols.resolve(loader, HostSymbol.BACKGROUND_AUDIO)
        val group = owner.getDeclaredMethod(HostSymbols.member(HostSymbol.BACKGROUND_AUDIO, "group"))
        module.trackHook("BackgroundAudioHook", group).intercept { chain ->
            val original = chain.proceed() as Int
            val snapshot = ConfigClient.snapshot()
            val enabled = snapshot.boolean(KEY_ENABLED)
            val result = if (enabled && original == 0) 2 else original
            HookRuntime.execution("BackgroundAudioHook", ExecutionEvidence(ExecutionEvidence.Scope.AUDIO, ExecutionEvidence.Action.READ,
                ExecutionEvidence.Outcome.READ_RETURNED, snapshot.revision, enabled,
                mapOf(ExecutionEvidence.Metric.ADJUSTED to if (result != original) 1L else 0L)))
            result
        }
    }
}
