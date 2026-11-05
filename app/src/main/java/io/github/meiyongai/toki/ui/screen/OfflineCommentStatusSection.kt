package io.github.meiyongai.toki.subtitle.ui.screen

import android.os.Bundle
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
import io.github.meiyongai.toki.subtitle.hook.HookRuntime
import io.github.meiyongai.toki.subtitle.hook.OfflineCommentDownloadHook
import io.github.meiyongai.toki.subtitle.provider.HookDiagnostics
import io.github.meiyongai.toki.subtitle.ui.component.stableDialogHeight

/** Explicit settings-only read. No broadcasts, polling, overlays or requests until the user opens this dialog. */
@Composable
internal fun OfflineCommentStatusSection() {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var reports by remember { mutableStateOf<List<Pair<String, Bundle>>>(emptyList()) }
    LaunchedEffect(open, refresh) {
        if (open) {
            loading = true
            reports = try {
                val local = OfflineCommentDownloadHook.statusSnapshot()
                if (local != null) listOf((HookRuntime.hostContext?.packageName ?: "Toki") to local)
                else {
                    val sessions = HookDiagnostics.request(context)
                    HookDiagnostics.hosts.mapNotNull { host -> sessions.getBundle(host)?.getBundle("offlineComments")?.let { host to it } }
                }
            } catch (_: Exception) { emptyList() }
            loading = false
        }
    }
    TextButton(onClick = { open = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
        Text(context.getString(R.string.offline_comment_status_open))
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(context.getString(R.string.offline_comment_status_title)) },
        text = {
            Column(Modifier.stableDialogHeight(360.dp).verticalScroll(rememberScrollState())) {
                Text(context.getString(R.string.offline_comment_status_hint), style = MaterialTheme.typography.bodySmall)
                if (loading) Text(context.getString(R.string.offline_comments_loading), Modifier.padding(top = 12.dp))
                else if (reports.isEmpty()) Text(context.getString(R.string.offline_comment_status_unavailable), Modifier.padding(top = 12.dp))
                for ((host, report) in reports) {
                    Text(host, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
                    Text(context.getString(R.string.offline_comment_status_values, report.getInt("videos"), report.getInt("originals"),
                        report.getInt("sourcesReady"), report.getInt("ready"), report.getInt("retrying"), report.getInt("queued"), report.getInt("batchSize")))
                    if (report.containsKey("mediaReady")) Text(context.getString(R.string.offline_comment_media_status, report.getInt("mediaReady"), report.getInt("commentLimit")))
                    if (!report.getBoolean("verified")) Text(context.getString(R.string.offline_comment_status_preparing))
                    else if (!report.getBoolean("network") && report.getInt("ready") < report.getInt("videos")) Text(context.getString(R.string.offline_comment_status_wait_network))
                    val failure = report.getString("failure").orEmpty()
                    if (failure.isNotEmpty()) Text(context.getString(when {
                        "stage=translation" in failure -> R.string.offline_comment_status_translation_retry
                        "stage=source" in failure -> R.string.offline_comment_status_source_retry
                        else -> R.string.offline_comment_status_retry
                    }), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text(context.getString(R.string.common_done)) } },
        dismissButton = { TextButton(onClick = { refresh++ }, enabled = !loading) { Text(context.getString(R.string.offline_comment_status_refresh)) } })
}
