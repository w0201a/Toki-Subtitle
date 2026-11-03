package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.SharePanelPolicy
import java.util.WeakHashMap

/** Main-thread share adapter state, retained only until its panel view is destroyed. */
internal class ShareListBindings(
    private val keyOf: (Any?) -> String?,
    private val render: (Any, List<Any?>) -> Unit,
    private val report: (String) -> Unit = {},
    private val capacity: Int = 64,
) : AutoCloseable {
    private val originals = WeakHashMap<Any, List<Any?>>()
    private var hidden = emptySet<String>()
    private var applying = false
    private var closed = false
    init { require(capacity > 0) }

    @Synchronized fun incoming(owner: Any, source: List<Any?>): List<Any?> {
        if (closed || applying) return source
        if (owner !in originals && originals.size >= capacity) {
            report("adapter capacity reached; new adapter retained natively")
            return source
        }
        originals[owner] = source.toList()
        return SharePanelPolicy.filter(source, hidden, keyOf)
    }

    @Synchronized fun forget(owner: Any) { originals.remove(owner) }

    @Synchronized fun configure(keys: Set<String>) {
        if (closed || hidden == keys) return
        hidden = keys.toSet()
        originals.entries.toList().forEach { (owner, source) ->
            apply(owner, SharePanelPolicy.filter(source, hidden, keyOf))
        }
    }

    private fun apply(owner: Any, values: List<Any?>) {
        val wasApplying = applying
        applying = true
        try { render(owner, values) } catch (error: Exception) {
            report("adapter refresh unavailable type=" + error.javaClass.simpleName)
        } finally { applying = wasApplying }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        originals.entries.toList().forEach { (owner, source) -> apply(owner, source) }
        originals.clear()
    }
}
