package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.model.SharePanelItem
import io.github.meiyongai.toki.subtitle.model.SharePanelPolicy
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.ui.component.CompactPreferenceSwitch
import io.github.meiyongai.toki.subtitle.ui.component.MorphingPreferenceContainer
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceSection
import io.github.meiyongai.toki.subtitle.ui.component.getGroupedShape
import io.github.meiyongai.toki.subtitle.ui.component.stableDialogHeight

/** Each toggle is independent; no action is hidden by default, and raw keys are an explicit expert option. */
@Composable
internal fun SharePanelCleanupSection() {
    val context = LocalContext.current
    val strings = context.resources
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var values by remember { mutableStateOf(SharePanelItem.entries.associateWith { ConfigClient.getBoolean(context, it.key) }) }
    var customEnabled by remember { mutableStateOf(ConfigClient.getBoolean(context, SharePanelPolicy.CUSTOM_ENABLED)) }
    var savedCustom by remember { mutableStateOf(ConfigClient.getString(context, SharePanelPolicy.CUSTOM_KEYS, "").orEmpty()) }
    var customDraft by remember { mutableStateOf(savedCustom) }
    var error by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val subscription = ConfigClient.addStateListener {
            values = SharePanelItem.entries.associateWith { ConfigClient.getBoolean(context, it.key) }
            customEnabled = ConfigClient.getBoolean(context, SharePanelPolicy.CUSTOM_ENABLED)
            val next = ConfigClient.getString(context, SharePanelPolicy.CUSTOM_KEYS, "").orEmpty()
            if (customDraft == savedCustom) customDraft = next
            savedCustom = next
        }
        onDispose { subscription.close() }
    }
    fun save(key: String, value: Boolean) {
        try { ConfigClient.putBoolean(context, key, value); error = false } catch (_: Exception) { error = true }
    }
    PreferenceSection(title = strings.getString(R.string.share_cleanup_title)) {
        MorphingPreferenceContainer(shape = getGroupedShape(0, 1), onClick = { open = true }) {
            Column(Modifier.padding(16.dp)) {
                Text(strings.getString(R.string.share_cleanup_choose))
                Text(strings.getString(R.string.share_cleanup_selected, values.values.count { it }),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(strings.getString(R.string.share_cleanup_title)) },
        text = {
            Column(Modifier.stableDialogHeight(440.dp)) {
                Text(strings.getString(R.string.share_cleanup_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text(strings.getString(R.string.layout_filter_hint)) })
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    val matching = SharePanelItem.entries.filter { item ->
                        query.isBlank() || strings.getString(item.title).contains(query.trim(), ignoreCase = true) ||
                            item.hostKeys.any { it.contains(query.trim(), ignoreCase = true) }
                    }
                    if (matching.isEmpty()) Text(strings.getString(R.string.layout_filter_empty), Modifier.padding(12.dp))
                    matching.forEach { item ->
                        CompactPreferenceSwitch(title = strings.getString(item.title), checked = values.getValue(item),
                            onCheckedChange = { save(item.key, it) }, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    CompactPreferenceSwitch(title = strings.getString(R.string.share_cleanup_custom_enabled), checked = customEnabled,
                        onCheckedChange = { save(SharePanelPolicy.CUSTOM_ENABLED, it) })
                    Text(strings.getString(R.string.share_cleanup_custom_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = customDraft, onValueChange = { customDraft = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                        label = { Text(strings.getString(R.string.share_cleanup_custom_keys)) })
                    TextButton(onClick = {
                        try {
                            SharePanelPolicy.customKeys(customDraft)
                            ConfigClient.putValues(context, mapOf(SharePanelPolicy.CUSTOM_KEYS to customDraft,
                                SharePanelPolicy.CUSTOM_ENABLED to customDraft.isNotBlank()))
                            error = false
                        } catch (_: Exception) { error = true }
                    }) { Text(strings.getString(R.string.common_save)) }
                    if (error) Text(strings.getString(R.string.share_cleanup_save_error), color = MaterialTheme.colorScheme.error)
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text(strings.getString(R.string.common_done)) } })
}
