package io.github.meiyongai.toki.subtitle.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.provider.SubtitleFontStore
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

/** Always asks the user to pick a document; incoming Intent data/extras are never imported. */
class SubtitleFontImportActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[SubtitleFontImportModel::class.java] }
    private var launched = false
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && uri.scheme == "content") model.importFont(applicationContext, uri)
        else model.state.value = SubtitleFontImportModel.State(cancelled = true)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean("pickerLaunched") == true
        setContent {
            val state by model.state.collectAsState()
            LocalizedTokiApp {
              TokiAppTheme {
                AlertDialog(onDismissRequest = { if (!state.busy) finish() }, title = { Text(stringResource(R.string.subtitle_font_import_title)) },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.subtitle_font_import_note))
                        when {
                            state.busy -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.subtitle_font_import_busy)) }
                            state.name != null -> Text(stringResource(R.string.subtitle_font_import_done, state.name!!))
                            state.failed -> Text(stringResource(R.string.subtitle_font_import_failed))
                            state.cancelled -> Text(stringResource(R.string.subtitle_font_import_cancelled))
                        }
                    } },
                    confirmButton = { TextButton(enabled = !state.busy, onClick = { launched = true; picker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.subtitle_font_choose_file)) } },
                    dismissButton = { TextButton(enabled = !state.busy, onClick = { finish() }) { Text(stringResource(R.string.common_close)) } })
                LaunchedEffect(Unit) { if (!launched && !model.state.value.busy) { launched = true; picker.launch(arrayOf("*/*")) } }
              }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("pickerLaunched", launched); super.onSaveInstanceState(outState) }
}

class SubtitleFontImportModel : ViewModel() {
    data class State(val busy: Boolean = false, val name: String? = null, val failed: Boolean = false, val cancelled: Boolean = false)
    val state = MutableStateFlow(State())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "toki-font-import").apply { isDaemon = true } }
    fun importFont(context: Context, uri: Uri) {
        if (state.value.busy) return
        state.value = State(busy = true)
        val app = context.applicationContext
        worker.execute {
            try {
                var name = "Custom font"
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) name = cursor.getString(0) ?: name
                }
                val stream = app.contentResolver.openInputStream(uri) ?: error("Font document unavailable")
                val asset = SubtitleFontStore.importFont(app, name, stream)
                ConfigClient.putValues(app, mapOf(SubtitleFontStore.ASSET to asset.id, SubtitleFontStore.NAME to asset.name))
                state.value = State(name = asset.name)
            } catch (_: Exception) { state.value = State(failed = true) }
              catch (_: OutOfMemoryError) { state.value = State(failed = true) }
        }
    }
    override fun onCleared() { worker.shutdown() }
}
