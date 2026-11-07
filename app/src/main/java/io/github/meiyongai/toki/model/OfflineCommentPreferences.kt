package io.github.meiyongai.toki.subtitle.model

/** Independent root, total-reply and per-thread quotas; existing root limit key is retained. Display windows remain independently bounded. */
internal object OfflineCommentPreferences {
    const val MODE = "offline_comment_display_mode"
    const val LIMIT = "offline_comment_cache_limit"
    const val DEFAULT_LIMIT = 200
    const val MAX_COMMENTS = 2500
    const val MAX_DISPLAY_ROWS = 5000
    const val REPLY_LIMIT = "offline_comment_reply_limit"
    const val THREAD_LIMIT = "offline_comment_thread_reply_limit"
    const val DEFAULT_REPLY_LIMIT = 1000
    const val DEFAULT_THREAD_LIMIT = 15
    fun replies(value: String?) = value?.toIntOrNull()?.takeIf { it in 0..2500 } ?: DEFAULT_REPLY_LIMIT
    fun threadReplies(value: String?) = value?.toIntOrNull()?.takeIf { it in 0..2500 } ?: DEFAULT_THREAD_LIMIT
    const val REQUEST_PAGE_SIZE = 20
    const val DEFAULT_MODE = "translated"
    val modes = listOf("translated", "original", "bilingual")
    fun limit(value: String?): Int = value?.toIntOrNull()?.takeIf { it in 1..MAX_COMMENTS } ?: DEFAULT_LIMIT
    fun pages(count: Int): Int { require(count in 1..MAX_COMMENTS); return (count + REQUEST_PAGE_SIZE - 1) / REQUEST_PAGE_SIZE }
    fun fromPages(pages: Int): Int { require(pages in 1..125); return pages * REQUEST_PAGE_SIZE }
}
