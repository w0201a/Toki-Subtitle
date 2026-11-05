package io.github.meiyongai.toki.subtitle.hook

/** Completion callbacks can arrive before code verification. Retain them and drain five identities at a time. */
internal class OfflineCommentIngress<T> {
    private val waiting = LinkedHashMap<String, T>()
    @Synchronized fun offer(id: String, model: T) { waiting[id] = model }
    @Synchronized fun take(ready: Boolean): List<T> {
        if (!ready) return emptyList()
        val keys = waiting.keys.take(OfflineCommentWorkPlan.BATCH_SIZE)
        return keys.map { waiting.remove(it)!! }
    }
    @Synchronized fun size(): Int = waiting.size
    @Synchronized fun clear() = waiting.clear()
}
