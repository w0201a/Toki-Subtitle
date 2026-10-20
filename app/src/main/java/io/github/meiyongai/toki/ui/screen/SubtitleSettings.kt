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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.model.SubtitleFonts
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceSection

@Composable
fun SubtitleSettings() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ConfigClient.getBoolean(context, "subtitle_style_enabled")) }
    var open by remember { mutableStateOf(false) }
    PreferenceSection(title = "字幕外观与位置") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { open = true }) { Text("打开字幕设置") }
            Switch(checked = enabled, onCheckedChange = {
                enabled = it; ConfigClient.putBoolean(context, "subtitle_style_enabled", it)
            })
        }
        Text("开启字幕外观及作品翻译后，跟随 TikTok 的“始终翻译作品”或单条视频译文选择。优先使用中文字幕轨；能读取完整字幕时异步预读并提前翻译后续句子，读不到时逐句翻译。等待或失败时保留原文。无需 API 密钥，字幕文字由 TikTok 服务处理。首次开启后重启 TikTok。", style = MaterialTheme.typography.bodySmall)
        Text("TikTok 播放页：拖动字幕文字可调整位置；长按字幕不移动打开设置，也可从长按视频菜单进入“字幕设置”。", style = MaterialTheme.typography.bodySmall)
    }
    if (open) SubtitleDialog { open = false }
}

@Composable
internal fun SubtitleDialog(close: () -> Unit) {
    val context = LocalContext.current
    val defaults = mapOf("subtitle_size" to "20", "subtitle_x" to "0", "subtitle_y" to "0",
        "subtitle_font" to "sans-serif", "subtitle_weight" to "normal",
        "subtitle_color" to "#FFFFFFFF", "subtitle_background" to "#80000000", "subtitle_background_mode" to "show")
    var values by remember { mutableStateOf(defaults.mapValues { (k, v) -> ConfigClient.getString(context, k, v)!! }) }
    var gestures by remember { mutableStateOf(listOf("subtitle_drag_enabled", "subtitle_lock_x", "subtitle_lock_y")
        .associateWith { ConfigClient.getBoolean(context, it) }) }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(key: String, value: String) { values = values + (key to value) }
    val weight = values.getValue("subtitle_weight")
    val previewFont = remember(values["subtitle_font"]) {
        FontFamily(android.graphics.Typeface.create(values["subtitle_font"], android.graphics.Typeface.NORMAL))
    }
    AlertDialog(onDismissRequest = close, title = { Text("字幕设置") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
            Surface(color = if (values["subtitle_background_mode"] == "hide") Color.Transparent else Color(android.graphics.Color.parseColor(values.getValue("subtitle_background"))),
                modifier = Modifier.fillMaxWidth()) {
                Text("这是字幕预览\nSubtitle preview", modifier = Modifier.padding(12.dp),
                    fontSize = values.getValue("subtitle_size").toFloat().sp,
                    color = Color(android.graphics.Color.parseColor(values.getValue("subtitle_color"))),
                    fontFamily = previewFont,
                    fontWeight = if (weight.contains("bold")) FontWeight.Bold else FontWeight.Normal,
                    fontStyle = if (weight.contains("italic")) FontStyle.Italic else FontStyle.Normal)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            for ((key, label, range) in listOf(Triple("subtitle_size", "字号（sp）", 6f..160f),
                Triple("subtitle_x", "水平偏移 %", -200f..200f), Triple("subtitle_y", "垂直偏移 %", -200f..200f))) {
                Text("$label：${values.getValue(key).toFloat().toInt()}")
                Slider(value = values.getValue(key).toFloat().coerceIn(range.start, range.endInclusive), valueRange = range,
                    onValueChange = { update(key, it.toInt().toString()) })
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
                        onCheckedChange = { gestures = gestures + (key to it) })
                }
            }
            Text("方向锁定只影响播放页拖动；仍可在这里手动调整位置。关闭拖动后，长按字幕仍可打开设置。",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("显示字幕背景")
                Switch(checked = values["subtitle_background_mode"] != "hide", onCheckedChange = {
                    update("subtitle_background_mode", if (it) "show" else "hide")
                })
            }
            SubtitleChoices("字体", SubtitleFonts.choices, values.getValue("subtitle_font")) { update("subtitle_font", it) }
            Text("使用手机系统字体，无需下载。部分字体主要改变拉丁字母；中文、日文、韩文或缺失字体会使用系统回退字体，实际效果以手机为准。", style = MaterialTheme.typography.bodySmall)
            SubtitleChoices("样式", listOf("normal" to "常规", "bold" to "粗体", "italic" to "斜体", "bold_italic" to "粗斜体"), weight) { update("subtitle_weight", it) }
            SubtitleChoices("文字颜色", listOf("#FFFFFFFF" to "白", "#FFFFFF00" to "黄", "#FF000000" to "黑", "#FF00FFFF" to "青"), values.getValue("subtitle_color")) { update("subtitle_color", it) }
            SubtitleChoices("背景", listOf("#00000000" to "透明", "#80000000" to "半透明黑", "#FF000000" to "黑", "#CCFFFFFF" to "白"), values.getValue("subtitle_background")) { update("subtitle_background", it) }
            Text("自定义颜色：#AARRGGBB，AA 为透明度")
            for (key in listOf("subtitle_color", "subtitle_background")) {
                var input by remember(key, values.getValue(key)) { mutableStateOf(values.getValue(key)) }
                OutlinedTextField(value = input, onValueChange = {
                    input = it
                    if (it.matches(Regex("#[0-9A-Fa-f]{8}"))) update(key, it)
                }, label = { Text(if (key == "subtitle_color") "文字颜色" else "背景颜色") }, singleLine = true,
                    isError = !input.matches(Regex("#[0-9A-Fa-f]{8}")))
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        try { ConfigClient.putValues(context, values + gestures); close() }
        catch (e: Exception) { error = e.message ?: "保存失败" }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = close) { Text("取消") } })
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
