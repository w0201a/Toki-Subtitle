package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.hook.ManualCommentTranslateHook
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.ui.component.CompactPreferenceSwitch
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceSection

@Composable
internal fun ManualCommentTranslationSection() {
    val context = LocalContext.current
    val strings = context.resources
    var enabled by remember { mutableStateOf(ConfigClient.getBoolean(context, ManualCommentTranslateHook.KEY)) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val subscription = ConfigClient.addStateListener {
            enabled = ConfigClient.getBoolean(context, ManualCommentTranslateHook.KEY)
        }
        onDispose { subscription.close() }
    }
    PreferenceSection(title = strings.getString(R.string.manual_comment_feature)) {
        CompactPreferenceSwitch(title = strings.getString(R.string.manual_comment_enable),
            summary = strings.getString(R.string.manual_comment_setting_hint), checked = enabled,
            modifier = Modifier.padding(12.dp), onCheckedChange = {
                try { ConfigClient.putBoolean(context, ManualCommentTranslateHook.KEY, it); failed = false }
                catch (_: Exception) { failed = true }
            })
        if (failed) Text(strings.getString(R.string.manual_comment_save_error), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
    }
}
