package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import io.github.meiyongai.toki.subtitle.BuildConfig
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceSection
import io.github.meiyongai.toki.subtitle.update.AppRelease
import io.github.meiyongai.toki.subtitle.update.UpdateEngine
import io.github.meiyongai.toki.subtitle.update.UpdatePreferences
import io.github.meiyongai.toki.subtitle.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Always visible for this release, even if a rootless manager has delivered a companion Binder. */
@Composable
internal fun RootOnlyReleaseNotice() {
    val strings = LocalContext.current.resources
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag("root-only-release-notice")) {
        Column(Modifier.padding(16.dp)) {
            Text(strings.getString(R.string.release_root_only_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(strings.getString(R.string.release_root_only_message), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Only mounted by standalone HomeScreen, never by the injected settings or module process. */
@Composable
internal fun AppUpdateSection(onOpenUrl: (String) -> Unit, engine: UpdateEngine? = null) {
    val context = LocalContext.current
    val strings = context.resources
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val updater = remember(engine, context.applicationContext) { engine ?: UpdatePreferences.engine(context, BuildConfig.VERSION_NAME) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var manualChecking by remember { mutableStateOf(false) }
    var feedback by rememberSaveable { mutableStateOf<Int?>(null) }
    var noticeJson by rememberSaveable { mutableStateOf<String?>(null) }
    var currentNotes by rememberSaveable { mutableStateOf(false) }
    val notice = remember(noticeJson) { noticeJson?.let(AppRelease::fromCache) }

    suspend fun check(manual: Boolean) {
        if (checking) return
        checking = true
        manualChecking = manual
        if (manual) feedback = null
        try {
            when (val result = withContext(Dispatchers.IO) { updater.check(manual) }) {
                is UpdateResult.Available -> {
                    lifecycle.withResumed { Unit }
                    if (withContext(Dispatchers.IO) { updater.claimNotice(result.release, manual) }) {
                        noticeJson = result.release.encode()
                    }
                }
                UpdateResult.UpToDate -> if (manual) feedback = R.string.app_update_latest
                UpdateResult.Failed -> if (manual) feedback = R.string.app_update_failed
                UpdateResult.Busy, UpdateResult.NotDue -> Unit
            }
        } finally { checking = false; manualChecking = false }
    }

    LaunchedEffect(lifecycle, updater) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            check(manual = false)
            awaitCancellation()
        }
    }
    PreferenceSection(title = strings.getString(R.string.app_update_title)) {
        Text(strings.getString(R.string.app_update_policy), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
        TextButton(enabled = !checking, onClick = { scope.launch { check(manual = true) } },
            modifier = Modifier.testTag("manual-update-check")) {
            Text(strings.getString(if (manualChecking) R.string.app_update_checking else R.string.app_update_manual))
        }
        feedback?.let { Text(strings.getString(it), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 6.dp).testTag("manual-update-result")) }
        TextButton(onClick = { currentNotes = true }, modifier = Modifier.testTag("current-release-notes")) {
            Text(strings.getString(R.string.app_update_current_notes))
        }
    }
    if (notice != null) {
        ReleaseNotesDialog(strings.getString(R.string.app_update_available, notice.version.toString()),
            notice.notes.ifBlank { strings.getString(R.string.app_update_notes_empty) },
            onDismiss = { noticeJson = null },
            onOpen = { onOpenUrl(notice.pageUrl) })
    }
    if (currentNotes) {
        ReleaseNotesDialog("${BuildConfig.VERSION_NAME} · ${strings.getString(R.string.app_update_current_notes)}",
            strings.getString(R.string.app_update_current_body), onDismiss = { currentNotes = false })
    }
}

@Composable
private fun ReleaseNotesDialog(title: String, notes: String, onDismiss: () -> Unit, onOpen: (() -> Unit)? = null) {
    val strings = LocalContext.current.resources
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text(strings.getString(R.string.app_update_notes), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            // Plain text only: remote release notes never become executable HTML or arbitrary links.
            Text(notes, style = MaterialTheme.typography.bodyMedium)
        } },
        confirmButton = { TextButton(onClick = onOpen ?: onDismiss) {
            Text(strings.getString(if (onOpen == null) R.string.common_close else R.string.app_update_open))
        } },
        dismissButton = { if (onOpen != null) TextButton(onClick = onDismiss) { Text(strings.getString(R.string.common_close)) } })
}
