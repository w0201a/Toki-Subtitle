package io.github.meiyongai.toki.subtitle.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.provider.SubtitleFontStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Used from both the manager and the host-owned settings window; only the manager receives document access. */
@Composable internal fun SubtitleFontControls(asset: String, name: String, select: (Map<String, String>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    if (asset.isNotBlank()) Text(stringResource(R.string.subtitle_font_selected, name.ifBlank { "Custom font" }), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.subtitle_font_custom_note), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            try {
                context.startActivity(Intent().setClassName("io.github.meiyongai.toki.subtitle", "io.github.meiyongai.toki.subtitle.ui.SubtitleFontImportActivity"))
                error = false
            } catch (_: Exception) { error = true }
        }) { Text(stringResource(R.string.subtitle_font_import_title)) }
        TextButton(enabled = !loading, onClick = {
            loading = true
            scope.launch {
                try {
                    choices = withContext(Dispatchers.IO) {
                        val reply = context.contentResolver.call(Uri.parse("content://" + SubtitleFontStore.AUTHORITY), "recent", null, null)
                        val values = JSONArray(checkNotNull(reply?.getString("fonts")))
                        (0 until values.length()).map { index ->
                            val row = values.getJSONObject(index); row.getString("id").also { require(SubtitleFontStore.validId(it)) } to row.getString("name")
                        }
                    }
                    error = false
                } catch (_: Exception) { error = true }
                finally { loading = false }
            }
        }) { Text(stringResource(R.string.subtitle_font_recent)) }
    }
    if (error) Text(stringResource(R.string.subtitle_font_manager_unavailable), color = MaterialTheme.colorScheme.error)
    choices?.let { fonts -> AlertDialog(onDismissRequest = { choices = null }, title = { Text(stringResource(R.string.subtitle_font_recent)) },
        text = { LazyColumn(Modifier.heightIn(max = 320.dp)) {
            if (fonts.isEmpty()) item { Text(stringResource(R.string.subtitle_font_none)) }
            items(fonts, key = { it.first }) { item -> TextButton(onClick = {
                select(mapOf(SubtitleFontStore.ASSET to item.first, SubtitleFontStore.NAME to item.second)); choices = null
            }) { Text(item.second) } }
        } }, confirmButton = { TextButton(onClick = { choices = null }) { Text(stringResource(R.string.common_close)) } }) }
}
