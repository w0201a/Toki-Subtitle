package io.github.meiyongai.toki.subtitle.hook

import java.util.WeakHashMap

/** 长文案按段更新，未完成段保留原文，失败段独立重试一次。 */
internal class ChunkedDescriptionTranslation(
    translate: (String, (String?) -> Unit) -> AutoCloseable,
    private val ready: (Any) -> Unit,
    private val report: (String) -> Unit = {},
) : AutoCloseable {
    private class Entry(val source: String, val chunks: List<String>) {
        val results = mutableMapOf<Int, String>()
        var translated: String? = null
    }
    private val entries = WeakHashMap<Any, Entry>()
    private val queue = PollTranslationQueue(translate, { key, index, value ->
        entries[key]?.let { entry ->
            if (entry.results[index] != value) {
                entry.results[index] = value
                // Publish successful segments immediately; retain source for missing
                // segments and preserve their order, rather than hiding all progress.
                entry.translated = entry.chunks.indices.joinToString("\n") { entry.results[it] ?: entry.chunks[it] }
                report("segments completed=${entry.results.size} total=${entry.chunks.size}")
                ready(key)
            }
        }
    }, report, retryFailures = true)

    fun request(key: Any, source: String) {
        if (entries[key]?.source != source) {
            queue.unbind(key)
            val chunks = split(source)
            entries[key] = Entry(source, chunks).also { entry ->
                chunks.forEachIndexed { index, text ->
                    if (!text.any(Char::isLetter)) entry.results[index] = text
                }
            }
        }
        val entry = entries.getValue(key)
        if (entry.results.size < entry.chunks.size) queue.bind(key, entry.chunks)
    }

    fun result(key: Any, source: String): String? = entries[key]?.takeIf { it.source == source }?.translated
    fun unbind(key: Any) { queue.unbind(key); entries.remove(key) }
    override fun close() { entries.clear(); queue.close() }

    companion object {
        internal fun split(text: String): List<String> {
            val chunks = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                var end = minOf(start + 700, text.length)
                if (end < text.length) {
                    if (Character.isHighSurrogate(text[end - 1])) end--
                    val boundary = (end - 1 downTo start + 350).firstOrNull {
                        text[it].isWhitespace() || text[it] in ".!?。！？"
                    }
                    if (boundary != null) end = boundary + 1
                }
                chunks += text.substring(start, end)
                start = end
            }
            return chunks
        }
    }
}
