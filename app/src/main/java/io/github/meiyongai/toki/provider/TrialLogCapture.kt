package io.github.meiyongai.toki.subtitle.provider

import android.content.Context
import android.os.Bundle
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** 由用户在模块内启动 root logcat，并通过系统文件选择器导出；不依赖电脑。 */
internal object TrialLogCapture {
    private var process: Process? = null
    private var directory: File? = null
    /** 先探测 root，再逐个尝试候选命令；全部失败时写入可核查的探针与错误输出。 */
    @Synchronized fun start(context: Context): String {
        if (process?.isAlive == true) return "日志正在记录"
        val dir = File(context.filesDir, "trial-logs/${System.currentTimeMillis()}").apply { mkdirs() }
        directory = dir
        val probe = probeRoot(File(dir, "root-probe.txt"))
        val attempts = listOf(
            arrayOf("su", "-c", "logcat -b all -v threadtime"),
            arrayOf("su", "-c", "/system/bin/logcat -b all -v threadtime"),
            arrayOf("su", "-c", "sh -c 'logcat -b all -v threadtime'"),
            arrayOf("su", "-c", "logcat -v threadtime"),
        )
        val failures = StringBuilder()
        for ((index, command) in attempts.withIndex()) {
            val stderrFile = File(dir, "capture-$index-stderr.txt")
            val child = try { ProcessBuilder(*command)
                .redirectOutput(File(dir, "logcat-$index.txt"))
                .redirectError(stderrFile).start()
            } catch (error: Exception) {
                failures.append("attempt=$index start failed: ").append(error).append('\n')
                continue
            }
            if (!child.waitFor(900, TimeUnit.MILLISECONDS)) {
                process = child
                return "正在记录全部可读取的 logcat；请去 TikTok 复现，再返回导出"
            }
            val stderr = stderrFile.takeIf { it.isFile }?.readText()?.trim()?.take(200)
            failures.append(command.joinToString(" ")).append(" -> exit=").append(child.exitValue())
                .append(" stderr=").append(stderr).append('\n')
        }
        File(dir, "capture-failure.txt").writeText("probe: $probe\n$failures")
        throw java.io.IOException("root 日志启动失败。$probe")
    }

    /** 探测 root 授权与命令形态，保留探针输出，便于区分授权失败与写法问题。 */
    private fun probeRoot(out: File): String = try {
        // 先重定向到文件再等待，避免 readText 在授权提示未结束时无限阻塞。
        val raw = File(out.parentFile, "root-probe-output.txt")
        val child = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).redirectOutput(raw).start()
        val result = if (child.waitFor(8, TimeUnit.SECONDS))
            "exit=${child.exitValue()} out=${raw.readText().trim().take(200)}"
        else {
            child.destroy()
            "timeout"
        }
        out.writeText(result)
        result
    } catch (error: Exception) {
        out.writeText(error.toString())
        error.toString()
    }
    @Synchronized fun stop() {
        process?.destroy()
        process = null
    }
    /**
     * 导出诊断包；模块日志与 Hook 状态始终写入，logcat 快照按调用方选择。
     * @param includeLogcat 是否追加一次只读 logcat 快照。失败只记录状态，不影响导出。
     */
    fun export(context: Context, diagnostics: Bundle, output: OutputStream, includeLogcat: Boolean = false) {
        stop()
        val root = File(context.filesDir, "trial-logs").apply { mkdirs() }
        val dir = File(root, "snapshot-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        File(dir, "00-先看这里-诊断速览.txt").writeText(DiagnosticOverview.render(diagnostics))
        File(dir, "01-阅读说明.txt").writeText(
            "阅读顺序：00诊断速览 → 各宿主 operations 操作摘要 → 宿主 hook-log → diagnostics.json。\n" +
                "已注册只表示拦截安装；请求开不等于已应用，调用计数不等于业务成功。无宿主回复／无操作记录属于证据不足，不默认正常。\n" +
                "日志使用UTC日期时间、进程相对毫秒、序号；op编号仅在同一进程会话内关联，不能跨进程当同一任务。\n" +
                "仅本次快照与用户明确选择的当前logcat采集会话进入本包；磁盘历史日志不会被递归全部打包。\n" +
                "进程日志与操作摘要有容量上限，计数注明覆盖情况；进程退出会丢失内存日志，复现后保留TikTok后台再导出。\n" +
                "每个文件最多8MiB、包内原始数据合计最多32MiB，截断情况见archive-status.txt。完整logcat可能包含其他应用信息，分享前检查。\n")
        File(dir, "diagnostics.json").writeText(json(diagnostics).toString(2))
        File(dir, "module-log.txt").writeText(TrialLog.snapshot())
        File(dir, "manager-operations.txt").writeText(OperationDiagnostics.snapshot())
        val missing = mutableListOf<String>()
        val received = mutableListOf<String>()
        for (host in HookDiagnostics.hosts) {
            val hostReport = diagnostics.getBundle(host)
            val hostLog = hostReport?.getString("trialLog")
            if (hostLog == null) missing.add(host)
            else {
                received.add(host)
                File(dir, "$host-hook-log.txt").writeText(DiagnosticText.redact(hostLog).take(128 * 1024))
                File(dir, "$host-operations.txt").writeText(hostReport?.getString("operations")?.let { DiagnosticText.redact(it).take(48 * 1024) }
                    ?: "未收到新版操作摘要；不能据此判断业务成功。")
            }
        }
        File(dir, "host-log-status.txt").writeText(
            "已收到宿主进程日志：${received.joinToString().ifEmpty { "无" }}\n" +
                "未回复：${missing.joinToString().ifEmpty { "无" }}（未安装的 TikTok 版本无回复属于正常情况）\n" +
                "请复现后保留 TikTok 在后台运行，再返回 Toki 导出。管理端日志不能代替宿主日志。")
        if (includeLogcat) dumpLogcat(dir)
        // Selecting a new export must not silently attach every historical root-logcat session.
        val capture = if (includeLogcat) synchronized(this) { directory } else null
        val selected = listOf(dir to "snapshot") + listOfNotNull(capture?.takeIf { it.isDirectory }?.let { it to "selected-capture" })
        val status = StringBuilder("仅包含本次快照；旧目录仍保留在本地但不自动加入。\n")
        var remaining = 32L * 1024 * 1024
        ZipOutputStream(output).use { zip ->
            for ((source, prefix) in selected) {
                source.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(source).invariantSeparatorsPath }.forEach { file ->
                    // Reject symlinks escaping the explicitly selected source directory.
                    if (!file.canonicalFile.toPath().startsWith(source.canonicalFile.toPath())) return@forEach
                    val name = "$prefix/" + file.relativeTo(source).invariantSeparatorsPath
                    val limit = minOf(8L * 1024 * 1024, remaining)
                    if (limit <= 0) { status.append("跳过（总容量上限）：$name\n"); return@forEach }
                    zip.putNextEntry(ZipEntry(name))
                    var written = 0L
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (written < limit) {
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - written).toInt())
                            if (count < 0) break
                            zip.write(buffer, 0, count); written += count
                        }
                    }
                    remaining -= written; zip.closeEntry()
                    status.append(if (file.length() > written) "截断" else "完整").append(": $name bytes=$written\n")
                }
            }
            zip.putNextEntry(ZipEntry("archive-status.txt")); zip.write(status.toString().toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
    }

    /** 只读导出一次 logcat 快照；命令失败只写状态文件，不中断诊断包导出。 */
    private fun dumpLogcat(dir: File) {
        try {
            val dump = ProcessBuilder("su", "-c", "logcat -b all -d -v threadtime")
                .redirectOutput(File(dir, "final-logcat.txt"))
                .redirectError(File(dir, "final-stderr.txt")).start()
            if (!dump.waitFor(20, TimeUnit.SECONDS)) {
                dump.destroy()
                File(dir, "dump-status.txt").writeText("timeout")
            } else File(dir, "dump-status.txt").writeText("exit=${dump.exitValue()}")
        } catch (error: Exception) {
            File(dir, "dump-status.txt").writeText(error.toString())
        }
    }

    @Suppress("DEPRECATION")
    private fun json(bundle: Bundle): JSONObject = JSONObject().apply {
        bundle.keySet().sorted().forEach { key ->
            val value = bundle.get(key)
            put(DiagnosticText.line(key, 240), when (value) {
                is Bundle -> json(value)
                is String -> DiagnosticText.redact(value).take(128 * 1024)
                else -> value ?: JSONObject.NULL
            })
        }
    }
}
