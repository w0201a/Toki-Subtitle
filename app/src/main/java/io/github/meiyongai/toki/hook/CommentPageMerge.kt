package io.github.meiyongai.toki.subtitle.hook

/** Explicit network pages update rows by ID without silently dropping previously cached rows. */
internal object CommentPageMerge {
    private fun ordered(rows: Collection<OfflineCommentData.Row>): List<OfflineCommentData.Row> {
        val ids = rows.mapTo(mutableSetOf()) { it.id }
        val children = rows.filter { it.parentId.isNotBlank() && it.parentId in ids }.groupBy { it.parentId }
        val result = mutableListOf<OfflineCommentData.Row>()
        val visited = mutableSetOf<String>()
        fun add(row: OfflineCommentData.Row, depth: Int) {
            require(depth <= 3 && visited.add(row.id)) { "Invalid cached reply relationships" }
            result += row.copy(depth = depth)
            children[row.id].orEmpty().forEach { add(it, depth + 1) }
        }
        rows.filter { it.parentId.isBlank() || it.parentId !in ids }.forEach { add(it, 0) }
        require(result.size == rows.size) { "Cyclic cached replies" }
        return result
    }
    fun cached(primary: OfflineCommentData.Snapshot, secondary: OfflineCommentData.Snapshot?): OfflineCommentData.Snapshot {
        if (secondary == null) return primary
        require(primary.account == secondary.account && primary.video == secondary.video)
        val values = linkedMapOf<String, OfflineCommentData.Row>()
        primary.rows.forEach { values[it.id] = it }
        secondary.rows.forEach { values.putIfAbsent(it.id, it) }
        return primary.copy(rows = ordered(values.values.take(OfflineCommentData.MAX_ROWS)),
            truncated = primary.truncated || secondary.truncated || values.size > OfflineCommentData.MAX_ROWS,
            retainedOlder = primary.retainedOlder || secondary.retainedOlder || values.size > primary.rows.size)
    }
    fun merge(old: OfflineCommentData.Snapshot?, page: OfflineCommentData.Snapshot, requestedCursor: Long): OfflineCommentData.Snapshot {
        require(requestedCursor >= 0)
        require(old == null || old.account == page.account && old.video == page.video)
        if (requestedCursor > 0) require(old != null && old.nextCursor == requestedCursor)
        if (page.hasMore) require(page.nextCursor != null && page.nextCursor > requestedCursor) { "Non-progressing comment cursor" }
        val values = linkedMapOf<String, OfflineCommentData.Row>()
        val previous = old?.rows.orEmpty()
        if (requestedCursor == 0L) {
            page.rows.forEach { values[it.id] = it }
            previous.forEach { values.putIfAbsent(it.id, it) }
        } else {
            previous.forEach { values[it.id] = it }
            page.rows.forEach { values[it.id] = it }
        }
        val retained = requestedCursor == 0L && previous.any { before -> page.rows.none { it.id == before.id } }
        require(values.size <= OfflineCommentData.MAX_ROWS) { "Comment snapshot capacity reached" }
        return page.copy(rows = ordered(values.values),
            truncated = page.truncated || old?.truncated == true,
            retainedOlder = old?.retainedOlder == true || retained)
    }
}

/** Disposable ownership covers callbacks delivered synchronously before native subscribe returns. */
internal class CommentPageOperation : AutoCloseable {
    private val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
    private val resource = java.util.concurrent.atomic.AtomicReference<AutoCloseable?>(null)
    val active: Boolean get() = !cancelled.get()
    fun attach(value: AutoCloseable) {
        if (!resource.compareAndSet(null, value)) value.close()
        if (cancelled.get()) resource.getAndSet(null)?.close()
    }
    override fun close() { cancelled.set(true); resource.getAndSet(null)?.close() }
}
