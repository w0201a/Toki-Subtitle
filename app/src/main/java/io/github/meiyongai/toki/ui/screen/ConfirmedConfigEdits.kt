package io.github.meiyongai.toki.subtitle.ui.screen

/** Commit once, then reload actual values; a failed write must not leave optimistic controls or a success message. */
internal class ConfirmedConfigEdits(private val write: (Map<String, Any>) -> Unit,
    private val reload: () -> Unit, private val report: (Exception?) -> Unit) {
    fun save(values: Map<String, Any>): Boolean {
        var failure: Exception? = null
        try { write(values) } catch (error: Exception) { failure = error }
        try { reload() } catch (error: Exception) { if (failure == null) failure = error }
        report(failure)
        return failure == null
    }
}
