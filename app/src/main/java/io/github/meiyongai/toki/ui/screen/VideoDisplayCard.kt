package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.model.VideoFitMode
import io.github.meiyongai.toki.subtitle.ui.component.CompactPreferenceSwitch
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.hook.ImmersiveFullScreenHook
import kotlin.math.roundToInt

/**
 * 提供视频居中、全屏沉浸和填充模式，关闭总开关使用原生布局。
 * @param shape 设置分组圆角。
 * @param enabled 是否扩展视频区域。
 * @param mode VideoFitMode 的稳定存储键。
 * @param onEnabledChange 保存总开关。
 * @param onModeChange 保存显示方式。
 * @return Unit。
 * Callers: DashboardScreen。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoDisplayCard(shape: Shape, enabled: Boolean, mode: String,
    onEnabledChange: (Boolean) -> Unit, onModeChange: (String) -> Unit) {
    val context = LocalContext.current
    val strings = context.resources
    var zoom by remember { mutableFloatStateOf(ConfigClient.getString(context,
        ImmersiveFullScreenHook.KEY_VIDEO_FILL_ZOOM, "100")?.toFloatOrNull()?.coerceIn(100f, 150f) ?: 100f) }
    var autoCrop by remember { mutableStateOf(ConfigClient.getBoolean(context, ImmersiveFullScreenHook.KEY_VIDEO_AUTO_CROP)) }
    var selectedMode by remember { mutableStateOf(VideoFitMode.from(mode)) }
    var editingZoom by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(mode) { selectedMode = VideoFitMode.from(mode) }
    DisposableEffect(context) {
        val subscription = ConfigClient.addStateListener {
            selectedMode = VideoFitMode.from(ConfigClient.getString(context, ImmersiveFullScreenHook.KEY_VIDEO_FIT_MODE, "center"))
            autoCrop = ConfigClient.getBoolean(context, ImmersiveFullScreenHook.KEY_VIDEO_AUTO_CROP)
            if (!editingZoom) zoom = ConfigClient.getString(context, ImmersiveFullScreenHook.KEY_VIDEO_FILL_ZOOM, "100")
                ?.toFloatOrNull()?.coerceIn(100f, 150f) ?: 100f
        }
        onDispose { subscription.close() }
    }
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            CompactPreferenceSwitch(title = strings.getString(R.string.feature_immersive),
                checked = enabled,
                onCheckedChange = onEnabledChange)
            AnimatedVisibility(enabled) {
                Column {
                FlowRow(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (value in VideoFitMode.entries) {
                        val label = when (value) {
                            VideoFitMode.CENTER -> R.string.video_fit_center
                            VideoFitMode.SMART -> R.string.video_fit_smart
                            VideoFitMode.FILL -> R.string.video_fit_fill
                            VideoFitMode.CONTAIN -> R.string.video_fit_contain
                            VideoFitMode.WIDTH -> R.string.video_fit_width
                            VideoFitMode.HEIGHT -> R.string.video_fit_height
                        }
                        FilterChip(selected = selectedMode == value, onClick = {
                            try { onModeChange(value.key); selectedMode = value; failed = false }
                            catch (_: Exception) { failed = true }
                        }, label = { Text(strings.getString(label)) })
                    }
                }
                Text(strings.getString(R.string.video_display_runtime_hint), style = MaterialTheme.typography.bodySmall)
                Text(strings.getString(when (selectedMode) {
                    VideoFitMode.CENTER -> R.string.video_fit_center_hint
                    VideoFitMode.SMART -> R.string.video_fit_smart_hint
                    VideoFitMode.FILL -> R.string.video_fit_fill_hint
                    VideoFitMode.CONTAIN -> R.string.video_fit_contain_hint
                    VideoFitMode.WIDTH -> R.string.video_fit_width_hint
                    VideoFitMode.HEIGHT -> R.string.video_fit_height_hint
                }), style = MaterialTheme.typography.bodySmall)
                if (selectedMode == VideoFitMode.FILL) {
                    CompactPreferenceSwitch(title = strings.getString(R.string.video_auto_crop),
                        summary = strings.getString(R.string.video_auto_crop_hint), checked = autoCrop,
                        onCheckedChange = {
                            try {
                                ConfigClient.putBoolean(context, ImmersiveFullScreenHook.KEY_VIDEO_AUTO_CROP, it)
                                autoCrop = it; failed = false
                            } catch (_: Exception) { failed = true }
                        })
                    Text(strings.getString(R.string.video_fill_zoom, zoom.roundToInt()),
                        style = MaterialTheme.typography.bodyMedium)
                    Slider(value = zoom, onValueChange = { editingZoom = true; zoom = it }, valueRange = 100f..150f, steps = 49,
                        onValueChangeFinished = {
                            editingZoom = false
                            try { ConfigClient.putString(context, ImmersiveFullScreenHook.KEY_VIDEO_FILL_ZOOM, zoom.roundToInt().toString()); failed = false }
                            catch (_: Exception) { failed = true }
                        })
                    Text(strings.getString(R.string.video_fill_zoom_hint), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        try {
                            ConfigClient.putString(context, ImmersiveFullScreenHook.KEY_VIDEO_FILL_ZOOM, "100")
                            zoom = 100f; editingZoom = false; failed = false
                        } catch (_: Exception) { failed = true }
                    }) { Text(strings.getString(R.string.video_fill_zoom_reset)) }
                }
                if (failed) Text(strings.getString(R.string.video_display_save_failed), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
