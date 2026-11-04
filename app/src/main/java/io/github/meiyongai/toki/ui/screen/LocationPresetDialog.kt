package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.model.LocationPresets

@Composable internal fun LocationPresetDialog(country: String, onSelected: (Map<String, Any>) -> Unit, onDismiss: () -> Unit) {
    val strings = LocalContext.current.resources
    var search by remember { mutableStateOf("") }
    val options = remember(country, search) { LocationPresets.search(country, search) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(strings.getString(R.string.location_presets_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(strings.getString(R.string.location_presets_note), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(search, { search = it }, label = { Text(strings.getString(R.string.location_presets_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (options.isEmpty()) item { Text(strings.getString(R.string.location_presets_missing)) }
                    items(options, key = { it.country + it.english }) { item ->
                        TextButton(onClick = { onSelected(LocationPresets.selection(item)) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(if (strings.configuration.locales[0].language == "zh") item.name else item.english)
                                Text(item.zone, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(strings.getString(R.string.common_close)) } })
}
