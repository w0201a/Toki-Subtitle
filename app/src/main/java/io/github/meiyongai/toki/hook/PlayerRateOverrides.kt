package io.github.meiyongai.toki.subtitle.hook

import java.lang.ref.WeakReference

/** Weak-identity, bounded native observations. Unknown playback rates are never invented as 1x. */
internal class PlayerRateOverrides(private val report: (Exception) -> Unit = {}, private val capacity: Int = 64) {
    private class Entry(target: Any, var revision: Long) {
        val target = WeakReference(target)
        var native: Float? = null
        var output: Float? = null
        var owned = false
        var observed = false
        var pending = 0
        var write: ((Any, Float) -> Unit)? = null
    }
    data class Result(val written: Int, val failed: Int, val raced: Int, val known: Int)
    private val entries = mutableListOf<Entry>()
    private var revision = 0L
    init { require(capacity > 0) }
    private fun valid(speed: Float) = speed.isFinite() && speed in 0.1f..3f
    private fun notify(error: Exception) { try { report(error) } catch (_: Exception) { } }

    /** Reserve capacity BEFORE modifying a native request; commit only after its setter succeeds. */
    fun <T> request(target: Any, native: Float, replacement: Float?,
        proceed: (Float) -> T, write: (Any, Float) -> Unit): T {
        require(replacement == null || valid(replacement))
        if (!valid(native)) {
            val result = proceed(native)
            forget(target)
            return result
        }
        val ticket = synchronized(this) {
            entries.removeAll { it.target.get() == null }
            val entry = entries.firstOrNull { it.target.get() === target } ?:
                if (entries.size < capacity) Entry(target, ++revision).also { entries += it } else null
            entry?.let {
                it.pending++
                it.revision = ++revision
                Triple(it, it.revision, if (it.pending == 1) replacement ?: native else native)
            }
        } ?: return proceed(native)
        val (entry, token, actual) = ticket
        var completed = false
        try {
            val result = proceed(actual)
            completed = true
            val raced = synchronized(this) {
                if (entry !in entries || entry.revision != token) {
                    // Concurrent completion order cannot prove the physical output. Retain only genuine native evidence.
                    if (entry in entries && entry.observed) { entry.output = Float.NaN; entry.owned = true }
                    true
                } else {
                    entry.native = native; entry.output = actual; entry.owned = native != actual
                    entry.write = write; entry.observed = true
                    false
                }
            }
            if (raced) notify(IllegalStateException("Concurrent native playback request; actual output unconfirmed"))
            return result
        } finally {
            synchronized(this) {
                entry.pending--
                if (!completed && entry in entries) {
                    entry.revision = ++revision
                    if (entry.observed) { entry.output = null; entry.owned = true }
                }
                if (!entry.observed && entry.pending == 0) entries.remove(entry)
            }
        }
    }

    @Synchronized fun forget(target: Any) { entries.removeAll { it.target.get() == null || it.target.get() === target } }
    fun apply(speed: Float?, only: Any? = null): Result {
        require(speed == null || valid(speed))
        val work = synchronized(this) {
            entries.removeAll { it.target.get() == null }
            entries.filter { it.observed && it.pending == 0 && (only == null || it.target.get() === only) && (speed != null || it.owned) }
                .map { Triple(it, it.revision, speed ?: checkNotNull(it.native)) }
        }
        var written = 0; var failed = 0; var raced = 0
        for ((entry, token, desired) in work) {
            val target = entry.target.get() ?: continue
            val writer = synchronized(this) {
                if (entry !in entries || entry.pending != 0 || entry.revision != token || entry.output == desired) null else entry.write
            } ?: continue
            try {
                writer(target, desired)
                synchronized(this) {
                    if (entry !in entries || entry.revision != token || entry.pending != 0) {
                        raced++
                        if (entry in entries && entry.observed) { entry.output = Float.NaN; entry.owned = true }
                    } else { entry.output = desired; entry.owned = desired != entry.native; entry.revision = ++revision; written++ }
                }
            } catch (error: Exception) {
                synchronized(this) {
                    if (entry in entries && entry.revision == token) {
                        entry.output = null; entry.owned = true; entry.revision = ++revision
                    }
                }
                failed++; notify(error)
            }
        }
        return Result(written, failed, raced, synchronized(this) { entries.count { it.observed } })
    }
    @Synchronized fun clear() { entries.clear() }
}
