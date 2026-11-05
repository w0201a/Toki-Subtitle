package io.github.meiyongai.toki.subtitle.provider

import android.os.Bundle
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class TrialLogCaptureTest {
    @Test fun currentExportHasReadableSummaryAndNeverRecursivelyAttachesHistoricalLogs() {
        val context = RuntimeEnvironment.getApplication()
        val old = java.io.File(context.filesDir, "trial-logs/old-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        java.io.File(old, "private-history.txt").writeText("PRIVATE_OLD_LOG_MARKER")
        val report = Bundle().apply {
            putString("trialLog", "url=https://secret.example/video")
            putString("operations", "op=OFFLINE_EXPORT-1 本次失败 阶段=回读目标文件")
        }
        val diagnostics = Bundle().apply { putBundle("com.zhiliaoapp.musically", report) }
        val output = ByteArrayOutputStream(); TrialLogCapture.export(context, diagnostics, output, false)
        val files = mutableMapOf<String, String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(files.keys.any { it.contains("00-先看这里") })
        assertTrue(files.keys.any { it.endsWith("-operations.txt") })
        assertTrue(files.containsKey("archive-status.txt"))
        assertFalse(files.values.any { it.contains("PRIVATE_OLD_LOG_MARKER") || it.contains("secret.example") })
        assertFalse(files.keys.any { it.contains("private-history") || it.contains("selected-capture") })
    }

    @Test fun rootFreeExportIncludesHostProcessLogAndReportsMissingReply() {
        val host = "com.zhiliaoapp.musically"
        val report = Bundle().apply {
            putBundle(host, Bundle().apply { putString("trialLog", "trial-4 host selection visible=false") })
        }
        val output = ByteArrayOutputStream()
        TrialLogCapture.export(RuntimeEnvironment.getApplication(), report, output, false)
        val files = mutableMapOf<String, String>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                files[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertTrue(files.any { (name, text) -> name.endsWith("$host-hook-log.txt") && text.contains("visible=false") })
        assertTrue(files.any { (name, text) -> name.endsWith("host-log-status.txt") && text.contains("com.ss.android.ugc.trill") })
        assertTrue(files.keys.any { it.endsWith("diagnostics.json") })
        assertFalse(files.keys.any { it.endsWith("final-logcat.txt") })
    }
    @Test fun exportingDoesNotLeaveAnotherPersistentSnapshotOrModifyOldLogs() {
        val context=RuntimeEnvironment.getApplication()
        val root=java.io.File(context.filesDir,"trial-logs")
        val old=java.io.File(root,"keep-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        val marker=java.io.File(old,"logcat-0.txt").apply{writeText("keep original evidence")}
        val before=root.walkTopDown().filter{it.isFile}.map{it.absolutePath to it.length()}.toMap()
        repeat(3){TrialLogCapture.export(context,Bundle(),ByteArrayOutputStream(),false)}
        val after=root.walkTopDown().filter{it.isFile}.map{it.absolutePath to it.length()}.toMap()
        assertEquals(before,after);assertEquals("keep original evidence",marker.readText())
    }
}
