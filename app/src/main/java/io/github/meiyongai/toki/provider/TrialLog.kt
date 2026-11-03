package io.github.meiyongai.toki.subtitle.provider

import java.time.Instant
import java.util.ArrayDeque

/** Bounded readable process log: UTC wall clock + monotonic relative time, sequence and loss counters. */
internal object TrialLog {
    private val lines = ArrayDeque<String>()
    private val started = System.nanoTime()
    private var dropped = 0L
    private var sequence = 0L
    private var characters = 0
    private const val MAX_LINES = 600
    private const val MAX_CHARACTERS = 96 * 1024
    @Synchronized fun add(tag: String, message: String) {
        val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
        val line = "${Instant.ofEpochMilli(System.currentTimeMillis())} +${elapsed}ms #${++sequence} " +
            DiagnosticText.line(tag, 80) + " " + DiagnosticText.line(message)
        while (lines.isNotEmpty() && (lines.size >= MAX_LINES || characters + line.length > MAX_CHARACTERS)) {
            characters -= lines.removeFirst().length; dropped++
        }
        lines.addLast(line); characters += line.length
    }
    @Synchronized fun snapshot(): String =
        "版本=${io.github.meiyongai.toki.subtitle.BuildConfig.VERSION_NAME} | 本进程日志 | UTC时间 + 进程相对毫秒 | 已覆盖旧行=$dropped | 当前行=${lines.size}\n" +
            "说明：注册／配置记录不代表业务已成功；同一 op 编号串联一次操作。最近行可能因容量被覆盖，操作结果请另看操作摘要。\n" + lines.joinToString("\n")
}
