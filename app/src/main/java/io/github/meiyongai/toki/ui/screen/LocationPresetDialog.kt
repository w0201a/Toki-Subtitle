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
import io.github.meiyongai.toki.subtitle.model.AdministrativeRegions

@Composable internal fun LocationPresetDialog(country: String, onSelected: (Map<String, Any>) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val strings = context.resources
    var search by remember { mutableStateOf("") }
    val loaded = remember { runCatching { AdministrativeRegions.read(context.assets.open("geography/admin-regions.tsv").bufferedReader()) } }
    val regions = loaded.getOrDefault(emptyList())
    var cities by remember(country) { mutableStateOf(false) }
    val options = remember(country, search) { LocationPresets.search(country, search) }
    val divisions = remember(country, search, regions) { AdministrativeRegions.search(regions, country, search) }
    val chinese = strings.configuration.locales[0].language == "zh"
    AlertDialog(onDismissRequest = onDismiss, title = { Text(strings.getString(R.string.region_directory_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(strings.getString(R.string.region_directory_note), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !cities, onClick = { cities = false }, label = { Text(strings.getString(R.string.region_directory_regions)) })
                    FilterChip(selected = cities, onClick = { cities = true }, label = { Text(strings.getString(R.string.region_directory_cities)) })
                }
                OutlinedTextField(search, { search = it }, label = { Text(strings.getString(R.string.region_directory_search)) }, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    if (cities) {
                        if (options.isEmpty()) item { Text(strings.getString(R.string.location_presets_missing)) }
                        items(options, key = { it.country + it.english }) { item ->
                            TextButton(onClick = { onSelected(LocationPresets.selection(item)) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) { Text(if (chinese) item.name else item.english); Text(item.zone, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    } else {
                        if (loaded.isFailure) item { Text(strings.getString(R.string.region_directory_unavailable)) }
                        else if (divisions.isEmpty()) item { Text(strings.getString(R.string.region_directory_empty)) }
                        items(divisions, key = { it.code }) { item ->
                            TextButton(enabled = item.hasLocation, onClick = { onSelected(AdministrativeRegions.selection(item)) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(item.name(chinese))
                                    Text(if (item.hasLocation) item.city + " · " + item.zone else strings.getString(R.string.region_directory_no_point), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(strings.getString(R.string.common_close)) } })
}
