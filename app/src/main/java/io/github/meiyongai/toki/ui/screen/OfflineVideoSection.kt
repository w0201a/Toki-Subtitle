package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.hook.OfflineCommentDownloadHook
import io.github.meiyongai.toki.subtitle.hook.OfflineVideoHook
import io.github.meiyongai.toki.subtitle.hook.CachedCommentTranslateHook
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.ui.component.CompactPreferenceSwitch
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceSection

@Composable
internal fun OfflineVideoSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ConfigClient.getBoolean(context, OfflineVideoHook.KEY)) }
    var failed by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf(ConfigClient.getBoolean(context,OfflineCommentDownloadHook.KEY)) }
    var activeFill by remember { mutableStateOf(ConfigClient.getBoolean(context,OfflineCommentDownloadHook.BACKFILL)) }
    var passiveFill by remember { mutableStateOf(ConfigClient.getBoolean(context,OfflineCommentDownloadHook.PASSIVE)) }

    var directEntry by remember { mutableStateOf(ConfigClient.getBoolean(context, OfflineVideoHook.KEY_ENTRY)) }
    var online by remember { mutableStateOf(ConfigClient.getBoolean(context, CachedCommentTranslateHook.KEY)) }
    DisposableEffect(context) {
        val subscription = ConfigClient.addStateListener {
            enabled = ConfigClient.getBoolean(context, OfflineVideoHook.KEY)
            download=ConfigClient.getBoolean(context,OfflineCommentDownloadHook.KEY)
            activeFill=ConfigClient.getBoolean(context,OfflineCommentDownloadHook.BACKFILL)
            passiveFill=ConfigClient.getBoolean(context,OfflineCommentDownloadHook.PASSIVE)

            directEntry = ConfigClient.getBoolean(context, OfflineVideoHook.KEY_ENTRY)
            online = ConfigClient.getBoolean(context, CachedCommentTranslateHook.KEY)
        }
        onDispose { subscription.close() }
    }
    PreferenceSection(title = context.getString(R.string.offline_video_feature)) {
        CompactPreferenceSwitch(title = context.getString(R.string.offline_video_enable),
            summary = context.getString(R.string.offline_video_summary), checked = enabled,
            modifier = Modifier.padding(12.dp), onCheckedChange = {
                try { ConfigClient.putBoolean(context, OfflineVideoHook.KEY, it); failed = false }
                catch (_: Exception) { failed = true }
            })
        CompactPreferenceSwitch(title = context.getString(R.string.offline_comment_button_enable),
            summary = context.getString(R.string.offline_comment_button_summary), checked = directEntry,
            modifier = Modifier.padding(12.dp), onCheckedChange = {
                try { ConfigClient.putBoolean(context, OfflineVideoHook.KEY_ENTRY, it); failed = false }
                catch (_: Exception) { failed = true }
            })
        CompactPreferenceSwitch(title = context.getString(R.string.cached_comment_online_feature),
            summary = context.getString(R.string.cached_comment_online_summary), checked = online,
            modifier = Modifier.padding(12.dp), onCheckedChange = {
                try { ConfigClient.putBoolean(context, CachedCommentTranslateHook.KEY, it); failed = false }
                catch (_: Exception) { failed = true }
            })
        CompactPreferenceSwitch(title=context.getString(R.string.offline_comment_download_title),
            summary=context.getString(R.string.offline_comment_download_hint),checked=download,modifier=Modifier.padding(12.dp),onCheckedChange={
                try{ConfigClient.putBoolean(context,OfflineCommentDownloadHook.KEY,it);failed=false}catch(_:Exception){failed=true}
            })
        CompactPreferenceSwitch(title=context.getString(R.string.offline_comment_backfill_title),
            summary=context.getString(R.string.offline_comment_backfill_hint),checked=activeFill,modifier=Modifier.padding(12.dp),onCheckedChange={
                try{ConfigClient.putBoolean(context,OfflineCommentDownloadHook.BACKFILL,it);failed=false}catch(_:Exception){failed=true}
            })
        CompactPreferenceSwitch(title=context.getString(R.string.offline_comment_passive_title),
            summary=context.getString(R.string.offline_comment_passive_hint),checked=passiveFill,modifier=Modifier.padding(12.dp),onCheckedChange={
                try{ConfigClient.putBoolean(context,OfflineCommentDownloadHook.PASSIVE,it);failed=false}catch(_:Exception){failed=true}
            })
        OfflineCommentCacheSettings()
        OfflineCommentStatusSection()
        if (failed) Text(context.getString(R.string.manual_comment_save_error), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
    }
}
