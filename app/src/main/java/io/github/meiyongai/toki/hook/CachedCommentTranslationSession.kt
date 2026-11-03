package io.github.meiyongai.toki.subtitle.hook

/** Main-thread per-dialog coordinator; injected scheduling/transport keeps stale-result behavior testable without Android. */
internal class CachedCommentTranslationSession(
    private val post: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val valid: () -> Boolean,
    private val changed: (OfflineCommentData.Row, String, ManualCommentTranslation.State) -> Unit,
    private val complete: (OfflineCommentData.Row, String, String) -> Unit,
) : AutoCloseable {
    private var task: ManualCommentTranslation? = null
    private var request: ((String, (String?) -> Unit) -> AutoCloseable)? = null
    private var row: OfflineCommentData.Row? = null
    private var target = ""
    private var generation = 0L
    private var committed = false
    private var closed = false
    val busy: Boolean get() = task?.snapshot()?.busy == true

    fun start(value: OfflineCommentData.Row, language: String,
        transport: (String, (String?) -> Unit) -> AutoCloseable): Boolean {
        if (closed || !valid() || value.source.isBlank() || value.source.length > ManualCommentTranslation.MAX_SOURCE_LENGTH ||
            value.sourceTruncated || CommentTranslationKey.normalizedTarget(language) == null || busy) return false
        if (row?.id == value.id && row?.source == value.source && target == language && task?.snapshot()?.failed?.let { it > 0 } == true) {
            request = transport; task?.retryFailed(); return true
        }
        cancel()
        row = value; target = language; committed = false; request = transport
        val token = generation
        val session = ManualCommentTranslation(translate = { text, done ->
            if (closed || generation != token || !valid()) { done(null); AutoCloseable { } }
            else checkNotNull(request).invoke(text, done) // Completion is marshalled to the main scheduler by ManualCommentTranslation.
        }, post = post, remove = remove, changed = { state ->
            if (!closed && token == generation && valid()) {
                changed(value, language, state)
                if (!closed && token == generation && valid() && !state.busy && state.failed == 0 &&
                    state.translated != null && !committed) {
                    committed = true; complete(value, language, state.translated)
                }
            }
        })
        task = session
        session.start(value.source)
        return true
    }

    fun cancel() {
        generation++; task?.close(); task = null; request = null; row = null; target = ""; committed = false
    }
    override fun close() { if (!closed) { closed = true; cancel() } }
}
