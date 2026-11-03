package io.github.meiyongai.toki.subtitle.hook

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.util.UUID

/** Export downloaded offline video to a user-accessible playable MP4, without modifying its cache or fetching bytes. */
internal object OfflineVideoExporter {
    fun save(context: Context, source: LocalVideoTracks.Plan, directory: String, active: () -> Boolean): String {
        if (Build.VERSION.SDK_INT < 29) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.UNSUPPORTED)
        require(directory.substringBefore('/') in setOf("Movies", "DCIM", "Pictures", "Download") &&
            directory.split('/').all { it.isNotBlank() && it == it.trim() && it != "." && it != ".." } &&
            !directory.contains('\\') && ':' !in directory && directory.none(Char::isISOControl))
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "TikTok_offline_" + UUID.randomUUID() + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, directory)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        source.verify(active)
        val resolver = context.contentResolver
        val target = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        // Pending on failure; do not delete any user/provider data without confirmation, or publish incomplete output.
        resolver.openFileDescriptor(target, "rw")?.use { destination ->
            LocalVideoRemuxer.write(source, destination.fileDescriptor, active)
            destination.fileDescriptor.sync()
            val size = destination.statSize
            LocalVideoRemuxer.verifyOutput(source, destination.fileDescriptor, size, active)
        } ?: error("Local export destination unavailable")
        source.verify(active)
        check(resolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1)
        HookRuntime.count("OfflineVideoHook", "离线视频导出成功")
        return target.toString()
    }
}
