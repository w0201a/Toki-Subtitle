package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.model.PageCleanupGroup
import io.github.meiyongai.toki.subtitle.model.PageCleanupItem
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.ui.component.CompactPreferenceSwitch
import io.github.meiyongai.toki.subtitle.ui.component.MorphingPreferenceContainer
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceSection
import io.github.meiyongai.toki.subtitle.ui.component.getGroupedShape
import io.github.meiyongai.toki.subtitle.ui.component.stableDialogHeight

/** Independently persisted presentation controls, never an implied permission to delete messages or filter data. */
@Composable
internal fun PageCleanupSection() {
    val context = LocalContext.current
    val strings = context.resources
    var open by remember { mutableStateOf(false) }
    var values by remember { mutableStateOf(PageCleanupItem.entries.associateWith { ConfigClient.getBoolean(context, it.key) }) }
    var error by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val subscription = ConfigClient.addStateListener {
            values = PageCleanupItem.entries.associateWith { ConfigClient.getBoolean(context, it.key) }
        }
        onDispose { subscription.close() }
    }
    PreferenceSection(title = strings.getString(R.string.page_cleanup_title)) {
        MorphingPreferenceContainer(shape = getGroupedShape(0, 1), onClick = { open = true }) {
            Column(Modifier.padding(16.dp)) {
                Text(strings.getString(R.string.page_cleanup_choose))
                Text(strings.getString(R.string.page_cleanup_selected, values.values.count { it }), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(strings.getString(R.string.page_cleanup_title)) },
        text = {
            Column(Modifier.stableDialogHeight(460.dp).verticalScroll(rememberScrollState())) {
                Text(strings.getString(R.string.page_cleanup_hint), style = MaterialTheme.typography.bodySmall)
                PageCleanupGroup.entries.forEach { group ->
                    Text(strings.getString(group.title), modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall)
                    PageCleanupItem.entries.filter { it.group == group }.forEach { item ->
                        CompactPreferenceSwitch(title = strings.getString(item.title), checked = values.getValue(item),
                            onCheckedChange = { enabled ->
                                try { ConfigClient.putBoolean(context, item.key, enabled); error = false }
                                catch (_: Exception) { error = true }
                            }, modifier = Modifier.padding(top = 8.dp))
                        Text(strings.getString(item.summary), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (error) Text(strings.getString(R.string.page_cleanup_save_error), color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text(strings.getString(R.string.common_done)) } })
}
