package io.github.meiyongai.toki.subtitle.hook

import android.content.Context

/** Per-video overrides remain inside the owning host/account; changing a limit never removes cached comments or assets. */
internal object OfflineCommentLimitOverrides {
    private fun preferences(context: Context) = context.getSharedPreferences("toki_offline_comment_limits_v1", Context.MODE_PRIVATE)
    fun savedLimit(context: Context, account: String, video: String): Int? =
        (try { preferences(context).getInt(CommentSnapshotStore.key(account, video), 0) } catch (_: ClassCastException) { 0 })
            .takeIf { it in 1..io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences.MAX_COMMENTS }
    fun effective(context: Context, account: String, video: String, fallback: Int): Int =
        savedLimit(context, account, video) ?: fallback
    fun configured(context: Context, account: String, video: String): Int = effective(context, account, video,
        io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences.limit(
            io.github.meiyongai.toki.subtitle.provider.ConfigClient.getString(
                io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences.LIMIT)))
    fun quotas(context: Context, account: String, video: String): OfflineCommentBudget.Quotas {
        val settings = io.github.meiyongai.toki.subtitle.provider.ConfigClient.effectiveSnapshotOrNull()
        val prefs = io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences
        return OfflineCommentBudget.Quotas(effective(context, account, video, prefs.limit(settings?.string(prefs.LIMIT))),
            prefs.replies(settings?.string(prefs.REPLY_LIMIT)), prefs.threadReplies(settings?.string(prefs.THREAD_LIMIT)))
    }
    fun set(context: Context, account: String, video: String, value: Int?) {
        require(value == null || value in 1..2500)
        check(preferences(context).edit().putInt(CommentSnapshotStore.key(account, video), value ?: 0).commit())
    }
}
