package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.hook.OfflineVideoHook
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.ui.component.CompactPreferenceSwitch
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceSection

@Composable
internal fun OfflineVideoSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ConfigClient.getBoolean(context, OfflineVideoHook.KEY)) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val subscription = ConfigClient.addStateListener { enabled = ConfigClient.getBoolean(context, OfflineVideoHook.KEY) }
        onDispose { subscription.close() }
    }
    PreferenceSection(title = context.getString(R.string.offline_video_feature)) {
        CompactPreferenceSwitch(title = context.getString(R.string.offline_video_enable),
            summary = context.getString(R.string.offline_video_summary), checked = enabled,
            modifier = Modifier.padding(12.dp), onCheckedChange = {
                try { ConfigClient.putBoolean(context, OfflineVideoHook.KEY, it); failed = false }
                catch (_: Exception) { failed = true }
            })
        if (failed) Text(context.getString(R.string.manual_comment_save_error), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
    }
}
