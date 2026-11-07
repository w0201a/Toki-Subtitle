package io.github.meiyongai.toki.subtitle.util

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LSPosed 框架激活状态与框架元数据载体。
 *
 * @property isActivated 模块是否已成功绑定 LSPosed 框架服务。
 * @property details 框架详细信息展示文本（框架名、版本号、构建号及支持的 API 等级）。
 */
data class LSPosedStatus(
    val isActivated: Boolean = false,
    val details: String = "None",
    val configurationSynced: Boolean = false
)

/**
 * LSPosed 框架服务监听与状态管理器。
 *
 * 负责通过标准现代化接口 [XposedServiceHelper] 监听并绑定系统下发的 [XposedService]，
 * 实时同步并维护模块当前运行时的激活状态与框架版本信息。
 */
object LSPosedStatusHelper {

    private val _status = MutableStateFlow(LSPosedStatus(isActivated = false, details = "None"))

    /**
     * 对外暴露的只读响应式框架激活状态流。
     */
    val status: StateFlow<LSPosedStatus> = _status.asStateFlow()

    private var isInitialized = false
    private val connection = FrameworkServiceConnection()
    private var pull: FrameworkServicePullClient? = null
    private var settingsSubscription: AutoCloseable? = null

    /** Ask an existing manager to restore this process's own service; never launch a host. */
    @Synchronized fun requestConnection(context: android.content.Context) {
        if (_status.value.isActivated && ConfigClient.syncError == null) return
        val client = pull ?: FrameworkServicePullClient(context.applicationContext,
            { _status.value.isActivated && ConfigClient.syncError == null }, { android.util.Log.i("TokiFramework", it) }).also { pull = it }
        client.request()
    }

    /**
     * 注册并启动 LSPosed 服务监听器。
     *
     * @return Unit；无入参。
     * Callers: TokiApplication.onCreate、MainActivity.onCreate。
     */
    @Synchronized fun init() {
        if (isInitialized) return
        isInitialized = true
        settingsSubscription = ConfigClient.addStateListener {
            val previous = _status.value
            if (previous.isActivated) _status.value = previous.copy(configurationSynced = ConfigClient.syncError == null)
        }

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            /** 发布当前完整配置并更新激活信息。@param service 框架连接。@return Unit。Callers: XposedServiceHelper。 */
            override fun onServiceBind(service: XposedService) {
                connection.bind(service) {
                    try {
                        // Read remote metadata before claiming a live connection; dead Binder calls can throw.
                        val frameworkName = service.frameworkName.ifBlank { "LSPosed" }
                        val frameworkVersion = service.frameworkVersion.ifBlank { "unknown" }
                        val details = "$frameworkName $frameworkVersion (${service.frameworkVersionCode}), API ${service.apiVersion}"
                        val synced = ConfigClient.connectFramework { service.getRemotePreferences(ConfigClient.FRAMEWORK_GROUP) }
                        // A live Binder, acknowledged settings, and host execution are separate facts.
                        _status.value = LSPosedStatus(isActivated = true, details = details, configurationSynced = synced)
                        if (synced) pull?.cancel()
                        else android.util.Log.w("TokiFramework", "Framework connected, configuration not acknowledged")
                    } catch (error: Exception) {
                        connection.died(service) { disconnected() }
                        android.util.Log.e("TokiFramework", "框架连接建立失败，保留本地设置", error)
                    }
                }
            }

            /** Ignore death of a replaced connection, but never pretend a genuinely dead service is active. */
            override fun onServiceDied(service: XposedService) {
                connection.died(service) { disconnected() }
            }
        })
    }
    private fun disconnected() {
        ConfigClient.disconnectFramework()
        _status.value = LSPosedStatus(isActivated = false, details = "None")
    }

}
