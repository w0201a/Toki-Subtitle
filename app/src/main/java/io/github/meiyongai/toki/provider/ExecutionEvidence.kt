package io.github.meiyongai.toki.subtitle.provider

import android.os.Bundle

/** Only enumerated technical evidence; no arbitrary text, media/account identifiers, addresses or paths. */
internal class ExecutionEvidence(
    val scope: Scope, val action: Action, val outcome: Outcome, val revision: Long?, val enabled: Boolean?,
    metrics: Map<Metric, Long> = emptyMap()
) {
    enum class Scope(val feature: String, val title: String, val boundary: String) {
        PLAYER("PlaybackSpeedHook", "固定倍速", "只观察请求及调用返回，不测量真实播放速率；未观察实例不猜原速，并发结果需再确认。"),
        MENU("SpeedOptions", "倍速菜单", "只影响后续菜单数据源读取；已打开且缓存列表的菜单可能需重新打开。"),
        TEMPLATE("TemplateCleanupHook", "模板／特效", "只覆盖已核实类型和已观察根；根数量或刷新返回不证明完整截图覆盖／像素效果。"),
        AUDIO("BackgroundAudioHook", "后台音频读取", "只证明这次实验getter返回；不重建宿主缓存，也不证明后台播放已启动或停止。")
    }
    enum class Action(val title: String) { APPLY("应用设置"), RELEASE("关闭／失效恢复"), READ("原生数据读取"), NATIVE_REQUEST("原生调速回调"), BIND("视图绑定／刷新") }
    enum class Outcome(val title: String) {
        CONFIG_MISSING("配置失效，不能判定最新设置已生效"), CODE_PENDING("等待宿主代码核验"), CODE_UNSUPPORTED("宿主代码未匹配，此路径不可用"),
        NO_TARGET("缺少已观察目标，未强制猜测或修改当前实例"), NEXT_READ("配置已收到，等待后续原生菜单读取"),
        COMMANDS_RETURNED("调用已返回，实际效果仍待确认"), NO_NEW_COMMAND("本次没有新命令；不能据此断言实际效果正常"),
        PARTIAL("有失败、并发冲突或暂缓，结果尚未完全确认"), READ_RETURNED("本次原生数据读取已返回"),
        NATIVE_RETURNED("本次原生setter已返回并有同实例观察记录"), UNTRACKED_NATIVE("原生请求已放行，但未建立可恢复记录"),
        RULE_REFRESHED("已处理已知根的规则刷新；全页面覆盖未确认"), DISABLED("配置关闭；恢复／放行证据见统计")
    }
    enum class Metric(val title: String) {
        KNOWN("已观察实例"), COMMANDS("调用返回次数"), UNCHANGED("记录中无需新写入"), DEFERRED("暂缓或观察已变化"),
        FAILED("调用失败"), RACED("并发结果未确认"), FIELD_FAILURES("字段恢复异常组"), TRACKED("本次实例有记录"),
        OPTIONS("返回自定义档位数"), ROOTS("已观察目标根"), ATTACHED("已挂载目标根"), ADJUSTED("本次改写实验值")
    }
    val metrics = metrics.toMap()
    init { require(revision == null || revision >= 0); require(this.metrics.values.all { it >= 0 }) }
    fun signature(): String = listOf(scope.name, action.name, outcome.name, revision, enabled,
        metrics.entries.sortedBy { it.key.ordinal }.joinToString { it.key.name + "=" + it.value }).joinToString("|")
    fun summary(): String {
        val partial = listOf(Metric.FAILED, Metric.RACED, Metric.DEFERRED, Metric.FIELD_FAILURES).any { (metrics[it] ?: 0) > 0 }
        return "${scope.title}／${action.title}：${if (partial) Outcome.PARTIAL.title else outcome.title}；" +
            metrics.entries.sortedBy { it.key.ordinal }.joinToString { it.key.title + "=" + it.value }
    }
    fun encode(): Bundle = Bundle().apply {
        putInt("format", 1); putString("scope", scope.name); putString("action", action.name); putString("outcome", outcome.name)
        revision?.let { putLong("revision", it) }; enabled?.let { putBoolean("enabled", it) }
        putBundle("metrics", Bundle().apply { metrics.forEach { (key, value) -> putLong(key.name, value) } })
    }
    companion object {
        /** Unknown future or malformed payloads stay unknown, never become a successful default. */
        @Suppress("DEPRECATION")
        fun decode(value: Bundle?): ExecutionEvidence? {
            if (value == null || value.get("format") != 1) return null
            val scope = Scope.entries.firstOrNull { it.name == value.get("scope") } ?: return null
            val action = Action.entries.firstOrNull { it.name == value.get("action") } ?: return null
            val outcome = Outcome.entries.firstOrNull { it.name == value.get("outcome") } ?: return null
            val revision = if (value.containsKey("revision")) value.get("revision") as? Long ?: return null else null
            val enabled = if (value.containsKey("enabled")) value.get("enabled") as? Boolean ?: return null else null
            val raw = value.get("metrics") as? Bundle ?: return null
            if (raw.size() > Metric.entries.size || revision != null && revision < 0) return null
            val metrics = linkedMapOf<Metric, Long>()
            for (key in raw.keySet()) {
                val metric = Metric.entries.firstOrNull { it.name == key } ?: return null
                val number = raw.get(key) as? Long ?: return null
                if (number < 0) return null
                metrics[metric] = number
            }
            return ExecutionEvidence(scope, action, outcome, revision, enabled, metrics)
        }
        fun describe(value: Bundle?, currentRevision: Long?, expectedFeature: String? = null): String {
            val evidence = decode(value) ?: return "未收到可识别的执行证据；不把配置开关或注册数量当作业务成功。"
            if (expectedFeature != null && evidence.scope.feature != expectedFeature)
                return "功能与执行证据不匹配，不能用于证明该功能可用。"
            val version = when {
                evidence.revision == null -> "没有有效配置版本，不能证明当前设置已生效。"
                currentRevision == null -> "当前请求配置版本未报告，无法确认这条证据是否对应当前设置。"
                evidence.revision != currentRevision -> "旧配置执行证据：记录版本=${evidence.revision}，当前=$currentRevision；不用于证明当前设置已生效。"
                else -> "配置版本=${evidence.revision}。"
            }
            return evidence.summary() + System.lineSeparator() + "    " + version + evidence.scope.boundary
        }
    }
}
