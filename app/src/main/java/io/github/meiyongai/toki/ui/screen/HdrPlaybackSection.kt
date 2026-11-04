package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.hook.HdrPlaybackHook
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.ui.component.CompactPreferenceSwitch
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceSection

@Composable internal fun HdrPlaybackSection() {
    val context = LocalContext.current
    val strings = context.resources
    var disabled by remember { mutableStateOf(ConfigClient.getBoolean(context, HdrPlaybackHook.KEY)) }
    var error by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val subscription = ConfigClient.addStateListener {
            disabled = ConfigClient.getBoolean(context, HdrPlaybackHook.KEY)
        }
        onDispose { subscription.close() }
    }
    PreferenceSection(title = strings.getString(R.string.hdr_section)) {
        CompactPreferenceSwitch(title = strings.getString(R.string.hdr_disable_title),
            summary = strings.getString(R.string.hdr_disable_summary), checked = disabled,
            modifier = Modifier.padding(12.dp), onCheckedChange = {
                try { ConfigClient.putBoolean(context, HdrPlaybackHook.KEY, it); error = false }
                catch (_: Exception) { error = true }
            })
        Text(strings.getString(R.string.hdr_disable_scope), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall)
        if (error) Text(strings.getString(R.string.hdr_save_error), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
    }
}
