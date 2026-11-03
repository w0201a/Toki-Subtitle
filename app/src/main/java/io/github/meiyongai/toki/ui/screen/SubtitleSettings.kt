package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.meiyongai.toki.subtitle.model.SubtitleAppearance
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import io.github.meiyongai.toki.subtitle.ui.StyledSubtitleTextView
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.model.SubtitleFonts
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceSection

@Composable
fun SubtitleSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ConfigClient.getBoolean(context, "subtitle_style_enabled")) }
    var open by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val subscription = ConfigClient.addStateListener {
            enabled = ConfigClient.getBoolean(context, "subtitle_style_enabled")
        }
        onDispose { subscription.close() }
    }
    PreferenceSection(title = "字幕外观与位置") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { open = true }) { Text("打开字幕设置") }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it; ConfigClient.putBoolean(context, "subtitle_style_enabled", it)
            })
        }
        Text("开启字幕外观及作品翻译后，跟随 TikTok 的“始终翻译作品”或单条视频译文选择。优先使用中文字幕轨；能读取完整字幕时异步预读并提前翻译后续句子，读不到时逐句翻译。等待或失败时保留原文。无需 API 密钥，字幕文字由 TikTok 服务处理。首次开启会按需查找并注册运行时依赖；已经创建的页面可能需切换一次视频后生效。", style = MaterialTheme.typography.bodySmall)
        Text("TikTok 播放页：拖动字幕文字可调整位置；长按字幕不移动打开设置，也可从长按视频菜单进入“字幕设置”。", style = MaterialTheme.typography.bodySmall)
    }
    if (open) SubtitleDialog { open = false }
}

@Composable
internal fun SubtitleDialog(close: () -> Unit) {
    val context = LocalContext.current
    val defaults = mapOf("subtitle_size" to "20", "subtitle_x" to "0", "subtitle_y" to "0",
        "subtitle_font" to "sans-serif", "subtitle_weight" to "normal",
        "subtitle_color" to "#FFFFFFFF", "subtitle_background" to "#80000000", "subtitle_background_mode" to "show") + SubtitleAppearance.defaults
    var values by remember { mutableStateOf(defaults.mapValues { (k, v) -> ConfigClient.getString(context, k, v)!! }) }
    var gestures by remember { mutableStateOf(listOf("subtitle_drag_enabled", "subtitle_lock_x", "subtitle_lock_y")
        .associateWith { ConfigClient.getBoolean(context, it) }) }
    var renderMode by remember { mutableStateOf(ConfigClient.getString(context, "subtitle_render_mode", "native")!!) }
    var liveDebug by remember { mutableStateOf(ConfigClient.getBoolean(context, "subtitle_debug_overlay_enabled")) }
    var cacheCleared by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun persist(key: String, value: Any) {
        try { ConfigClient.putValues(context, mapOf(key to value)) }
        catch (e: Exception) { error = e.message ?: "实时同步失败" }
    }
    fun update(key: String, value: String, save: Boolean = true) {
        values = values + (key to value)
        if (save) persist(key, value)
    }
    val weight = values.getValue("subtitle_weight")
    DisposableEffect(context) {
        val subscription = ConfigClient.addStateListener {
            values = defaults.mapValues { (key, fallback) -> ConfigClient.getString(context, key, fallback)!! }
            gestures = gestures.keys.associateWith { ConfigClient.getBoolean(context, it) }
            renderMode = ConfigClient.getString(context, "subtitle_render_mode", "native")!!
            liveDebug = ConfigClient.getBoolean(context, "subtitle_debug_overlay_enabled")
        }
        onDispose { subscription.close() }
    }
    AlertDialog(onDismissRequest = close, title = { Text("字幕设置") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            AndroidView(factory = { StyledSubtitleTextView(it).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            } }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 160.dp), update = { preview ->
                preview.text = "这是字幕预览\nSubtitle preview"
                preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, values.getValue("subtitle_size").toFloat())
                val style = when (weight) {
                    "bold" -> android.graphics.Typeface.BOLD
                    "italic" -> android.graphics.Typeface.ITALIC
                    "bold_italic" -> android.graphics.Typeface.BOLD_ITALIC
                    else -> android.graphics.Typeface.NORMAL
                }
                preview.typeface = android.graphics.Typeface.create(values.getValue("subtitle_font"), style)
                preview.setTextColor(android.graphics.Color.parseColor(values.getValue("subtitle_color")))
                val background = if (values["subtitle_background_mode"] == "hide") android.graphics.Color.TRANSPARENT
                    else android.graphics.Color.parseColor(values.getValue("subtitle_background"))
                preview.applyAppearance(SubtitleAppearance.from(ConfigSnapshot(values)), background)
            })
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            for ((key, label, range) in listOf(Triple("subtitle_size", "字号（sp）", 6f..160f),
                Triple("subtitle_x", "水平偏移 %", -200f..200f), Triple("subtitle_y", "垂直偏移 %", -200f..200f))) {
                Text("$label：${values.getValue(key).toFloat().toInt()}")
                Slider(value = values.getValue(key).toFloat().coerceIn(range.start, range.endInclusive), valueRange = range,
                    onValueChange = { update(key, it.toInt().toString(), save = false) },
                    onValueChangeFinished = { persist(key, values.getValue(key)) })
                var custom by remember(key, values.getValue(key)) { mutableStateOf(values.getValue(key)) }
                val allowed = if (key == "subtitle_size") 6f..160f else -1000f..1000f
                OutlinedTextField(value = custom, onValueChange = {
                    custom = it
                    it.toFloatOrNull()?.takeIf { n -> n.isFinite() && n in allowed }?.let { update(key, custom) }
                }, label = { Text(if (key == "subtitle_size") "自定义：6–160 sp" else "自定义：-1000–1000 %") },
                    keyboardOptions = KeyboardOptions(keyboardType = if (key == "subtitle_size") KeyboardType.Decimal else KeyboardType.Ascii), singleLine = true,
                    isError = custom.toFloatOrNull()?.let { it.isFinite() && it in allowed } != true)
            }
            Text("可拖动滑条或手动输入；位置仍限制在页面内，避免字幕移出屏幕。")
            for ((key, title) in listOf("subtitle_drag_enabled" to "允许拖动字幕",
                "subtitle_lock_x" to "锁定 X 方向（水平）", "subtitle_lock_y" to "锁定 Y 方向（垂直）")) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(title, modifier = Modifier.weight(1f))
                    Switch(checked = gestures.getValue(key),
                        enabled = key == "subtitle_drag_enabled" || gestures.getValue("subtitle_drag_enabled"),
                        onCheckedChange = {
                            gestures = gestures + (key to it)
                            persist(key, it)
                        })
                }
            }
            Text("方向锁定只影响播放页拖动；仍可在这里手动调整位置。关闭拖动后，长按字幕仍可打开设置。",
                style = MaterialTheme.typography.bodySmall)
            Text("显示方式")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = renderMode == "native", onClick = {
                    renderMode = "native"; persist("subtitle_render_mode", renderMode)
                }, label = { Text("跟随 TikTok 原生样式") })
                FilterChip(selected = renderMode == "custom", onClick = {
                    renderMode = "custom"; persist("subtitle_render_mode", renderMode)
                }, label = { Text("Toki 自定义样式") })
            }
            Text("原生模式保留 TikTok 字幕渲染和系统样式；Toki 只在需要自定义样式、拖动或清屏补帧时绘制字幕层。",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("实时调试浮层")
                    Text("仅显示当前视频的字幕状态、轨道摘要和缓存计数；默认关闭，不采集字幕正文。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = liveDebug, onCheckedChange = {
                    liveDebug = it
                    persist("subtitle_debug_overlay_enabled", it)
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("字幕翻译缓存：内存与宿主私有存档")
                TextButton(onClick = {
                    ConfigClient.putString(context, "subtitle_cache_clear_revision", java.util.UUID.randomUUID().toString())
                    cacheCleared = true
                }) { Text(if (cacheCleared) "已请求清理" else "清空缓存") }
            }
            Text("缓存按视频及字幕轨隔离；已保存译文可在重开后复用。本操作清理译文缓存，不清理已保存的原字幕文件。",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("显示字幕背景")
                Switch(checked = values["subtitle_background_mode"] != "hide", onCheckedChange = {
                    update("subtitle_background_mode", if (it) "show" else "hide")
                })
            }
            SubtitleChoices("字幕内容", listOf("original" to "只看原文", "translated" to "只看译文", "bilingual" to "原文与译文"), values.getValue("subtitle_text_mode")) { update("subtitle_text_mode", it) }
            Text("没有已缓存译文且服务不可用时仍显示原文；双语仅显示当前字幕句的原文和译文。", style = MaterialTheme.typography.bodySmall)
            SubtitleChoices("对齐", listOf("start" to "靠起始侧", "center" to "居中", "end" to "靠结束侧"), values.getValue("subtitle_alignment")) { update("subtitle_alignment", it) }
            for ((key, label) in listOf(
                "subtitle_outline_width" to "描边宽度 dp", "subtitle_shadow_radius" to "阴影半径 dp",
                "subtitle_shadow_dx" to "阴影水平偏移 dp", "subtitle_shadow_dy" to "阴影垂直偏移 dp",
                "subtitle_line_spacing" to "行高倍数", "subtitle_letter_spacing" to "字间距 em",
                "subtitle_background_radius" to "背景圆角 dp", "subtitle_padding_horizontal" to "水平内边距 dp",
                "subtitle_padding_vertical" to "垂直内边距 dp")) {
                val range = SubtitleAppearance.ranges.getValue(key)
                Text(label + "：" + values.getValue(key))
                Slider(value = values.getValue(key).toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
                    valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                    onValueChange = { update(key, String.format(java.util.Locale.ROOT, "%.2f", it), save = false) },
                    onValueChangeFinished = { persist(key, values.getValue(key)) })
                var draft by remember(key, values.getValue(key)) { mutableStateOf(values.getValue(key)) }
                OutlinedTextField(value = draft, onValueChange = {
                    draft = it
                    it.toDoubleOrNull()?.takeIf { value -> value.isFinite() && value in range }?.let { update(key, draft) }
                }, label = { Text(label + "（" + range.start + "–" + range.endInclusive + "）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    isError = draft.toDoubleOrNull()?.let { it.isFinite() && it in range } != true)
            }
            Text("最多显示行数（0 = 不限制）：" + values.getValue("subtitle_max_lines"))
            Slider(value = values.getValue("subtitle_max_lines").toFloat(), valueRange = 0f..12f, steps = 11,
                onValueChange = { update("subtitle_max_lines", kotlin.math.round(it).toInt().toString(), save = false) },
                onValueChangeFinished = { persist("subtitle_max_lines", values.getValue("subtitle_max_lines")) })
            SubtitleChoices("字体", SubtitleFonts.choices, values.getValue("subtitle_font")) { update("subtitle_font", it) }
            Text("使用手机系统字体，无需下载。部分字体主要改变拉丁字母；中文、日文、韩文或缺失字体会使用系统回退字体，实际效果以手机为准。", style = MaterialTheme.typography.bodySmall)
            SubtitleChoices("样式", listOf("normal" to "常规", "bold" to "粗体", "italic" to "斜体", "bold_italic" to "粗斜体"), weight) { update("subtitle_weight", it) }
            SubtitleChoices("文字颜色", listOf("#FFFFFFFF" to "白", "#FFFFFF00" to "黄", "#FF000000" to "黑", "#FF00FFFF" to "青"), values.getValue("subtitle_color")) { update("subtitle_color", it) }
            SubtitleChoices("背景", listOf("#00000000" to "透明", "#80000000" to "半透明黑", "#FF000000" to "黑", "#CCFFFFFF" to "白"), values.getValue("subtitle_background")) { update("subtitle_background", it) }
            Text("自定义颜色：#AARRGGBB，AA 为透明度")
            for (key in listOf("subtitle_color", "subtitle_background", "subtitle_outline_color", "subtitle_shadow_color")) {
                var input by remember(key, values.getValue(key)) { mutableStateOf(values.getValue(key)) }
                OutlinedTextField(value = input, onValueChange = {
                    input = it
                    if (it.matches(Regex("#[0-9A-Fa-f]{8}"))) update(key, it)
                }, label = { Text(when (key) { "subtitle_color" -> "文字颜色"; "subtitle_background" -> "背景颜色"; "subtitle_outline_color" -> "描边颜色"; else -> "阴影颜色" }) }, singleLine = true,
                    isError = !input.matches(Regex("#[0-9A-Fa-f]{8}")))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            ConfigClient.putValues(context, values + gestures + mapOf(
                "subtitle_render_mode" to renderMode,
                "subtitle_debug_overlay_enabled" to liveDebug))
            close()
        }
        catch (e: Exception) { error = e.message ?: "保存失败" }
    }) { Text("完成") } }, dismissButton = { TextButton(onClick = close) { Text("关闭") } })
}

@Composable
private fun SubtitleChoices(label: String, choices: List<Pair<String, String>>, selected: String, select: (String) -> Unit) {
    Text(label)
    Column {
        choices.chunked(2).forEach { row -> Row {
            row.forEach { (value, text) -> FilterChip(selected = selected == value, onClick = { select(value) },
                label = { Text(text) }, modifier = Modifier.padding(end = 6.dp)) }
        } }
    }
}
