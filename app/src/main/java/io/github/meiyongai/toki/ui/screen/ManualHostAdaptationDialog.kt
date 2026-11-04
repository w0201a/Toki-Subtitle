package io.github.meiyongai.toki.subtitle.ui.screen

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.provider.ManualHostAdaptation
import io.github.meiyongai.toki.subtitle.util.TikTokInstallStatus
import io.github.meiyongai.toki.subtitle.util.TikTokVersionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saving a retry is not a scan-success receipt. The user controls the host restart. */
@Composable
internal fun ManualHostAdaptationDialog(
    initialHost: String?,
    loadHosts: suspend (Context) -> List<TikTokInstallStatus> = { ctx ->
        withContext(Dispatchers.IO) { TikTokVersionHelper.getInstalledTikTokStatuses(ctx) }
    },
    saveRequest: suspend (Context, String) -> Unit = { ctx, host ->
        withContext(Dispatchers.IO) { saveManualHostAdaptation(ctx, host) }
    },
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val strings = context.resources
    val scope = rememberCoroutineScope()
    var hosts by remember { mutableStateOf<List<TikTokInstallStatus>?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var savedHost by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        try {
            hosts = loadHosts(context).filter {
                context.packageName !in ManualHostAdaptation.packages || it.packageName == context.packageName
            }
            selected = hosts?.firstOrNull { it.isInstalled && it.packageName == initialHost }?.packageName
                ?: hosts?.firstOrNull { it.isInstalled }?.packageName
        } catch (_: Exception) { hosts = emptyList(); error = R.string.manual_adaptation_load_error }
    }
    fun open(host: String, information: Boolean) {
        try {
            require(host in ManualHostAdaptation.packages)
            val intent = if (information) Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$host"))
                else context.packageManager.getLaunchIntentForPackage(host) ?: throw IllegalStateException("No launcher")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { error = R.string.manual_adaptation_open_error }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(strings.getString(R.string.manual_adaptation_title)) },
        text = {
            // Dialog windows replace LocalContext; retain the selected module language for the body too.
            CompositionLocalProvider(LocalContext provides context) {
                ManualHostAdaptationContent(hosts, selected, savedHost, saving, error,
                    onSelected = { selected = it }, onOpen = { open(it, false) })
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && (savedHost != null || hosts.orEmpty().any { it.isInstalled && it.packageName == selected }),
                onClick = {
                    val saved = savedHost
                    if (saved != null) open(saved, true) else {
                        val target = selected ?: return@TextButton
                        saving = true; error = null
                        scope.launch {
                            val success = try { saveRequest(context.applicationContext, target); true }
                                catch (_: Exception) { false }
                            saving = false
                            if (success) savedHost = target else error = R.string.manual_adaptation_save_error
                        }
                    }
                }) { Text(strings.getString(if (savedHost == null) R.string.manual_adaptation_confirm else R.string.manual_adaptation_app_info)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(strings.getString(R.string.common_close)) } }
    )
}

@Composable
internal fun ManualHostAdaptationContent(
    hosts: List<TikTokInstallStatus>?, selected: String?, savedHost: String?, saving: Boolean, error: Int?,
    onSelected: (String) -> Unit, onOpen: (String) -> Unit,
) {
    val strings = LocalContext.current.resources
    Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(strings.getString(R.string.manual_adaptation_hint), style = MaterialTheme.typography.bodySmall)
        if (hosts == null) Text(strings.getString(R.string.manual_adaptation_loading))
        else if (hosts.none { it.isInstalled }) Text(strings.getString(R.string.manual_adaptation_none))
        hosts.orEmpty().forEach { host ->
            val name = host.packageName ?: return@forEach
            val enabled = host.isInstalled && !saving && savedHost == null
            Row(Modifier.fillMaxWidth().testTag("manual-host:$name").selectable(
                selected = selected == name, enabled = enabled, role = Role.RadioButton, onClick = { onSelected(name) })
                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == name, onClick = null, enabled = enabled)
                Column(Modifier.weight(1f)) {
                    Text(strings.getString(if (name == ManualHostAdaptation.packages[0]) R.string.manual_adaptation_global else R.string.manual_adaptation_trill))
                    Text(if (host.isInstalled) strings.getString(R.string.manual_adaptation_host_info, name, host.versionName ?: strings.getString(R.string.common_unknown))
                        else strings.getString(R.string.manual_adaptation_not_installed, name), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(strings.getString(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (savedHost != null) {
            Text(strings.getString(R.string.manual_adaptation_saved, savedHost), Modifier.testTag("manual-adaptation-saved"))
            TextButton(onClick = { onOpen(savedHost) }) { Text(strings.getString(R.string.manual_adaptation_open)) }
        }
        Text(strings.getString(R.string.manual_adaptation_scope_note), style = MaterialTheme.typography.bodySmall)
        Text(strings.getString(R.string.manual_adaptation_registration_note), style = MaterialTheme.typography.bodySmall)
    }
}

internal fun saveManualHostAdaptation(context: Context, target: String) {
    require(target in ManualHostAdaptation.packages)
    require(context.packageName !in ManualHostAdaptation.packages || context.packageName == target)
    check(TikTokVersionHelper.getInstalledTikTokStatuses(context).any { it.isInstalled && it.packageName == target })
    ConfigClient.init(context)
    val changes = ManualHostAdaptation.values(ConfigClient.getString(ManualHostAdaptation.KEY, ""), target)
    ConfigClient.putValues(context, changes)
    check(ConfigClient.getString(ManualHostAdaptation.KEY, "") == changes.getValue(ManualHostAdaptation.KEY))
    ConfigClient.syncError?.let { throw it }
    check(ConfigClient.deliveryConfirmed(changes)) { "Adaptation request has not reached host-readable storage" }
}
