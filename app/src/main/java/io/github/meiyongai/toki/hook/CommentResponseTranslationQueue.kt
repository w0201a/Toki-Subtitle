package io.github.meiyongai.toki.subtitle.hook

/** Translate all admitted response rows, independent of which RecyclerView cells happen to be visible. */
internal class CommentResponseTranslationQueue<T>(
    private val translate: (String, (String?) -> Unit) -> AutoCloseable,
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val apply: (T, String) -> Unit,
    private val capacity: Int = 2500,
) : AutoCloseable {
    private data class Entry<T>(val key: String, val value: T, val source: String, val current: () -> Boolean)
    private val pending = LinkedHashMap<String, Entry<T>>()
    private val completed = LinkedHashMap<String, String>()
    private var active: Entry<T>? = null
    private var task: ManualCommentTranslation? = null
    private var closed = false
    private var revision = 0L
    private val pump = Runnable { next() }
    val queued: Int get() = pending.size + if (active == null) 0 else 1
    fun offer(key: String, value: T, source: String, current: () -> Boolean): Boolean {
        if (closed || !current() || source.isBlank() || source.length > ManualCommentTranslation.MAX_SOURCE_LENGTH) return false
        completed[key]?.let { if (current()) apply(value, it); return true }
        if (active?.key == key) return true
        if (key !in pending && queued >= capacity) return false
        pending[key] = Entry(key, value, source, current)
        remove(pump); post(pump, 0)
        return true
    }
    private fun next() {
        if (closed || active != null) return
        while (pending.isNotEmpty()) {
            val value = pending.values.first(); pending.remove(value.key)
            if (!value.current()) continue
            task?.close(); task = null
            active = value
            val token = revision
            val session = ManualCommentTranslation(translate = { text, done ->
                if (!closed && token == revision && value.current()) translate(text, done)
                else { done(null); AutoCloseable { } }
            }, post = post, remove = remove, changed = { state ->
                if (!closed && token == revision && active === value && !state.busy) {
                    if (state.failed == 0 && state.completed == state.total && value.current()) {
                        val result = state.translated ?: state.original
                        completed[value.key] = result
                        while (completed.size > capacity) completed.remove(completed.keys.first())
                        apply(value.value, result)
                    }
                    active = null
                    remove(pump); post(pump, 150)
                }
            })
            task = session
            session.start(value.source)
            return
        }
    }
    fun cancel() {
        revision++; task?.close(); task = null; active = null; pending.clear(); remove(pump)
    }
    override fun close() { if (!closed) { cancel(); closed = true; completed.clear() } }
}
