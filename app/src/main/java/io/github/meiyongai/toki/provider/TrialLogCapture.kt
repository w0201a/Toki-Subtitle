package io.github.meiyongai.toki.subtitle.provider

import android.content.Context
import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/** User-started root capture, bounded at the pipe. Exports stream metadata directly into the selected ZIP. */
internal object TrialLogCapture {
    private var process: BoundedLogProcess? = null
    private var directory: File? = null
    @Synchronized fun isRecording(): Boolean = process?.isRunning == true
    @Synchronized fun captureState(): String = when(process?.reason) {
        BoundedLogProcess.Reason.RUNNING -> "正在记录（最多8 MiB或10分钟）"
        BoundedLogProcess.Reason.BYTES -> "已自动停止：达到8 MiB大小上限"
        BoundedLogProcess.Reason.TIME -> "已自动停止：达到10分钟时长上限"
        BoundedLogProcess.Reason.USER -> "已停止记录"
        BoundedLogProcess.Reason.ERROR -> "采集结束：读取或写入错误"
        BoundedLogProcess.Reason.COMPLETE -> "采集进程已结束"
        null -> "当前未记录完整logcat"
    }
    @Synchronized fun start(context: Context): String {
        if (process?.isRunning == true) return captureState()
        val failures=StringBuilder()
        val attempts=listOf(
            arrayOf("su","-c","logcat -b all -v threadtime"),
            arrayOf("su","-c","/system/bin/logcat -b all -v threadtime"),
            arrayOf("su","-c","sh -c 'logcat -b all -v threadtime'"),
            arrayOf("su","-c","logcat -v threadtime"))
        var dir:File?=null
        for ((index,command) in attempts.withIndex()) {
            val reservation=TrialLogStorage.reserve(context,TrialLogStorage.MAX_STREAM_BYTES+64*1024)
                ?: throw java.io.IOException("内部诊断日志达到64 MiB上限；现有日志已保留，请先导出或手动确认清理")
            var handedOff=false
            try {
                if (dir==null) {
                    dir=File(context.filesDir,"trial-logs/capture-${System.currentTimeMillis()}-${java.util.UUID.randomUUID()}")
                    check(dir.isDirectory || dir.mkdirs()); directory=dir
                    File(dir,"root-probe.txt").writeText(probeRoot())
                }
                val child=try { ProcessBuilder(*command).redirectErrorStream(true).start() }
                    catch (error:Exception) { failures.append("attempt=$index start=").append(error.javaClass.simpleName).append('\n');continue }
                val output=try { File(dir,"logcat-$index.txt").outputStream() } catch (error:Exception) {child.destroy();throw error}
                val session=BoundedLogProcess(child,output,TrialLogStorage.MAX_STREAM_BYTES,TrialLogStorage.MAX_CAPTURE_MILLIS){reservation.close()}
                handedOff=true; process=session
                if (!child.waitFor(900,TimeUnit.MILLISECONDS) && session.isRunning) return captureState()
                session.await(1000)
                if (session.reason==BoundedLogProcess.Reason.BYTES || session.reason==BoundedLogProcess.Reason.TIME) return captureState()
                failures.append("attempt=$index reason=").append(session.reason).append(" bytes=").append(session.bytes.get()).append('\n')
            } finally { if(!handedOff)reservation.close() }
        }
        dir?.let { File(it,"capture-failure.txt").writeText(failures.toString().take(4096)) }
        throw java.io.IOException("root日志启动失败；探针与限额内输出已保留")
    }
    private fun probeRoot(): String = try {
        val result=collect(arrayOf("su","-c","id"),8*1024L,8000)
        "reason=${result.second} out=${result.first.toString(Charsets.UTF_8).trim().take(500)}"
    } catch(error:Exception) { "probe=${error.javaClass.simpleName}" }
    @Synchronized fun stop() { process?.stop(); process?.await(1200) }
    private fun collect(command:Array<String>,limit:Long,millis:Long):Pair<ByteArray,BoundedLogProcess.Reason> {
        val child=ProcessBuilder(*command).redirectErrorStream(true).start()
        val output=ByteArrayOutputStream()
        val session=BoundedLogProcess(child,output,limit,millis)
        if(!session.await(millis+1500)) {session.stop(BoundedLogProcess.Reason.TIME);session.await(1000)}
        return output.toByteArray() to session.reason
    }
    /** No persistent snapshot directory: only the user-selected ZIP receives the new export. */
    fun export(context: Context, diagnostics: Bundle, output: OutputStream, includeLogcat: Boolean = false) {
        stop()
        val documents=linkedMapOf<String,ByteArray>()
        fun text(name:String,value:String) { documents[name]=value.toByteArray(Charsets.UTF_8) }
        text("00-先看这里-诊断速览.txt",DiagnosticOverview.render(diagnostics))
        text("01-阅读说明.txt",
            "阅读顺序：00诊断速览 → 各宿主operations → hook-log → diagnostics.json。\n"+
            "已注册不等于业务成功；未收到宿主回复属于证据不足。日志使用UTC时间、相对毫秒和本进程序号。\n"+
            "新快照直接写入导出ZIP，不再在内部目录保留副本。仅明确选择的当前采集会话进入本包，历史日志不递归加入。\n"+
            "采集每文件最多8 MiB、最长10分钟，内部总占用上限64 MiB；超限停止而不自动删除旧日志。\n"+
            "每个导出条目最多8 MiB，原始数据总计最多32 MiB，截断见archive-status.txt。完整logcat可能包含其他应用信息，分享前检查。\n")
        val diagnosticText=json(diagnostics).toString(2)
        text("diagnostics.json",if(diagnosticText.toByteArray(Charsets.UTF_8).size<=8*1024*1024)diagnosticText else
            JSONObject().put("truncated",true).put("reason","diagnostics exceeded 8 MiB; see overview and per-host logs").toString(2))
        text("module-log.txt",TrialLog.snapshot())
        text("manager-operations.txt",OperationDiagnostics.snapshot())
        val missing=mutableListOf<String>();val received=mutableListOf<String>()
        for(host in HookDiagnostics.hosts) {
            val report=diagnostics.getBundle(host);val hostLog=report?.getString("trialLog")
            if(hostLog==null)missing+=host else {
                received+=host
                text("$host-hook-log.txt",DiagnosticText.redact(hostLog).take(128*1024))
                text("$host-operations.txt",report.getString("operations")?.let{DiagnosticText.redact(it).take(48*1024)}?:"未收到新版操作摘要；不能据此判断业务成功。")
            }
        }
        text("host-log-status.txt","已收到宿主进程日志：${received.joinToString().ifEmpty { "无" }}\n"+
            "未回复：${missing.joinToString().ifEmpty { "无" }}（未安装版本无回复属于正常情况）\n"+
            "复现后保留TikTok后台运行再导出；管理端日志不能代替宿主日志。")
        if(includeLogcat)try {
            val dump=collect(arrayOf("su","-c","logcat -b all -d -v threadtime"),TrialLogStorage.MAX_STREAM_BYTES,20_000)
            documents["final-logcat.txt"]=dump.first;text("dump-status.txt","reason=${dump.second} bytes=${dump.first.size}")
        } catch(error:Exception){text("dump-status.txt","unavailable=${error.javaClass.simpleName}")}
        // Selecting a new export must not attach every historical root-logcat session.
        val capture = if (includeLogcat) synchronized(this) { directory } else null
        val status=StringBuilder("仅本次内存快照与明确选择的采集会话；历史文件保留但不自动加入。\n")
        var remaining=32L * 1024 * 1024
        ZipOutputStream(output).use { zip ->
            fun add(name:String,length:Long,source:()->java.io.InputStream) {
                val limit=minOf(8L * 1024 * 1024,remaining)
                if(limit<=0){status.append("跳过（总容量上限）：$name\n");return}
                zip.putNextEntry(ZipEntry(name))
                val written=source().use { BoundedLogProcess.copy(it,zip,limit){true} }
                remaining-=written;zip.closeEntry()
                status.append(if(length>written)"截断" else "完整").append(": $name bytes=$written\n")
            }
            for((name,data)in documents)add("snapshot/$name",data.size.toLong()){data.inputStream()}
            if(capture?.isDirectory==true)for(file in capture.listFiles().orEmpty().sortedBy{it.name}) {
                if(!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile!=capture.canonicalFile)continue
                add("selected-capture/"+file.name,file.length()){file.inputStream()}
            }
            zip.putNextEntry(ZipEntry("archive-status.txt"));zip.write(status.toString().toByteArray(Charsets.UTF_8));zip.closeEntry()
        }
    }
    @Suppress("DEPRECATION")
    private fun json(bundle: Bundle): JSONObject = JSONObject().apply {
        bundle.keySet().sorted().forEach { key ->
            val value = bundle.get(key)
            put(DiagnosticText.line(key, 240), when (value) {
                is Bundle -> json(value)
                is String -> DiagnosticText.redact(value).take(128 * 1024)
                else -> value ?: JSONObject.NULL
            })
        }
    }
}
