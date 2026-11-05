package io.github.meiyongai.toki.subtitle.model

/** One count limit for a video, shared by top-level comments and replies. Display windows remain independently bounded. */
internal object OfflineCommentPreferences {
    const val MODE = "offline_comment_display_mode"
    const val LIMIT = "offline_comment_cache_limit"
    const val MAX_COMMENTS = 2500
    const val REQUEST_PAGE_SIZE = 20
    const val DEFAULT_MODE = "translated"
    val modes = listOf("translated", "original", "bilingual")
    fun limit(value: String?): Int = value?.toIntOrNull()?.takeIf { it in 1..MAX_COMMENTS } ?: MAX_COMMENTS
    fun pages(count: Int): Int { require(count in 1..MAX_COMMENTS); return (count + REQUEST_PAGE_SIZE - 1) / REQUEST_PAGE_SIZE }
    fun fromPages(pages: Int): Int { require(pages in 1..125); return pages * REQUEST_PAGE_SIZE }
}
