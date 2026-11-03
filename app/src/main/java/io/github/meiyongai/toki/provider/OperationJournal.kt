package io.github.meiyongai.toki.subtitle.provider

import java.time.Instant

/** Typed operation metadata only; identifiers are generated locally, never video IDs, account IDs, URLs or paths. */
internal class OperationJournal(private val emit: (String) -> Unit, private val capacity: Int = 64,
    private val wall: () -> Long = System::currentTimeMillis, private val clock: () -> Long = System::nanoTime) {
    enum class Kind(val title: String) { OFFLINE_EXPORT("离线视频导出"), STICKER_EXPORT("贴图导出／转换") }
    enum class Step(val title: String) {
        QUEUED("进入工作队列"), CODE_CHECK("核对宿主代码"), CANDIDATES("读取候选数据"), CACHE_LOOKUP("查找本地缓存"),
        SAMPLE_CHECK("校验本地轨道与样本"), PLAN_READY("已确定完整导出方案"), MUX("写入单个视频文件"), READBACK("回读目标文件"),
        PUBLISH("发布到相册"), SOURCE_FETCH("读取贴图源数据"), ANIMATION_PARSE("解析动画帧与限制"),
        ANIMATION_DECODE("打开逐帧解码器"), ANIMATION_ENCODE("逐帧合成与编码"), FINISHED("处理结束")
    }
    enum class Result(val title: String) { RUNNING("进行中，尚无结果"), SUCCESS("本次操作成功"), UNAVAILABLE("当前条件不可用"), FAILED("本次失败"), CANCELLED("本次已取消") }
    enum class Reason(val title: String, val suggestion: String) {
        NONE("无", "无需处理；本次成功不代表所有场景都已验证。"),
        CODE_MISMATCH("宿主代码不匹配", "检查宿主版本和适配状态；不要把版本号相同当成代码一致。"),
        CACHE_MISSING("没有找到对应本地缓存", "确认视频已下载供离线播放；导出不会联网补视频。"),
        CACHE_INCOMPLETE("缓存／必要音轨不完整或无法配对", "确认离线下载完成；多音轨或缺初始化信息可能尚未支持。"),
        UNSUPPORTED("编码、结构或设备接口不受支持", "查看阶段及错误类型；不要改后缀或把静态预览当成功。"),
        CANCELLED("页面／设置变化或任务取消", "保持目标页面与功能开启，确认后重试。"),
        SOURCE_CHANGED("源文件或结果与校验信息不一致", "保留原缓存，重新选择视频后重试；查看回读校验阶段。"),
        OUTPUT_FAILED("目标写入／封装／发布失败", "检查剩余空间、相册／文档接口；未完成记录不会被自动发布。"),
        QUEUE_FULL("工作队列已满或停止", "等待当前任务结束后重试。"),
        MEMORY_LIMIT("图片／样本内存或资源限制", "改用较小源或保留原文件；没有自动以首帧替代动画。"),
        REQUEST_FAILED("贴图源读取或转换失败", "检查网络、源格式和动画限制；模块日志不记录源地址。"),
        UNKNOWN("尚未分类的异常", "查看最近阶段和技术错误类型；不要只根据已注册判断功能可用。")
    }
    private data class StageRecord(val step: Step, val atMs: Long, val stats: Map<String, Long>)
    private data class Record(val id: Long, val kind: Kind, val startWall: Long, val start: Long,
        val history: MutableList<StageRecord> = mutableListOf(),
        var step: Step = Step.QUEUED, var result: Result = Result.RUNNING, var reason: Reason = Reason.NONE,
        var stats: Map<String, Long> = emptyMap(), var error: String = "", var ended: Long? = null,
        var attemptFailures: Int = 0, var lastAttemptError: String = "")
    private val records = linkedMapOf<Long, Record>()
    private var sequence = 0L
    private var overwritten = 0L
    init { require(capacity in 1..128) }
    inner class Run internal constructor(private val id: Long) {
        fun stage(step: Step, stats: Map<String, Long> = emptyMap()) = update(id, step, stats, null, Reason.NONE, "")
        fun attemptFailed(error: Throwable) = noteAttempt(id, error.javaClass.simpleName)
        fun failed(error: Throwable, cancelled: Boolean = false) = fail(id, error.javaClass.simpleName, cancelled)
        fun finish(result: Result, reason: Reason = Reason.NONE, errorType: String = "") {
            require(result != Result.RUNNING)
            update(id, null, emptyMap(), result, reason, errorType)
        }
    }
    @Synchronized fun start(kind: Kind): Run {
        while (records.size >= capacity) { records.remove(records.keys.first()); overwritten++ }
        val record = Record(++sequence, kind, wall(), clock()); records[record.id] = record
        record.history += StageRecord(Step.QUEUED, 0, emptyMap())
        emitSafely(event(record)); return Run(record.id)
    }
    @Synchronized private fun update(id: Long, step: Step?, stats: Map<String, Long>, result: Result?, reason: Reason, error: String) {
        val record = records[id] ?: return
        if (record.result != Result.RUNNING) return
        require(stats.size <= 8 && stats.all { (key, value) -> key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}")) && value >= 0 })
        if (step != null) {
            record.step = step; record.stats = stats.toMap()
            if (record.history.size == 16) record.history.removeAt(0)
            record.history += StageRecord(step, elapsed(record), record.stats)
        }
        if (result != null) {
            record.result = result; record.reason = reason
            record.error = error.takeIf { it.matches(Regex("[A-Za-z0-9_.$]{0,120}")) }.orEmpty()
            record.ended = clock()
        }
        emitSafely(event(record))
    }
    @Synchronized private fun noteAttempt(id: Long, type: String) {
        val record = records[id] ?: return
        if (record.result != Result.RUNNING) return
        record.attemptFailures++
        record.lastAttemptError = type.takeIf { it.matches(Regex("[A-Za-z0-9_.$]{0,120}")) }.orEmpty()
        emitSafely(event(record) + " 本候选失败，若有其他候选会继续；不是终态。")
    }
    private fun emitSafely(value: String) { try { emit(value) } catch (_: Exception) { /* Logging must not fail a media job. */ } }
    @Synchronized private fun fail(id: Long, type: String, cancelled: Boolean) {
        val record = records[id] ?: return
        val reason = if (cancelled) Reason.CANCELLED else if (type == "OutOfMemoryError") Reason.MEMORY_LIMIT else when (record.step) {
            Step.CODE_CHECK -> Reason.CODE_MISMATCH
            Step.CACHE_LOOKUP -> Reason.CACHE_MISSING
            Step.CANDIDATES, Step.SAMPLE_CHECK, Step.ANIMATION_PARSE, Step.ANIMATION_DECODE, Step.ANIMATION_ENCODE -> Reason.UNSUPPORTED
            Step.READBACK -> Reason.SOURCE_CHANGED
            Step.MUX, Step.PUBLISH -> Reason.OUTPUT_FAILED
            Step.SOURCE_FETCH -> Reason.REQUEST_FAILED
            else -> Reason.UNKNOWN
        }
        update(id, null, emptyMap(), if (cancelled) Result.CANCELLED else Result.FAILED, reason, type)
    }
    private fun elapsed(record: Record): Long = (((record.ended ?: clock()) - record.start) / 1_000_000).coerceAtLeast(0)
    private fun event(record: Record): String = "op=${record.kind.name}-${record.id} [${record.result.title}] 阶段=${record.step.title} " +
        "耗时=${elapsed(record)}ms 原因=${record.reason.title} 候选失败次数=${record.attemptFailures} 上次候选错误=${record.lastAttemptError.ifEmpty { "无" }} " + record.stats.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" } +
        if (record.error.isEmpty()) "" else " 错误类型=${record.error}"
    @Synchronized fun render(): String = buildString {
        appendLine("操作摘要：只记录本进程本次会话；进程退出后不保留，须复现后及时导出。历史覆盖=$overwritten")
        if (records.isEmpty()) appendLine("没有记录到导出／转换操作；不能据此断言功能正常或故障。")
        records.values.toList().asReversed().forEach { record ->
            appendLine(event(record))
            appendLine("  开始(UTC)=${Instant.ofEpochMilli(record.startWall)} | 功能=${record.kind.title}")
            appendLine("  最近阶段（最多16条，数字仅为数量／字节等元数据）：")
            record.history.forEach { stage ->
                appendLine("    +${stage.atMs}ms ${stage.step.title} " + stage.stats.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" })
            }
            appendLine("  建议：" + if (record.result == Result.RUNNING) "尚未收到终态；若长期不变，保留宿主运行并重新导出，对照最后阶段。" else record.reason.suggestion)
        }
    }
}

internal object OperationDiagnostics {
    private val journal = OperationJournal({ TrialLog.add("Operation", it) })
    fun start(kind: OperationJournal.Kind): OperationJournal.Run = journal.start(kind)
    fun snapshot(): String {
        val value = journal.render()
        return if (value.length <= 48 * 1024) value else value.take(48 * 1024) + "\n[操作摘要达到48Ki字符上限，较旧记录未完整展示]"
    }
}
