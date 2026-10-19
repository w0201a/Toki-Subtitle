package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import io.github.meiyongai.toki.subtitle.provider.HookDiagnostics
import io.github.meiyongai.toki.subtitle.provider.TrialLogCapture
import kotlinx.coroutines.*

/** 测试入口：模块日志始终可导出，logcat 采集按需启动。 */
@Composable
internal fun TrialLogSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("字幕版开发预览：TikTok 47.0.3，诊断与日志导出") }
    var busy by remember { mutableStateOf(false) }
    var includeLogcat by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val withLogcat = includeLogcat
            try {
                val diagnostics = withTimeoutOrNull(8000) { HookDiagnostics.request(context) }
                    ?: android.os.Bundle().apply { putString("error", "宿主诊断请求超时") }
                withContext(Dispatchers.IO) {
                    val output = checkNotNull(context.contentResolver.openOutputStream(uri))
                    output.use { TrialLogCapture.export(context, diagnostics, it, withLogcat) }
                }
                val hostReplied = HookDiagnostics.hosts.any { diagnostics.getBundle(it)?.getString("trialLog") != null }
                status = if (!hostReplied) "已导出，但未收到 TikTok 日志；请保留 TikTok 在后台运行后再导出"
                else if (withLogcat) "已导出宿主诊断包；logcat 采集结果见包内状态文件"
                else "已导出：TikTok 模块日志 + Hook 状态（无需 root）"
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status = "导出失败：${e.message}"
            } finally { busy = false }
        }
    }
    Text(status)
    TextButton(enabled = !busy, onClick = {
        scope.launch {
            busy = true
            try { status = withContext(Dispatchers.IO) { TrialLogCapture.start(context) } }
            catch (e: Exception) {
                if (e is CancellationException) throw e
                status = "采集失败：${e.message}"
            }
            finally { busy = false }
        }
    }) { Text("开始完整 logcat 记录（需 root）") }
    TextButton(enabled = !busy, onClick = {
        includeLogcat = true
        export.launch("Toki-subtitle-full-${System.currentTimeMillis()}.zip")
    }) { Text("停止并导出：logcat + 模块日志") }
    TextButton(enabled = !busy, onClick = {
        includeLogcat = false
        export.launch("Toki-subtitle-module-${System.currentTimeMillis()}.zip")
    }) {
        Text("仅导出模块日志（无需 root）")
    }
}
