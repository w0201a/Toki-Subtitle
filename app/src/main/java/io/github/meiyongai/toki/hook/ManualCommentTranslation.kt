package io.github.meiyongai.toki.subtitle.hook

/** Explicit user-requested translation. Per-dialog results only: no new disk/offline storage or video-prefetch lane. */
internal class ManualCommentTranslation(
    private val translate: (String, (String?) -> Unit) -> AutoCloseable,
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val changed: (State) -> Unit,
    private val report: (String) -> Unit = {},
) : AutoCloseable {
    enum class Mode { ORIGINAL, TRANSLATED, BILINGUAL }
    data class State(val original: String, val translated: String?, val completed: Int, val total: Int,
        val failed: Int, val busy: Boolean) {
        fun text(mode: Mode): String = when {
            mode == Mode.ORIGINAL || translated == null || translated == original -> original
            mode == Mode.BILINGUAL -> original + "\n\n" + translated
            else -> translated
        }
    }
    private class Segment(val source: String) {
        var result: String? = if (source.isBlank()) source else null
        var done = result != null
        var failed = false
    }
    private var source = ""
    private var segments = emptyList<Segment>()
    private var index: Int? = null
    @Volatile private var generation = 0L
    private var subscription: AutoCloseable? = null
    private var timeout: Runnable? = null
    @Volatile private var closed = false
    private val pump = Runnable { next() }
    private val completions = mutableSetOf<Runnable>()

    fun start(text: String) {
        check(!closed)
        require(text.length in 1..MAX_SOURCE_LENGTH)
        cancelPending()
        source = text
        segments = ChunkedDescriptionTranslation.split(text).map(::Segment)
        val token = generation
        publish()
        if (!closed && generation == token) post(pump, 0)
    }

    fun retryFailed() {
        if (closed || index != null || segments.any { !it.done }) return
        segments.filter { it.failed }.forEach { it.done = false; it.failed = false }
        val token = generation
        publish()
        if (!closed && generation == token) { remove(pump); post(pump, 0) }
    }

    fun snapshot(): State {
        val translated = if (segments.any { it.result != null && it.result != it.source })
            segments.joinToString("\n") { it.result ?: it.source } else null
        return State(source, translated, segments.count { it.result != null }, segments.size,
            segments.count { it.failed }, index != null || segments.any { !it.done })
    }

    private fun publish() { if (!closed) changed(snapshot()) }

    private fun next() {
        if (closed || index != null) return
        val current = segments.indexOfFirst { !it.done }
        if (current < 0) { publish(); return }
        index = current
        val token = ++generation
        timeout = Runnable { finish(token, current, null) }.also { post(it, 6000) }
        publish()
        // Observers may dismiss or restart the dialog while rendering this state.
        if (closed || generation != token || index != current) return
        try {
            val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
            val pending = translate(segments[current].source, complete@{ value ->
                if (!delivered.compareAndSet(false, true)) return@complete
                lateinit var deliver: Runnable
                deliver = Runnable {
                    synchronized(completions) { completions.remove(deliver) }
                    finish(token, current, value)
                }
                synchronized(completions) {
                    if (!closed && generation == token) {
                        completions.add(deliver)
                        post(deliver, 0)
                    }
                }
            })
            if (generation == token && index == current && !closed) subscription = pending else pending.close()
        } catch (error: Exception) {
            report("request unavailable type=" + error.javaClass.simpleName)
            finish(token, current, null)
        }
    }

    private fun finish(token: Long, current: Int, value: String?) {
        if (closed || generation != token || index != current) return
        timeout?.let(remove); timeout = null
        index = null
        try { subscription?.close() } catch (_: Exception) { }
        subscription = null
        val result = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8000 }
        segments[current].apply { this.result = result; done = true; failed = result == null }
        report(if (result == null) "segment unavailable; original retained" else "segment translated")
        publish()
        if (!closed && generation == token) {
            remove(pump)
            post(pump, if (result == null) 500 else 100)
        }
    }

    private fun cancelPending() {
        generation++
        remove(pump)
        timeout?.let(remove); timeout = null
        synchronized(completions) { completions.toList().forEach(remove); completions.clear() }
        index = null
        try { subscription?.close() } catch (_: Exception) { }
        subscription = null
    }

    override fun close() {
        if (closed) return
        closed = true
        cancelPending()
        segments = emptyList()
        source = ""
    }

    companion object { const val MAX_SOURCE_LENGTH = 16000 }
}

/** Shared only by module menus when explicitly returning to TikTok's native actions; copy tracking is not bypassed. */
internal object CommentNativeMenuScope {
    private val depth = ThreadLocal<Int>()
    val active: Boolean get() = (depth.get() ?: 0) > 0
    fun <T> native(call: () -> T): T {
        val previous = depth.get() ?: 0
        depth.set(previous + 1)
        try { return call() } finally { if (previous == 0) depth.remove() else depth.set(previous) }
    }
}
