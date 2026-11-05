package io.github.meiyongai.toki.subtitle.hook

import android.content.Context

/** Per-video overrides remain inside the owning host/account; changing a limit never removes cached comments or assets. */
internal object OfflineCommentLimitOverrides {
    private fun preferences(context: Context) = context.getSharedPreferences("toki_offline_comment_limits_v1", Context.MODE_PRIVATE)
    fun effective(context: Context, account: String, video: String, fallback: Int): Int {
        val value = try { preferences(context).getInt(CommentSnapshotStore.key(account, video), 0) } catch (_: ClassCastException) { 0 }
        return value.takeIf { it in 1..2500 } ?: fallback
    }
    fun set(context: Context, account: String, video: String, value: Int?) {
        require(value == null || value in 1..2500)
        check(preferences(context).edit().putInt(CommentSnapshotStore.key(account, video), value ?: 0).commit())
    }
}
