package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.hook.HoldSpeedGestureHook
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.ui.component.CompactPreferenceSwitch
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceSection

@Composable
internal fun HoldSpeedGestureSection() {
    val context = LocalContext.current
    val strings = context.resources
    var enabled by remember { mutableStateOf(ConfigClient.getBoolean(context, HoldSpeedGestureHook.KEY)) }
    var error by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val subscription = ConfigClient.addStateListener {
            enabled = ConfigClient.getBoolean(context, HoldSpeedGestureHook.KEY)
        }
        onDispose { subscription.close() }
    }
    PreferenceSection(title = strings.getString(R.string.hold_speed_section)) {
        CompactPreferenceSwitch(title = strings.getString(R.string.hold_speed_down_lock),
            summary = strings.getString(R.string.hold_speed_down_lock_hint), checked = enabled,
            modifier = Modifier.padding(12.dp), onCheckedChange = {
                try { ConfigClient.putBoolean(context, HoldSpeedGestureHook.KEY, it); error = false }
                catch (_: Exception) { error = true }
            })
        Text(strings.getString(R.string.hold_speed_scope_hint), Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall)
        if (error) Text(strings.getString(R.string.hold_speed_save_error), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
    }
}
