package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.ui.TokiAppTheme
import io.github.meiyongai.toki.subtitle.ui.LocalizedTokiApp
import io.github.meiyongai.toki.subtitle.ui.LocalAppLanguage
import io.github.meiyongai.toki.subtitle.ui.screen.LanguageScreen
import io.github.meiyongai.toki.subtitle.util.AppLanguage
import io.github.meiyongai.toki.subtitle.ui.screen.TokiSettingsHub
import io.github.meiyongai.toki.subtitle.ui.screen.SubtitleDialog

/** A host-owned window: no external Activity, provider, or overlay permission. */
internal class HostSettingsDialog(activity: Activity, resources: ModuleResources, private val subtitleOnly: Boolean = false) :
    ComponentDialog(io.github.meiyongai.toki.subtitle.provider.ConfigClient.EditingContext(resources.createActivityContext(activity)), R.style.Theme_Toki) {
    init {
        if (subtitleOnly) window?.setBackgroundDrawableResource(android.R.color.transparent)
        setContentView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val systemConfiguration = android.content.res.Configuration(LocalConfiguration.current).apply {
                    setLocales(android.content.res.Resources.getSystem().configuration.locales)
                }
                LocalizedTokiApp(systemConfiguration) {
                    TokiAppTheme {
                        if (subtitleOnly) {
                            SubtitleDialog { dismiss() }
                            return@TokiAppTheme
                        }
                        var performance by remember { mutableStateOf<String?>(null) }
                        var capturing by remember { mutableStateOf(false) }
                        var subtitles by remember { mutableStateOf(false) }
                        var diagnostics by remember { mutableStateOf(false) }
                        var languages by remember { mutableStateOf(false) }
                        var manualAdaptation by remember { mutableStateOf(false) }
                        var moreActions by remember { mutableStateOf(false) }
                        BackHandler(enabled = languages || diagnostics) { languages = false; diagnostics = false }
                        val settingsSync by io.github.meiyongai.toki.subtitle.provider.HostSettingsMirror.status.collectAsState()
                        DisposableEffect(Unit) {
                            io.github.meiyongai.toki.subtitle.provider.HostSettingsMirror.kick()
                            onDispose { }
                        }
                        val localizedContext = LocalContext.current
                        val language = LocalAppLanguage.current.language
                        var scan by remember { mutableStateOf(HostScanController.session.status) }
                        DisposableEffect(Unit) {
                            val main = android.os.Handler(android.os.Looper.getMainLooper())
                            val update = Runnable { scan = HostScanController.session.status }
                            val subscription = HostScanController.session.addListener {
                                main.removeCallbacks(update); main.post(update)
                            }
                            onDispose { subscription.close(); main.removeCallbacks(update) }
                        }
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                                Surface(color = MaterialTheme.colorScheme.surface) {
                                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Text("Toki", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                        IconButton(onClick = { subtitles = true }) {
                                            Icon(Icons.Outlined.Subtitles, stringResource(R.string.host_settings_subtitles))
                                        }
                                        Box {
                                            IconButton(onClick = { moreActions = true }) {
                                                Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.toki_more_actions))
                                            }
                                            DropdownMenu(expanded = moreActions, onDismissRequest = { moreActions = false }) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.language_title)) }, onClick = {
                                                    moreActions = false; languages = !languages; diagnostics = false
                                                })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.manual_adaptation_title)) }, onClick = {
                                                    moreActions = false; manualAdaptation = true
                                                })
                                                DropdownMenuItem(text = { Text(stringResource(R.string.host_settings_tools)) }, onClick = {
                                                    moreActions = false; diagnostics = !diagnostics; languages = false
                                                })
                                            }
                                        }
                                        IconButton(onClick = { dismiss() }) {
                                            Icon(Icons.Outlined.Close, stringResource(R.string.common_close))
                                        }
                                    }
                                }
                                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(when {
                                        settingsSync.error == "HistoricalLocalValuesNotConfirmed" -> R.string.host_settings_sync_history
                                        settingsSync.error != null -> R.string.host_settings_sync_error
                                        settingsSync.pending > 0 -> R.string.host_settings_sync_pending
                                        settingsSync.revision != null -> R.string.host_settings_sync_saved
                                        else -> R.string.host_settings_sync_ready
                                    }, settingsSync.pending), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                        color = if (settingsSync.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (settingsSync.pending > 0 || settingsSync.error != null) TextButton(onClick = {
                                        io.github.meiyongai.toki.subtitle.provider.ConfigClient.queueUnsyncedHostValues()
                                        io.github.meiyongai.toki.subtitle.provider.HostSettingsMirror.kick()
                                    }) { Text(stringResource(R.string.host_settings_sync_retry)) }
                                }
                                when {
                                    languages -> Box(Modifier.weight(1f)) { LanguageScreen() }
                                    diagnostics -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(stringResource(R.string.host_settings_tools), style = MaterialTheme.typography.headlineSmall)
                                        Text(HostScanPresentation.summary(localizedContext, scan), style = MaterialTheme.typography.bodyMedium)
                                        OutlinedButton(enabled = scan.phase != HostScanPhase.IDLE,
                                            onClick = { dismiss(); HostScanController.reopenProgress() }) {
                                            Text(stringResource(R.string.host_settings_progress))
                                        }
                                        OutlinedButton(enabled = !capturing, onClick = {
                                            capturing = true
                                            HostPerformance.capture { performance = it; capturing = false }
                                        }) { Text(if (capturing) "正在采样…" else "性能采样（手动）") }
                                    }
                                    else -> Box(Modifier.weight(1f)) {
                                        TokiSettingsHub(onDiagnostics = { diagnostics = true })
                                    }
                                }
                            }
                        }
                        performance?.let { report ->
                            AlertDialog(onDismissRequest = { performance = null }, title = { Text("性能采样") },
                                text = { Text(report, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                                confirmButton = { TextButton(onClick = { performance = null }) { Text("关闭") } })
                        }
                        if (subtitles) SubtitleDialog { subtitles = false }
                        if (manualAdaptation) io.github.meiyongai.toki.subtitle.ui.screen.ManualHostAdaptationDialog(
                            localizedContext.packageName) { manualAdaptation = false }
                    }
                }
            }
        })
    }
    override fun show() {
        super.show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
