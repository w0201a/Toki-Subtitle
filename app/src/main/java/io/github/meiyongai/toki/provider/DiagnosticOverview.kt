package io.github.meiyongai.toki.subtitle.provider

import android.os.Bundle
import java.time.Instant

/** Human-readable view of received evidence. Unknown/unobserved is never promoted to working. */
internal object DiagnosticOverview {
    private val titles = mapOf(
        "OfflineVideoHook" to "离线视频导出／已有本地操作", "CommentStickerDownloadHook" to "贴图下载与转换", "LayoutCleanupHook" to "布局净化（含旧模板按钮映射）",
        "TemplateCleanupHook" to "模板／特效业务锚点隐藏",
        "PageCleanupHook" to "搜索／收件箱／直播既有净化", "QuickCommentCleanupHook" to "快速评论隐藏", "SharePanelCleanupHook" to "分享项隐藏",
        "VideoTranslateHook" to "字幕显示与翻译", "CommentTranslateHook" to "评论翻译", "ManualCommentTranslateHook" to "手动评论翻译",
        "CachedCommentTranslateHook" to "缓存评论主动补译", "AuthorLocationHook" to "作者国家显示", "PlaybackSpeedHook" to "播放倍速", "SpeedOptions" to "倍速菜单",
        "ImmersiveFullScreenHook" to "沉浸与视频显示模式", "ProgressBarHook" to "进度条", "AutoCleanModeHook" to "播放净屏",
        "SimHook" to "国家／SIM设置", "LocaleHook" to "语言设置", "TimeZoneHook" to "时区设置", "GpsHook" to "位置设置",
        "BackgroundAudioHook" to "后台音频", "HoldSpeedGestureHook" to "长按倍速锁定手势", "LiveSwipeLockHook" to "直播滑动锁定",
        "AutoScrollHook" to "自动滚动", "DownloadHook" to "在线下载设置", "MusicUnlockHook" to "音乐限制设置", "StatusBarHook" to "状态栏",
        "ScreenCaptureHook" to "截图兼容", "VideoDurationAlertHook" to "视频时长提示", "FeedFilterHook" to "信息流过滤")
    private fun bool(row: Bundle, key: String): String = if (!row.containsKey(key)) "未知／未报告" else if (row.getBoolean(key)) "开" else "关"
    private fun long(row: Bundle, key: String): String = if (row.containsKey(key)) row.getLong(key).toString() else "未报告"
    private fun text(value: String?, limit: Int = 1000): String = value?.let { DiagnosticText.line(it, limit) }.orEmpty().ifEmpty { "未报告" }
    fun featureState(row: Bundle?): String {
        if (row == null) return "未收到该功能报告"
        val state = row.getString("state").orEmpty()
        if (!row.getString("error").isNullOrBlank() || !row.getString("errorStatus").isNullOrBlank() ||
            state in setOf("注册失败，已撤销", "部分功能已注册", "子功能均未安装", "地区环境预设缺失")) return "有异常／部分不可用"
        if (row.getBoolean("restartRequired")) return "已保存，仍需重开宿主"
        if (state.contains("正在") || state.contains("等待") || state in setOf("注册中", "环境配置待应用")) return "准备中／等待条件"
        if (!row.containsKey("requestedEnabled") && !row.containsKey("enabled")) return "请求状态未知"
        val requested = if (row.containsKey("requestedEnabled")) row.getBoolean("requestedEnabled") else row.getBoolean("enabled")
        if (!requested) return when {
            !row.containsKey("appliedEnabled") -> "请求关闭，尚未收到关闭／恢复回执（不代表已停用）"
            row.getBoolean("appliedEnabled") -> "请求关闭，宿主仍报告已启用，需核查"
            else -> "用户未开启（不是故障证明）"
        }
        if (!row.containsKey("appliedEnabled")) return "请求开启，尚未确认应用"
        if (!row.getBoolean("appliedEnabled")) return "请求开启，但未实际应用"
        if (row.getInt("registered") <= 0 || state != "已注册") return "状态待核查，不能判定可用"
        return "已注册并报告应用；实际业务结果另看操作摘要"
    }
    fun render(diagnostics: Bundle, now: Long = System.currentTimeMillis()): String = buildString {
        appendLine("# Toki 诊断速览")
        appendLine("导出时间(UTC)：${Instant.ofEpochMilli(now)}")
        appendLine("管理端版本：${io.github.meiyongai.toki.subtitle.BuildConfig.VERSION_NAME} / ${io.github.meiyongai.toki.subtitle.BuildConfig.VERSION_CODE}")
        appendLine("先看本文件判断哪里未开启／未应用／需重开，再看 operations 与具体阶段。已注册 ≠ 导出成功 ≠ 所有场景验收通过。")
        appendLine("此报告只描述收到的当前进程快照；无回复不等于应用未安装，辅助进程不能代表主进程页面。")
        if (diagnostics.containsKey("error")) appendLine("诊断查询：" + text(diagnostics.getString("error")))
        HookDiagnostics.hosts.sorted().forEach { host ->
            appendLine(); appendLine("## $host")
            val report = diagnostics.getBundle(host)
            if (report == null) {
                appendLine("没有收到回复：可能未运行、未安装、权限／注入／查询超时。不能给该宿主的功能下成功结论。")
                appendLine("建议：打开相应 TikTok，复现目标功能后保留后台，再返回 Toki 导出无需 root 的模块日志。")
                return@forEach
            }
            val process = report.getString("process").orEmpty()
            appendLine("进程=${text(process)} | PID=${if (report.containsKey("pid")) report.getInt("pid") else "未报告"} | 会话=${text(report.getString("session"), 100)}")
            if (process.contains(':')) appendLine("注意：这是辅助进程报告，不用它判定主界面功能可用。")
            appendLine("TikTok版本=${text(report.getString("hostVersion"))} | 宿主内模块版本=${text(report.getString("moduleVersion"))} | Android SDK=${if (report.containsKey("androidSdk")) report.getInt("androidSdk") else "未报告"}")
            val stamp = report.getLong("updated", 0)
            appendLine("快照时间=${if (stamp > 0) Instant.ofEpochMilli(stamp).toString() else "未报告"} | 年龄=${if (stamp > 0 && now >= stamp) "${now - stamp}ms" else "未知／时钟变化"}")
            if (stamp > 0 && now - stamp > 60_000) appendLine("注意：快照超过60秒；请重新查询，不能把旧报告当成当前运行状态。")
            if (stamp > now) appendLine("注意：快照时间晚于导出时间，可能时钟变化；不能据此判断快照新鲜度。")
            val version = report.getString("moduleVersion")
            if (version != null && version != io.github.meiyongai.toki.subtitle.BuildConfig.VERSION_NAME)
                appendLine("版本不一致：管理端与宿主内模块不是同一版本，需检查安装／注入。")
            appendLine("请求配置版本=${long(report, "configRevision")} | 宿主准备=${text(report.getString("adaptation"))}")
            val features = report.getBundle("features") ?: Bundle()
            val requested = ConfigSchema.featureSwitches.keys
            val missing = requested.count { features.getBundle(it) == null }
            appendLine("功能记录=${requested.size - missing}/${requested.size}；缺失=$missing，缺失不默认视为关闭。")
            val currentRevision = if (report.containsKey("configRevision")) report.getLong("configRevision") else null
            appendLine(); appendLine("### 四项一眼状态（本次会话，不是验收结论）")
            appendLine(report.getString("operationBrief")?.let { DiagnosticText.redact(it).take(8192) }
                ?: "离线视频导出／动态贴图：宿主未提供新版操作概览，证据不足；详细operations仍保留。")
            for (name in listOf("PlaybackSpeedHook", "SpeedOptions", "BackgroundAudioHook", "TemplateCleanupHook")) {
                val row = features.getBundle(name)
                appendLine("- ${titles[name]}：${featureState(row)}")
                appendLine("    " + ExecutionEvidence.describe(row?.getBundle("execution"), currentRevision, name))
            }
            appendLine("全设置无重开并未完成；这里只列已接入的执行证据，其余功能状态和缺口在下方。")
            appendLine(); appendLine("### 全部功能配置／执行证据")
            requested.forEach features@ { name ->
                val row = features.getBundle(name)
                appendLine("- ${titles[name] ?: name} [$name]：${featureState(row)}")
                if (row == null) return@features
                appendLine("  请求=${bool(row, if (row.containsKey("requestedEnabled")) "requestedEnabled" else "enabled")} | 已应用=${bool(row, "appliedEnabled")} | 重开=${bool(row, "restartRequired")} | 注册点=${row.getInt("registered")} | 应用配置版本=${long(row, "appliedRevision")}")
                if (report.containsKey("configRevision") && row.containsKey("appliedRevision") && row.getLong("appliedRevision") != report.getLong("configRevision"))
                    appendLine("  配置版本不一致：可能尚未同步、启动冻结或功能失败；结合重开／错误状态判断。")
                appendLine("  状态=${text(row.getString("state"))}")
                row.getString("detail")?.let { appendLine("  细节=${text(it, 2000)}") }
                row.getBundle("execution")?.let { evidence ->
                    appendLine("  执行：" + ExecutionEvidence.describe(evidence, currentRevision, name))
                    val at = evidence.getLong("at", 0)
                    appendLine("  最近观察=${if (at > 0) Instant.ofEpochMilli(at) else "未报告"}；记录采样次数=${long(row, "executionObservations")}；重复相同结果不会重复占满历史。")
                }
                row.getBundle("executionHistory")?.let { history ->
                    appendLine("  先前变化（最多5条，可能属于旧配置，不是当前成功证明）：")
                    for (index in 1..5) history.getBundle(index.toString())?.let { previous ->
                        val at = previous.getLong("at", 0)
                        appendLine("    ${if (at > 0) Instant.ofEpochMilli(at) else "时间未报告"} " + ExecutionEvidence.describe(previous, currentRevision, name))
                    }
                }
                row.getString("errorStatus")?.let { appendLine("  失败阶段=${text(it)}") }
                row.getString("error")?.let { appendLine("  错误类型／位置=${text(it, 1200)}") }
                val counts = row.getBundle("counts")
                counts?.keySet()?.sorted()?.take(12)?.forEach { counter ->
                    appendLine("  计数 ${text(counter, 180)}=${counts.getLong(counter)}")
                }
                if (counts != null && counts.size() > 12) appendLine("  计数已节选，完整值见 diagnostics.json。")
            }
            val internal = features.keySet().filter { it !in requested }.sorted()
            if (internal.isNotEmpty()) appendLine("内部适配／路径记录（完整细节见JSON）：")
            internal.take(96).forEach internals@ { name ->
                val row = features.getBundle(name) ?: return@internals
                appendLine("  ${text(name, 180)}：${text(row.getString("state"), 240)}；注册=${row.getInt("registered")}；错误=${text(row.getString("errorStatus"), 240)}")
            }
            appendLine(); appendLine("### 最近操作（不是注册计数）")
            appendLine(report.getString("operations")?.let { DiagnosticText.redact(it).take(48 * 1024) }
                ?: "宿主未提供新版操作摘要；没有操作记录不代表执行成功。")
        }
        appendLine(); appendLine("## 本版已知边界／下一步")
        appendLine("- 当前只优先：离线视频导出、动态贴图转换、全设置实时生效、截图模板／特效完整隐藏；另补日志可读性。")
        appendLine("- 离线导出：已有本地轨道校验／MP4封装／回读源码；Android9、加密、分散片段、未解析模型等仍有限制。")
        appendLine("- 动态贴图：GIF／WebP逐帧源码已接；有颜色／透明／资源与宿主解码器限制。")
        appendLine("- SIM／语言／时区／GPS已有运行时安装与后续平台读取刷新源码；不会重建宿主已缓存值／已发请求或切换现有页面语言，仍未设备验证。")
        appendLine("- 倍速按已观察实例发命令，未知实例不猜速；菜单等待后续读取，后台音频不清宿主缓存。执行历史只保留最多6种最近变化且进程退出即丢失，不是长期审计档案。")
        appendLine("- 全设置无重开、模板／特效全覆盖仍未完成，不能由一次绿色注册状态推断实现。")
        appendLine("- 编译／基础测试状态见随版本验证记录；本报告不把编译或静态检查当作真机验收，具体业务成功以本次操作终态及文件实测为准。")
        appendLine("- 数字说明：candidatePlans=候选方案数，candidateKeys=缓存候选键数，tracks=轨道数，samples=编码样本数，sourceBytes/outputBytes=字节数，frames=动画帧数，width/height=画布像素。")
        appendLine("- 用户已授权继续其他原始需求，并要求基础检查通过后再编译；完整范围与实际状态见仓库USER_WORK_ORDER.md和SOURCE_STATUS.md。")
        appendLine("隐私：模块诊断只记录技术元数据并做地址／凭据额外遮盖；若选择全量logcat，其内容可能包含其他应用信息，分享前自行检查。")
    }
}
