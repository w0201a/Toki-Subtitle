package io.github.meiyongai.toki.subtitle.hook

/** Immutable copies only: the offline viewer never changes native Comment models. */
internal object OfflineCommentData {
    enum class Mode { ORIGINAL, TRANSLATED, BILINGUAL }
    enum class MediaKind { IMAGE, STICKER }
    data class Media(val id: String, val kind: MediaKind, val urls: List<String>, val animated: Boolean = false)
    data class Row(val id: String, val author: String, val handle: String, val authorId: String,
        val source: String, val translation: String?, val language: String, val depth: Int,
        val replyCount: Long, val sourceTruncated: Boolean = false, val parentId: String = "",
        val media: List<Media> = emptyList(), val mediaKnown: Boolean = false) {
        fun text(mode: Mode): String = when {
            mode == Mode.ORIGINAL || translation.isNullOrBlank() || translation == source -> source
            mode == Mode.BILINGUAL -> source + "\n\n" + translation
            else -> translation
        }
    }
    data class ReplyCursor(val next: Long?, val hasMore: Boolean)
    data class Snapshot(val account: String, val video: String, val capturedAt: Long,
        val rows: List<Row>, val total: Long, val hasMore: Boolean, val truncated: Boolean,
        val nextCursor: Long? = null, val retainedOlder: Boolean = false,
        val replyCursors: Map<String, ReplyCursor> = emptyMap())
    enum class Failure { MISSING, UNSUPPORTED, CORRUPT, ACCOUNT_CHANGED, CANCELLED }
    class Unavailable(val reason: Failure) : Exception(reason.name)
    const val MAX_ROWS = 500
    const val MAX_TEXT = 16000

    fun bounded(text: String?, limit: Int): String {
        val value = text.orEmpty()
        if (value.length <= limit) return value
        var end = limit
        if (end > 0 && Character.isHighSurrogate(value[end - 1])) end--
        return value.substring(0, end)
    }

    /** Both displayed original and translated fields are native cache facts, never a remote request. */
    fun original(original: String?, text: String?): String = original?.takeIf { it.isNotBlank() } ?: text.orEmpty()
    fun translated(source: String, field: String?, visible: String?, isTranslated: Boolean): String? =
        (field?.takeIf { it.isNotBlank() } ?: visible?.takeIf { isTranslated && it != source && it.isNotBlank() })
            ?.takeIf { it != source && it.length <= MAX_TEXT }
}

/** Local, incremental display pages. End of this list does not imply all server comments were cached. */
internal class OfflineCommentPages(private val rows: List<OfflineCommentData.Row>, private val pageSize: Int = 40) {
    var count = 0
        private set
    init { require(pageSize in 1..100) }
    val hasNext: Boolean get() = count < rows.size
    fun append(): Int { count = minOf(rows.size, count + pageSize); return count }
    operator fun get(index: Int): OfflineCommentData.Row { require(index in 0 until count); return rows[index] }
}

internal object OfflineCommentJsonBounds {
    fun check(text: String) {
        require(text.length in 2..2 * 1024 * 1024)
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in text) {
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 64) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(!quoted && !escaped && depth == 0)
    }
}
