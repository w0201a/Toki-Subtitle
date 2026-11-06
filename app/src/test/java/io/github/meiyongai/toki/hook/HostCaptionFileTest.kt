package io.github.meiyongai.toki.subtitle.hook

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class HostCaptionFileTest {
    private fun root(name: String) = File(RuntimeEnvironment.getApplication().filesDir, name).apply { mkdirs() }
    @Test fun hostWebVttIsReadWithoutAnyNetworkPermissionAndGrowingFileRefreshes() {
        val files = root("caption-local")
        val directory = File(files, "realtime_captions_cache").apply { mkdirs() }
        val file = File(directory, "cla_realtime_[video]_[zh-Hans]")
        val first = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n第一句\n\n"
        file.writeText(first)
        assertTrue(HostCaptionFile.isLocal(file.toURI().toString()))
        assertEquals("第一句", CaptionTimeline.parse(HostCaptionFile.read(files, file.toURI().toString()) { true }).single().text)
        file.appendText("00:00:01.000 --> 00:00:02.000\n第二句\n\n")
        val cues = CaptionTimeline.parse(HostCaptionFile.read(files, file.toURI().toString()) { true })
        assertEquals("第二句", CaptionTimeline.at(cues, 1200))
        assertNull(CaptionTimeline.at(cues, 2000))
    }
    @Test fun arbitraryFilesTraversalAndOversizeAreNotAccepted() {
        val files = root("caption-boundaries")
        val directory = File(files, "realtime_captions_cache").apply { mkdirs() }
        val outside = File(files, "cla_realtime_outside").apply { writeText("private") }
        assertThrows(IllegalArgumentException::class.java) { HostCaptionFile.read(files, outside.toURI().toString()) { true } }
        assertThrows(IllegalArgumentException::class.java) { HostCaptionFile.read(files, File(directory, "../cla_realtime_outside").path) { true } }
        val huge = File(directory, "cla_realtime_huge").apply { writeBytes(ByteArray(1_048_577)) }
        assertThrows(IllegalArgumentException::class.java) { HostCaptionFile.read(files, huge.toURI().toString()) { true } }
        assertFalse(HostCaptionFile.isLocal("https://cdn.tiktok.com/video.vtt"))
    }
    @Test fun cancelledReadDoesNotPublishPartialText() {
        val files = root("caption-cancel")
        val directory = File(files, "realtime_captions_cache").apply { mkdirs() }
        val file = File(directory, "cla_realtime_cancel").apply { writeText("WEBVTT\n") }
        assertThrows(IllegalStateException::class.java) { HostCaptionFile.read(files, file.toURI().toString()) { false } }
    }
}
