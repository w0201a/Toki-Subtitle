package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.BuildConfig
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceItem
import io.github.meiyongai.toki.subtitle.ui.component.getGroupedShape

/** UI navigation only: no setting values or feature defaults live here. */
enum class SettingsCategory(val title: Int, val hint: Int) {
    SUBTITLES(R.string.toki_category_subtitles, R.string.toki_category_subtitles_hint),
    COMMENTS(R.string.toki_category_comments, R.string.toki_category_comments_hint),
    CLEANUP(R.string.toki_category_cleanup, R.string.toki_category_cleanup_hint),
    PLAYBACK(R.string.toki_category_playback, R.string.toki_category_playback_hint),
    DOWNLOADS(R.string.toki_category_downloads, R.string.toki_category_downloads_hint),
    REGION(R.string.toki_category_region, R.string.toki_category_region_hint),
    TOOLS(R.string.toki_category_tools, R.string.toki_category_tools_hint);
}

private fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.SUBTITLES -> Icons.Outlined.Translate
    SettingsCategory.COMMENTS -> Icons.Outlined.Forum
    SettingsCategory.CLEANUP -> Icons.Outlined.AutoAwesome
    SettingsCategory.PLAYBACK -> Icons.Outlined.PlayArrow
    SettingsCategory.DOWNLOADS -> Icons.Outlined.Download
    SettingsCategory.REGION -> Icons.Outlined.Public
    SettingsCategory.TOOLS -> Icons.Outlined.Tune
}

@Composable
fun TokiSettingsHub(scrollState: ScrollState = rememberScrollState(), onDiagnostics: (() -> Unit)? = null) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val category = selected?.let { name -> SettingsCategory.entries.firstOrNull { it.name == name } }
    BackHandler(enabled = category != null) { selected = null }
    if (category == null) {
        Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primary) {
                    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        Text("t", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Column { Text(stringResource(R.string.toki_settings_title), style = MaterialTheme.typography.headlineSmall) }
            }
            Text(stringResource(R.string.toki_settings_intro), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 22.dp))
            Text(stringResource(R.string.toki_ui_section_common), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
            SettingsCategory.entries.forEachIndexed { index, item ->
                PreferenceItem(title = stringResource(item.title), summary = stringResource(item.hint),
                    shape = getGroupedShape(index, SettingsCategory.entries.size), icon = item.icon(),
                    iconTint = when(item) {
                        SettingsCategory.CLEANUP -> MaterialTheme.colorScheme.secondary
                        SettingsCategory.PLAYBACK, SettingsCategory.TOOLS -> Color(0xFF967CC0)
                        SettingsCategory.DOWNLOADS -> Color(0xFF5491D1)
                        SettingsCategory.REGION -> Color(0xFFC4943E)
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.testTag("settings-category-" + item.name),
                    onClick = { selected = item.name })
                if (index != SettingsCategory.entries.lastIndex) Spacer(Modifier.height(1.dp))
            }
            Text("Toki Subtitle · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 20.dp, bottom = 24.dp))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selected = null }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.nav_back))
                }
                Text(stringResource(category.title), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            }
            if (category == SettingsCategory.TOOLS && onDiagnostics != null) {
                TextButton(onClick = onDiagnostics, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.toki_tools_open))
                }
            }
            key(category) {
                Box(Modifier.weight(1f)) { DashboardScreen(category = category) }
            }
        }
    }
}
