package io.github.meiyongai.toki.subtitle.hook

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.util.UUID

/** Exports verified local MP4 bytes. Source caches are never changed, renamed, deleted or fetched. */
internal object OfflineVideoExporter {
    fun save(context: Context, source: LocalVideoFile.Verified, directory: String, active: () -> Boolean): String {
        if (Build.VERSION.SDK_INT < 29) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.UNSUPPORTED)
        require(directory.substringBefore('/') in setOf("Movies", "DCIM", "Pictures", "Download") &&
            directory.split('/').all { it.isNotBlank() && it == it.trim() && it != "." && it != ".." } &&
            !directory.contains('\\'))
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "TikTok_offline_" + UUID.randomUUID() + ".mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, directory)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        check(active())
        val resolver = context.contentResolver
        val target = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        // A failed pending row is deliberately not deleted without user confirmation; never publish partial bytes.
        resolver.openOutputStream(target, "w")?.use { LocalVideoFile.copy(source, it, active) }
            ?: error("Local export output unavailable")
        check(active())
        check(resolver.update(target, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) == 1)
        HookRuntime.count("OfflineVideoHook", "完整本地视频导出成功")
        return target.toString()
    }
}
