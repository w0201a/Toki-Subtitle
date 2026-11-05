package io.github.meiyongai.toki.subtitle.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.provider.HookDiagnostics
import io.github.meiyongai.toki.subtitle.provider.TrialLogCapture
import io.github.meiyongai.toki.subtitle.provider.TrialLogStorage
import io.github.meiyongai.toki.subtitle.ui.component.PreferenceSection
import io.github.meiyongai.toki.subtitle.ui.component.CompactPreferenceActions
import kotlinx.coroutines.*

/** Storage is visible; capture is bounded and manual clearing always shows a concrete confirmation first. */
@Composable
internal fun TrialLogSection() {
    val context=LocalContext.current; val scope=rememberCoroutineScope()
    var status by remember { mutableStateOf("诊断日志可按需导出，不会自动开始完整logcat采集。") }
    var busy by remember { mutableStateOf(false) }
    var includeLogcat by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(TrialLogCapture.isRecording()) }
    var captureState by remember { mutableStateOf(TrialLogCapture.captureState()) }
    var usage by remember { mutableStateOf<TrialLogStorage.Usage?>(null) }
    var clearPlan by remember { mutableStateOf<TrialLogStorage.ClearPlan?>(null) }
    suspend fun refresh() {
        usage=withContext(Dispatchers.IO){TrialLogStorage.usage(context)}
        recording=TrialLogCapture.isRecording();captureState=TrialLogCapture.captureState()
    }
    LaunchedEffect(Unit) { try{refresh()}catch(_:Exception){status="日志占用读取失败；没有修改或清除文件"} }
    LaunchedEffect(recording) {
        if(recording)while(isActive){try{refresh()}catch(_:Exception){};if(!recording)break;delay(2000)}
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")){uri ->
        if(uri!=null)scope.launch {
            busy=true;val withLogcat=includeLogcat
            try {
                val diagnostics=withTimeoutOrNull(8000){HookDiagnostics.request(context)}
                    ?:android.os.Bundle().apply{putString("error","宿主诊断请求超时")}
                withContext(Dispatchers.IO){
                    checkNotNull(context.contentResolver.openOutputStream(uri)).use{TrialLogCapture.export(context,diagnostics,it,withLogcat)}
                }
                val replied=HookDiagnostics.hosts.any{diagnostics.getBundle(it)?.getString("trialLog")!=null}
                status=if(!replied)"已导出，但未收到TikTok日志；复现后保留TikTok后台再导出"
                    else if(withLogcat)"已导出限额内logcat与模块日志；停止原因见包内状态文件"
                    else "已导出TikTok模块日志与Hook状态（无需root）"
                refresh()
            }catch(error:Exception){if(error is CancellationException)throw error;status="导出失败：${error.message}"}
            finally{busy=false}
        }
    }
    fun requestExport(withLogcat:Boolean) {
        scope.launch {
            busy=true
            try {
                // Stop before opening SAF: cancelling a destination must not leave recording running.
                withContext(Dispatchers.IO){TrialLogCapture.stop()};refresh()
                includeLogcat=withLogcat
                export.launch("Toki-${if(withLogcat)"full" else "module"}-${System.currentTimeMillis()}.zip")
            }catch(error:Exception){if(error is CancellationException)throw error;status="停止采集失败：${error.message}"}
            finally{busy=false}
        }
    }
    PreferenceSection(title="日志与诊断") {
        Surface(shape=RoundedCornerShape(18.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                val used=usage
                Text(if(used==null)"正在读取日志占用…" else "内部日志：${android.text.format.Formatter.formatShortFileSize(context,used.bytes)} · ${used.files}个文件",
                    style=MaterialTheme.typography.titleSmall)
                Text("采集上限：每文件8 MiB、最长10分钟；内部总占用64 MiB。到限停止，不自动删除旧日志。",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(used!=null && used.bytes>=TrialLogStorage.MAX_TOTAL_BYTES)Text("已有日志超过上限，暂不接受新采集；可先导出，再手动确认清理。",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(enabled=!busy,onClick={scope.launch{try{refresh()}catch(_:Exception){status="日志占用读取失败"}}}){Text("刷新占用")}
                    TextButton(enabled=!busy && (used?.files?:0)>0,onClick={scope.launch{
                        busy=true
                        try{clearPlan=withContext(Dispatchers.IO){TrialLogStorage.plan(context)}}
                        catch(error:Exception){if(error is CancellationException)throw error;status="无法生成清理清单：${error.message}"}
                        finally{busy=false}
                    }}){Text("清理已存日志",color=MaterialTheme.colorScheme.error)}
                }
                Text(captureState,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                CompactPreferenceActions("开始限额logcat（需root）",{
                    if(!busy && !recording)scope.launch {
                        busy=true
                        try{status=withContext(Dispatchers.IO){TrialLogCapture.start(context)};refresh()}
                        catch(error:Exception){if(error is CancellationException)throw error;status="采集失败：${error.message}"}
                        finally{busy=false}
                    }
                },"停止采集",{
                    if(!busy)scope.launch{busy=true;try{withContext(Dispatchers.IO){TrialLogCapture.stop()};refresh()}
                    finally{busy=false}}
                })
            }
        }
        Text(status,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,
            modifier=Modifier.padding(horizontal=6.dp,vertical=8.dp))
        Text("完整logcat可能包含其他应用信息，分享前请检查。新导出不在内部目录重复保存快照。",
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(horizontal=6.dp))
        TextButton(enabled=!busy,onClick={requestExport(false)}){Text("导出模块日志（无需root）")}
        TextButton(enabled=!busy,onClick={requestExport(true)}){Text("停止并导出：限额logcat＋模块日志")}
    }
    clearPlan?.let { plan ->
        AlertDialog(onDismissRequest={clearPlan=null},title={Text("清除这${plan.files.size}个诊断文件？")},
            text={Text("合计${android.text.format.Formatter.formatShortFileSize(context,plan.bytes)}。只清除Toki内部trial-logs目录里本次列出的日志；记录中的采集会先停止。不会清除设置、离线视频、评论字幕或你另存的ZIP。此操作无法撤销。")},
            dismissButton={TextButton(onClick={clearPlan=null}){Text("取消")}},
            confirmButton={TextButton(onClick={
                clearPlan=null
                scope.launch{busy=true
                    try{
                        val removed=withContext(Dispatchers.IO){TrialLogCapture.stop();TrialLogStorage.clearConfirmed(context,plan)}
                        status="已清除${removed}个已确认的诊断文件";refresh()
                    }catch(error:Exception){if(error is CancellationException)throw error;status="清理未完成：${error.message}";try{refresh()}catch(_:Exception){}}
                    finally{busy=false}
                }
            }){Text("确认清除日志",color=MaterialTheme.colorScheme.error)}})
    }
}
