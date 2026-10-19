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
}
