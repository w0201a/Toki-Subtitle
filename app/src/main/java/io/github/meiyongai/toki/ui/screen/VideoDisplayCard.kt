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
import io.github.meiyongai.toki.subtitle.ui.component.CompactPreferenceSwitch
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.hook.ImmersiveFullScreenHook
import kotlin.math.roundToInt

/**
 * 提供视频居中、全屏沉浸和填充模式，关闭总开关使用原生布局。
 * @param shape 设置分组圆角。
 * @param enabled 是否扩展视频区域。
 * @param mode center、smart 或 fill。
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
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            CompactPreferenceSwitch(title = strings.getString(R.string.feature_immersive),
                checked = enabled,
                onCheckedChange = onEnabledChange)
            AnimatedVisibility(enabled) {
                Column {
                FlowRow(modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in listOf("center" to R.string.video_fit_center, "smart" to R.string.video_fit_smart,
                        "fill" to R.string.video_fit_fill)) {
                        FilterChip(selected = mode == value, onClick = { onModeChange(value) },
                            label = { Text(strings.getString(label)) })
                    }
                }
                if (mode == "fill") {
                    CompactPreferenceSwitch(title = strings.getString(R.string.video_auto_crop),
                        summary = strings.getString(R.string.video_auto_crop_hint), checked = autoCrop,
                        onCheckedChange = {
                            autoCrop = it
                            ConfigClient.putBoolean(context, ImmersiveFullScreenHook.KEY_VIDEO_AUTO_CROP, it)
                        })
                    Text(strings.getString(R.string.video_fill_zoom, zoom.roundToInt()),
                        style = MaterialTheme.typography.bodyMedium)
                    Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 100f..150f, steps = 49,
                        onValueChangeFinished = { ConfigClient.putString(context,
                            ImmersiveFullScreenHook.KEY_VIDEO_FILL_ZOOM, zoom.roundToInt().toString()) })
                    Text(strings.getString(R.string.video_fill_zoom_hint), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        zoom = 100f
                        ConfigClient.putString(context, ImmersiveFullScreenHook.KEY_VIDEO_FILL_ZOOM, "100")
                    }) { Text(strings.getString(R.string.video_fill_zoom_reset)) }
                }
                }
            }
        }
    }
}
