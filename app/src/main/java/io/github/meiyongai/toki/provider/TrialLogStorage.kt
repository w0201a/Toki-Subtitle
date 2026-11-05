package io.github.meiyongai.toki.subtitle.provider

import android.content.Context
import java.io.File
import java.nio.file.Files

/** No eviction. Full storage refuses new captures; only an explicitly confirmed file plan can clear old logs. */
internal object TrialLogStorage {
    const val MAX_STREAM_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    const val MAX_CAPTURE_MILLIS = 10L * 60 * 1000
    private val reserved = mutableMapOf<String,Long>()
    data class Usage(val bytes: Long, val files: Int, val captures: Int)
    data class ClearPlan(val root: File, val files: List<File>, val bytes: Long)
    private fun root(context: Context): File {
        val parent = context.filesDir.canonicalFile
        val named = File(parent,"trial-logs")
        require(!Files.isSymbolicLink(named.toPath())) { "Diagnostic directory must not be a symbolic link" }
        return named.canonicalFile.also { require(it.parentFile == parent && it.name == "trial-logs") }
    }
    private fun files(root: File): List<File> {
        val result = mutableListOf<File>(); val pending = ArrayDeque<File>(); pending.add(root)
        var visited = 0
        while (pending.isNotEmpty()) {
            val file = pending.removeFirst()
            check(++visited <= 100_000) { "Too many diagnostic entries; storage not scanned completely" }
            if (Files.isSymbolicLink(file.toPath()) || !file.canonicalFile.toPath().startsWith(root.toPath())) continue
            if (file.isDirectory) (file.listFiles() ?: error("Diagnostic directory cannot be read")).forEach(pending::addLast)
            else if (file.isFile) result += file
        }
        return result
    }
    @Synchronized fun usage(context: Context): Usage {
        val root = root(context); val files = files(root)
        return Usage(files.sumOf { it.length() }, files.size, files.map { it.parentFile }.toSet().size)
    }
    internal fun canReserve(used: Long, pending: Long, bytes: Long): Boolean =
        used >= 0 && pending >= 0 && bytes in 1..MAX_TOTAL_BYTES &&
            used <= MAX_TOTAL_BYTES - bytes && pending <= MAX_TOTAL_BYTES - bytes - used
    @Synchronized fun reserve(context: Context, bytes: Long): AutoCloseable? {
        require(bytes in 1..MAX_TOTAL_BYTES)
        val key = root(context).absolutePath; val used = usage(context).bytes; val pending = reserved[key] ?: 0L
        if (!canReserve(used,pending,bytes)) return null
        reserved[key] = pending + bytes
        val closed = java.util.concurrent.atomic.AtomicBoolean(false)
        return AutoCloseable { synchronized(this) {
            if (closed.compareAndSet(false,true)) reserved[key] = ((reserved[key] ?: 0L) - bytes).coerceAtLeast(0L)
        } }
    }
    @Synchronized fun plan(context: Context): ClearPlan {
        val root = root(context); val files = files(root)
        return ClearPlan(root, files.toList(), files.sumOf { it.length() })
    }
    /** Called only after a dialog displays this exact plan and the user explicitly confirms it. */
    @Synchronized fun clearConfirmed(context: Context, plan: ClearPlan): Int {
        val root = root(context); require(root == plan.root)
        var removed = 0
        for (file in plan.files) {
            require(!Files.isSymbolicLink(file.toPath()) && file.canonicalFile.toPath().startsWith(root.toPath()) && file.canonicalFile != root)
            if (file.isFile) { check(file.delete() || !file.exists()) { "Could not clear a diagnostic file" }; removed++ }
        }
        return removed
    }
}
