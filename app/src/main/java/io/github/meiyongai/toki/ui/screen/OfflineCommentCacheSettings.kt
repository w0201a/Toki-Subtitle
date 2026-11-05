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
    DisposableEffect(context) {
        val handle = ConfigClient.addStateListener { value = OfflineCommentPreferences.limit(ConfigClient.getString(context, OfflineCommentPreferences.LIMIT)) }
        onDispose { handle.close() }
    }
    TextButton(onClick = {
        OfflineCommentLimitDialog.show(context, value) { next ->
            ConfigClient.putString(context, OfflineCommentPreferences.LIMIT, (next ?: 2500).toString())
        }
    }) { Text(context.getString(R.string.offline_comment_limit_current, value, OfflineCommentPreferences.pages(value))) }
}
