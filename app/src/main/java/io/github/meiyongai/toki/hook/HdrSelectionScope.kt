package io.github.meiyongai.toki.subtitle.hook

/** One synchronous native selection, isolated across nested calls and player threads. */
internal class HdrSelectionScope {
    internal class Call(val disabled: Boolean) {
        var policyReads = 0
        var gateReads = 0
    }
    private val current = ThreadLocal<Call>()
    val call: Call? get() = current.get()
    fun <T> selecting(disabled: Boolean, action: (Call) -> T): T {
        val previous = current.get()
        val value = Call(disabled)
        current.set(value)
        return try { action(value) } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
    fun gate(native: Boolean): Boolean {
        val value = current.get() ?: return native
        value.gateReads++
        return native || value.disabled
    }
}
