package io.github.meiyongai.toki.subtitle.hook

import android.app.Application
import android.app.Instrumentation
import android.util.Log
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/** 按同一会话的安装契约组织配置、平台 Hook、宿主符号和诊断。 */
class TokiModule : XposedModule() {
    private var processName = ""
    private lateinit var plan: HostFeaturePlan
    private val installed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var applicationHookInstalled = false
    private var application: Application? = null
    private var pendingHost: (() -> Unit)? = null
    private val activationHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var hostInfo: android.content.pm.ApplicationInfo? = null
    private var hostLoader: ClassLoader? = null
    private var activationInFlight = false
    private val activateRequested = Runnable { activateLiveFeatures() }
    private val refreshRequested = Runnable {
        if (ConfigClient.isReady) application?.let { refreshConfiguration(it, publish = false) }
    }

    private fun scheduleLiveActivation() {
        activationHandler.removeCallbacks(activateRequested)
        activationHandler.post(activateRequested)
    }

    companion object {
        private const val TAG = "TokiModule"
        const val PKG_TIKTOK_GLOBAL = "com.zhiliaoapp.musically"
        const val PKG_TIKTOK_TRILL = "com.ss.android.ugc.trill"
        private val SUPPORTED_PACKAGES = setOf(PKG_TIKTOK_GLOBAL, PKG_TIKTOK_TRILL)
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
        HookRuntime.start(processName) { tag, message -> log(Log.INFO, tag, message) }
        val ready = ConfigClient.initHost({ getRemotePreferences(ConfigClient.FRAMEWORK_GROUP) }) {
            ConfigClient.syncError?.let { HookRuntime.failure("ConfigClient", it, "配置未就绪，功能暂停") }
        }
        plan = HostFeaturePlan(ConfigClient.requestedSnapshotOrNull())
        ConfigClient.configureHostSession { plan.apply(it) }
        ConfigSchema.featureSwitches.keys.forEach { feature ->
            HookRuntime.state(feature, if (feature in plan.features || feature == "SpeedOptions" && "PlaybackSpeedHook" in plan.features)
                "等待注册" else "本次会话未安装")
        }
        HookRuntime.own("HostSession", ConfigClient.addStateListener {
            updateConfigurationStatus(it)
            activationHandler.removeCallbacks(refreshRequested)
            activationHandler.post(refreshRequested)
            scheduleLiveActivation()
        })
        HookRuntime.own("HostSession", HostScanController.session.addListener {
            if (HostScanController.session.status.phase !in setOf(HostScanPhase.SCANNING, HostScanPhase.EXTENSIONS, HostScanPhase.SAVING)) scheduleLiveActivation()
        })
        HookRuntime.onDispose("HostSession") {
            activationHandler.removeCallbacks(activateRequested)
            activationHandler.removeCallbacks(refreshRequested)
        }
        updateConfigurationStatus(if (ready) ConfigClient.snapshot() else null)
        log(Log.INFO, TAG, "模块加载成功，process=$processName ready=$ready features=${plan.features} symbols=${plan.symbols.size}")
    }

    /** 诊断使用请求配置，业务只使用会话有效配置；失效是显式状态。 */
    private fun updateConfigurationStatus(effective: ConfigSnapshot?) {
        val requested = ConfigClient.requestedSnapshotOrNull()
        if (effective == null || requested == null) return
        var restart = false
        val livePending = plan.liveCandidates(requested).toSet()
        for (feature in ConfigSchema.featureSwitches.keys) {
            val pending = plan.requiresRestart(feature, requested)
            restart = restart || pending
            HookRuntime.restartRequired(feature, pending)
            if (feature in livePending) HookRuntime.state(feature, "等待运行时注册")
            val applied = if (feature == "SpeedOptions") "PlaybackSpeedHook" in installed else feature in installed
            if (applied || !HostFeaturePlan.enabled(feature, effective)) {
                val revision = plan.appliedRevision(if (feature == "SpeedOptions") "PlaybackSpeedHook" else feature, requested)
                HookRuntime.appliedConfiguration(feature,
                    ConfigSnapshot(effective.configuration(), revision, effective.importRevision),
                    applied && HostFeaturePlan.enabled(feature, effective))
            }
        }
        HookRuntime.configurationReady(if (restart) "配置就绪，等待重新打开" else "配置已就绪")
    }

    /** 全关也保留明确的会话诊断，平台业务、资源和扫描各自按需要初始化。 */
    private fun hookApplication() {
        if (applicationHookInstalled) return
        val method = Instrumentation::class.java.getMethod("callApplicationOnCreate", Application::class.java)
        hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
            val app = chain.args[0] as Application
            bindApplication(app)
            chain.proceed()
        }
        applicationHookInstalled = true
    }

    /** LSPatch's legacy package callback already has an attached Application. */
    internal fun onLegacyApplication(app: Application) {
        applicationHookInstalled = true
        bindApplication(app)
    }

    private fun bindApplication(app: Application) {
        if (application === app) return
        application = app
        HookRuntime.attach(app)
        ConfigClient.attachHostSettings(app)
        plan = HostFeaturePlan(ConfigClient.requestedSnapshotOrNull())
        plan.platformFeatures.forEach { if (it !in installed) install(it) }
        if (processName == app.packageName) {
            val resources = ModuleResources(moduleApplicationInfo, checkNotNull(TokiModule::class.java.classLoader))
            HostSettingsEntry.attach(app, this, resources)
            HostScanController.attach(app, ModuleResources(moduleApplicationInfo,
                checkNotNull(TokiModule::class.java.classLoader)))
        }
        pendingHost?.also { pendingHost = null }?.invoke()
        if (ConfigClient.isReady) refreshConfiguration(app)
    }

    private fun refreshConfiguration(app: Application, publish: Boolean = true) {
        val attempted = installed.toList()
        for (feature in attempted) HookRuntime.configure(feature) {
            when (feature) {
                "SimHook" -> SimHook.refreshConfig(app)
                "LocaleHook" -> { LocaleHook.refreshConfig(app); LocaleHook.applyToApplication(app) }
                "TimeZoneHook" -> { TimeZoneHook.refreshConfig(app); TimeZoneHook.applyToApplication(app) }
                "GpsHook" -> GpsHook.refreshConfig(app)
                "PlaybackSpeedHook" -> PlaybackSpeedHook.refreshConfig(app)
                "CommentTranslateHook" -> CommentTranslateHook.refreshConfig(app)
                "CommentCopyHook" -> CommentCopyHook.refreshConfig(app)
                "AuthorLocationHook" -> AuthorLocationHook.refreshConfig(app)
                "ImmersiveFullScreenHook" -> ImmersiveFullScreenHook.refreshConfig(app)
                "FeedFilterHook" -> FeedFilterHook.refreshConfig(app)
                "DownloadHook" -> DownloadHook.refreshConfig(app)
                "MusicUnlockHook" -> MusicUnlockHook.refreshConfig(app)
                "StatusBarHook" -> StatusBarHook.refreshConfig(app)
                "VideoDurationAlertHook" -> VideoDurationAlertHook.refreshConfig(app)
            }
        }
        val failed = attempted - installed
        failed.forEach(plan::reject)
        if (publish || failed.isNotEmpty()) ConfigClient.refreshHostSession()
        updateConfigurationStatus(ConfigClient.snapshot())
    }

    private fun install(feature: String, loader: ClassLoader? = null) {
        HookRuntime.install(feature) {
            when (feature) {
                "SimHook" -> SimHook.init(this)
                "LocaleHook" -> LocaleHook.init(this)
                "TimeZoneHook" -> TimeZoneHook.init(this)
                "GpsHook" -> GpsHook.init(this)
                else -> installHost(feature, checkNotNull(loader))
            }
            installed.add(feature)
            HookRuntime.onDispose(feature) { installed.remove(feature) }
        }
        if (feature !in installed) {
            plan.reject(feature)
            ConfigClient.refreshHostSession()
        }
    }

    private fun installHost(feature: String, loader: ClassLoader) {
        when (feature) {
            "FeedFilterHook" -> FeedFilterHook.init(this, loader)
            "PlaybackSpeedHook" -> PlaybackSpeedHook.init(this, loader, ConfigClient.snapshot())
            "CommentTranslateHook" -> CommentTranslateHook.init(this, loader)
            "ManualCommentTranslateHook" -> ManualCommentTranslateHook.init(this, loader)
            "OfflineVideoHook" -> OfflineVideoHook.init(this, loader)
            "VideoTranslateHook" -> VideoTranslateHook.init(this, loader)
            "BackgroundAudioHook" -> BackgroundAudioHook.init(this, loader)
            "CommentCopyHook" -> CommentCopyHook.init(this, loader)
            "CommentStickerDownloadHook" -> CommentStickerDownloadHook.init(this, loader)
            "AuthorLocationHook" -> AuthorLocationHook.init(this, loader)
            "ProgressBarHook" -> ProgressBarHook.init(this, loader)
            "AutoCleanModeHook" -> AutoCleanModeHook.init(this, loader)
            "LayoutCleanupHook" -> LayoutCleanupHook.init(this, loader)
            "QuickCommentCleanupHook" -> QuickCommentCleanupHook.init(this, loader)
            "SharePanelCleanupHook" -> SharePanelCleanupHook.init(this, loader)
            "ImmersiveFullScreenHook" -> ImmersiveFullScreenHook.init(this, loader)
            "LiveSwipeLockHook" -> LiveSwipeLockHook.init(this, loader)
            "HoldSpeedGestureHook" -> HoldSpeedGestureHook.init(this, loader)
            "AutoScrollHook" -> AutoScrollHook.init(this, loader)
            "DownloadHook" -> DownloadHook.init(this, loader)
            "MusicUnlockHook" -> MusicUnlockHook.init(this, loader)
            "StatusBarHook" -> StatusBarHook.init(this, loader)
            "VideoDurationAlertHook" -> VideoDurationAlertHook.init(this, loader)
            "ScreenCaptureHook" -> ScreenCaptureHook.init(this)
            else -> error("没有功能安装契约：$feature")
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName !in SUPPORTED_PACKAGES || !param.isFirstPackage) return
        hookApplication()
        plan.platformFeatures.forEach { install(it) }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName !in SUPPORTED_PACKAGES || !param.isFirstPackage) return
        // API 28 不分发 onPackageLoaded；ready 边界依然只安装一次。
        if (!applicationHookInstalled) {
            hookApplication()
            plan.platformFeatures.forEach { install(it) }
        }
        // Install independently of feature configuration, including modern-API
        // LSPatch forks where the traditional bootstrap deliberately skips.
        val injected = runCatching { frameworkName.lowercase().let { ("lspatch" in it || "vector" in it) && "legacy bridge" !in it } }.getOrDefault(false)
        if (injected) PreloaderWaitGuard.install(this, param.classLoader, param.applicationInfo.sourceDir)
        if (application == null) {
            pendingHost = {
                plan.platformFeatures.forEach { if (it !in installed) install(it) }
                prepareHost(param)
            }
        } else prepareHost(param)
    }

    private fun prepareHost(param: PackageReadyParam) {
        hostInfo = param.applicationInfo
        hostLoader = param.classLoader
        scheduleLiveActivation()
        if (plan.directHostFeatures.isNotEmpty()) {
            val loader = param.classLoader
            plan.directHostFeatures.forEach { install(it, loader) }
        }
        if (!plan.requiresScan) {
            if (ConfigClient.isReady) updateConfigurationStatus(ConfigClient.snapshot())
            return
        }
        val info = param.applicationInfo
        val loader = param.classLoader
        if (HostSymbols.initialize(info, processName == param.packageName, plan.symbols,
                onCorePrepared = { installAdapted(loader, extensions = false) }) {
                installAdapted(loader)
            }) installAdapted(loader)
    }

    /** Serialize late installs behind actual scan completion; requested settings stay masked until admission. */
    private fun activateLiveFeatures() {
        val app = application ?: return
        val info = hostInfo ?: return
        val loader = hostLoader ?: return
        if (processName != app.packageName || !ConfigClient.isReady || activationInFlight) return
        if (HostScanController.session.status.phase in setOf(HostScanPhase.SCANNING, HostScanPhase.EXTENSIONS, HostScanPhase.SAVING)) return
        val requested = ConfigClient.requestedSnapshotOrNull() ?: return
        val candidates = plan.liveCandidates(requested).filter { it !in installed && HookRuntime.scope(it).active }
        if (candidates.isEmpty()) return
        activationInFlight = true
        candidates.forEach { HookRuntime.state(it, "正在准备运行时依赖") }
        var finished = false
        fun failed(error: Exception) {
            if (finished) return
            finished = true
            candidates.forEach {
                plan.reject(it)
                HookRuntime.failure(it, error, "运行时准备失败，未启用")
            }
            activationInFlight = false
            ConfigClient.refreshHostSession()
            scheduleLiveActivation()
        }
        fun prepared() {
            if (finished) return
            finished = true
            try {
                val latest = ConfigClient.requestedSnapshotOrNull()
                for (feature in candidates) {
                    if (latest == null || !HostFeaturePlan.enabled(feature, latest)) continue
                    if (feature == "AutoCleanModeHook" && "ProgressBarHook" !in installed) {
                        plan.reject(feature)
                        HookRuntime.failure(feature, IllegalStateException("ProgressBarHook unavailable"), "运行时依赖未就绪，未启用")
                        continue
                    }
                    if (feature !in installed) install(feature, loader)
                    if (feature in installed) check(plan.admitLive(feature)) { "Live feature admission rejected: $feature" }
                }
            } catch (error: Exception) {
                // Successfully admitted siblings remain usable; never activate an uninstalled feature.
                HookRuntime.failure("LiveFeatureActivation", error, "运行时注册未完成")
                candidates.filter { it !in installed }.forEach(plan::reject)
            } finally {
                activationInFlight = false
                ConfigClient.refreshHostSession()
                refreshConfiguration(app)
                scheduleLiveActivation()
            }
        }
        val dependencies = HostFeaturePlan.liveSymbols(candidates)
        if (dependencies.isEmpty()) { prepared(); return }
        try {
            if (HostSymbols.initialize(info, true, plan.symbols + dependencies,
                    onFailed = ::failed, onPrepared = ::prepared)) prepared()
        } catch (error: Exception) { failed(error) }
    }

    private fun installAdapted(loader: ClassLoader, extensions: Boolean = true) {
        // 清屏依赖进度条的安装成功，不能留下半套状态机。
        val ordered = plan.adaptedFeatures.sortedBy { if (it == "ProgressBarHook") 0 else 1 }
        for (feature in ordered) {
            if (!extensions && feature in setOf("VideoTranslateHook", "CommentTranslateHook", "ManualCommentTranslateHook")) continue
            if (feature == "AutoCleanModeHook" && "ProgressBarHook" !in installed) {
                HookRuntime.failure(feature, IllegalStateException("清屏所需的进度条功能未注册"), "注册失败，已撤销")
            } else if (feature !in installed && HookRuntime.scope(feature).active) install(feature, loader)
        }
        if (ConfigClient.isReady) {
            application?.let { refreshConfiguration(it) }
            updateConfigurationStatus(ConfigClient.snapshot())
        }
        if (extensions) HookRuntime.installationOutcome("功能安装检查完成；代码 ${HostSymbols.codeIdentity()}")
    }
}
