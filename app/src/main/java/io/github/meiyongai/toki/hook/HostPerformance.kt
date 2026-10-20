package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Explicit, bounded in-process stack capture for devices that block ADB debuggerd. */
internal object HostPerformance {
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastCapture = 0L
    fun capture(deliver: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        if (!running.compareAndSet(false, true)) return
        if (SystemClock.uptimeMillis() - lastCapture < 10_000) { running.set(false); deliver("请间隔 10 秒后再采样"); return }
        lastCapture = SystemClock.uptimeMillis()
        Thread({
            val report = try {
                val runtime = Runtime.getRuntime()
                val traces = Thread.getAllStackTraces()
                val selected = traces.entries.filter {
                    it.key.name.startsWith("AsyncTask") || it.key.name.startsWith("Toki") || it.key.name == "main"
                }.sortedWith(compareBy<Map.Entry<Thread, Array<StackTraceElement>>> { !it.key.name.startsWith("AsyncTask") }
                    .thenBy { it.key.name }).take(10)
                buildString {
                    append("Java heap MB: ").append((runtime.totalMemory()-runtime.freeMemory())/1048576)
                    append("; threads: ").append(traces.size).append('\n')
                    selected.forEach { (thread, stack) ->
                        append(thread.name).append(" [").append(thread.state).append("]\n")
                        stack.take(12).forEach { append("  ").append(it.className).append('#').append(it.methodName).append('\n') }
                    }
                }.also { text ->
                    text.lineSequence().chunked(5).forEach { lines -> HookRuntime.event("HostPerformance", lines.joinToString(" | ").take(480)) }
                }
            } catch (error: Exception) { "采样失败：${error.javaClass.simpleName}" }
            finally { running.set(false) }
            main.post { deliver(report) }
        }, "TokiPerformanceCapture").apply { priority = Thread.MIN_PRIORITY; start() }
    }
}
