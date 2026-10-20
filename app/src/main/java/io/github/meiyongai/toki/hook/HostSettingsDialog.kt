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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.ui.TokiAppTheme
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
                TokiAppTheme {
                    if (subtitleOnly) {
                        SubtitleDialog { dismiss() }
                        return@TokiAppTheme
                    }
                    var subtitles by remember { mutableStateOf(false) }
                    var features by remember { mutableStateOf(false) }
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
                                Text(stringResource(R.string.host_settings_title), style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = { dismiss() }) { Text(stringResource(R.string.common_close)) }
                            }
                            Text(stringResource(R.string.host_settings_hint), style = MaterialTheme.typography.bodySmall)
                            Text(HostScanPresentation.summary(context, scan), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = scan.phase != HostScanPhase.IDLE, onClick = { dismiss(); HostScanController.reopenProgress() }) {
                                Text(stringResource(R.string.host_settings_progress))
                            }
                            TextButton(onClick = { subtitles = true }) { Text(stringResource(R.string.host_settings_subtitles)) }
                            TextButton(onClick = { features = !features }) { Text(stringResource(R.string.host_settings_features)) }
                            if (features) Box(Modifier.weight(1f)) { DashboardScreen() }
                        }
                    }
                    if (subtitles) SubtitleDialog { subtitles = false }
                }
            }
        })
    }
    override fun show() {
        super.show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
