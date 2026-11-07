package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.hook.OfflineCommentLimitDialog
import io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences
import io.github.meiyongai.toki.subtitle.provider.ConfigClient

@Composable
internal fun OfflineCommentCacheSettings() {
    val context = LocalContext.current
    var value by remember { mutableIntStateOf(OfflineCommentPreferences.limit(ConfigClient.getString(context, OfflineCommentPreferences.LIMIT))) }
    var replies by remember { mutableIntStateOf(OfflineCommentPreferences.replies(ConfigClient.getString(context, OfflineCommentPreferences.REPLY_LIMIT))) }
    var newCore by remember { mutableStateOf(ConfigClient.getBoolean(context, "comment_caption_core_v3")) }
    var thread by remember { mutableIntStateOf(OfflineCommentPreferences.threadReplies(ConfigClient.getString(context, OfflineCommentPreferences.THREAD_LIMIT))) }
    DisposableEffect(context) {
        val handle = ConfigClient.addStateListener {
            value = OfflineCommentPreferences.limit(ConfigClient.getString(context, OfflineCommentPreferences.LIMIT))
            replies = OfflineCommentPreferences.replies(ConfigClient.getString(context, OfflineCommentPreferences.REPLY_LIMIT))
            newCore = ConfigClient.getBoolean(context, "comment_caption_core_v3")
            thread = OfflineCommentPreferences.threadReplies(ConfigClient.getString(context, OfflineCommentPreferences.THREAD_LIMIT))
        }
        onDispose { handle.close() }
    }
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
            Text("评论与字幕重构核心（测试）")
            Text("下次手动重启 TikTok 后切换；不自动重启、不清理旧缓存。关闭可回退旧链路。")
        }
        androidx.compose.material3.Switch(checked = newCore, onCheckedChange = { enabled ->
            ConfigClient.putBoolean(context, "comment_caption_core_v3", enabled)
            newCore = enabled
        })
    }
    TextButton(onClick = {
        OfflineCommentLimitDialog.show(context, value) { next ->
            ConfigClient.putString(context, OfflineCommentPreferences.LIMIT, (next ?: OfflineCommentPreferences.DEFAULT_LIMIT).toString())
        }
    }) { Text(context.getString(R.string.offline_comment_limit_current, value, OfflineCommentPreferences.pages(value))) }
    TextButton(onClick = {
        OfflineCommentLimitDialog.replies(context, replies, thread) { total, perThread ->
            ConfigClient.putValues(context, mapOf(OfflineCommentPreferences.REPLY_LIMIT to total.toString(),
                OfflineCommentPreferences.THREAD_LIMIT to perThread.toString()))
        }
    }) { Text(context.getString(R.string.toki_reply_limits, replies, thread)) }

}
