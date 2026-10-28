package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.*
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
import io.github.meiyongai.toki.subtitle.ui.screen.DashboardScreen
import io.github.meiyongai.toki.subtitle.ui.screen.SubtitleDialog

/** A host-owned window: no external Activity, provider, or overlay permission. */
internal class HostSettingsDialog(activity: Activity, resources: ModuleResources, private val subtitleOnly: Boolean = false) :
    ComponentDialog(io.github.meiyongai.toki.subtitle.provider.ConfigClient.EditingContext(resources.createActivityContext(activity)), android.R.style.Theme_Material_Light_NoActionBar) {
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
                        Surface(Modifier.fillMaxSize()) {
                            Column(Modifier.fillMaxSize().padding(16.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(stringResource(R.string.host_settings_title), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                                    TextButton(onClick = { dismiss() }) { Text(stringResource(R.string.common_close)) }
                                }
                                Text(stringResource(R.string.host_settings_hint), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { languages = !languages }) {
                                    Text("Toki · " + stringResource(R.string.language_title) + " · " +
                                        (if (language == AppLanguage.SYSTEM) stringResource(R.string.language_system) else language.displayName))
                                }
                                if (languages) {
                                    Box(Modifier.weight(1f)) { LanguageScreen() }
                                } else {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { subtitles = true }) { Text(stringResource(R.string.host_settings_subtitles)) }
                                        TextButton(onClick = { diagnostics = !diagnostics }) { Text(stringResource(R.string.host_settings_tools)) }
                                    }
                                    if (diagnostics) {
                                        Text(HostScanPresentation.summary(localizedContext, scan), style = MaterialTheme.typography.bodySmall)
                                        TextButton(enabled = scan.phase != HostScanPhase.IDLE, onClick = { dismiss(); HostScanController.reopenProgress() }) {
                                            Text(stringResource(R.string.host_settings_progress))
                                        }
                                        TextButton(enabled = !capturing, onClick = {
                                            capturing = true
                                            HostPerformance.capture { performance = it; capturing = false }
                                        }) { Text(if (capturing) "正在采样…" else "性能采样（手动）") }
                                    }
                                    Box(Modifier.weight(1f)) { DashboardScreen() }
                                }
                            }
                        }
                        performance?.let { report ->
                            AlertDialog(onDismissRequest = { performance = null }, title = { Text("性能采样") },
                                text = { Text(report, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
                                confirmButton = { TextButton(onClick = { performance = null }) { Text("关闭") } })
                        }
                        if (subtitles) SubtitleDialog { subtitles = false }
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
