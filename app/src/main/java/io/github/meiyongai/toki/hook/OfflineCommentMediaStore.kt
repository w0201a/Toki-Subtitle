package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.graphics.ImageDecoder
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Original image/sticker bytes live with their account/video companion. Display and export never download on a cache miss. */
internal object OfflineCommentMediaStore {
    private fun directory(context: Context, account: String, video: String): File =
        File(File(context.noBackupFilesDir, "toki-offline-comments-v2/" + CommentSnapshotStore.key(account, video)), "media")
    private fun base(context: Context, account: String, video: String, asset: OfflineCommentData.Media): File {
        require(asset.id.matches(Regex("[a-f0-9]{64}")))
        return directory(context, account, video)
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun observe(listener: () -> Unit): AutoCloseable { listeners += listener; return AutoCloseable { listeners -= listener } }
    private val verified = object : LinkedHashMap<String, Pair<Long, Long>>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Long>>?) = size > 512
    }
    @Synchronized fun file(context: Context, account: String, video: String, asset: OfflineCommentData.Media): File? {
        val dir = base(context, account, video, asset)
        val file = File(dir, asset.id + ".bin"); val record = File(dir, asset.id + ".json")
        if (!file.isFile || !record.isFile || record.length() !in 1..4096L || file.length() !in 1..CommentStickerMedia.MAX_BYTES.toLong()) return null
        check(file.canonicalFile.parentFile == dir.canonicalFile && record.canonicalFile.parentFile == dir.canonicalFile)
        val stamp = file.lastModified() to file.length()
        if (verified[file.absolutePath] == stamp) return file
        val metadata = try { JSONObject(record.readText(Charsets.UTF_8)) } catch (_: Exception) { return null }
        val bytes = file.readBytes()
        if (metadata.optString("sha256") != digest(bytes) || metadata.optLong("size", -1L) != bytes.size.toLong() || CommentStickerMedia.format(bytes) == null) return null
        verified[file.absolutePath] = stamp
        return file
    }
    fun bytes(context: Context, account: String, video: String, asset: OfflineCommentData.Media): ByteArray =
        checkNotNull(file(context, account, video, asset)) { "Offline media not cached" }.readBytes()
    fun cache(context: Context, account: String, video: String, asset: OfflineCommentData.Media, active: () -> Boolean) {
        if (file(context, account, video, asset) != null) return
        var failure: Exception? = null
        for (url in asset.urls) {
            check(active()); require(CommentStickerMedia.allowed(url))
            try {
                val bytes = CommentStickerDownloadHook.fetch(url)
                store(context, account, video, asset, bytes, active)
                return
            } catch (error: Exception) { failure = error }
        }
        throw (failure ?: IllegalStateException("No offline media source"))
    }
    internal fun store(context: Context, account: String, video: String, asset: OfflineCommentData.Media,
        bytes: ByteArray, active: () -> Boolean, validate: (ByteArray) -> Unit = ::validateImage) {
        check(active())
        require(bytes.size in 1..CommentStickerMedia.MAX_BYTES)
        requireNotNull(CommentStickerMedia.format(bytes)); validate(bytes); check(active())
        val dir = base(context, account, video, asset); check(dir.isDirectory || dir.mkdirs())
        fun write(name: String, value: ByteArray) {
            check(active())
            val pending = File(dir, name + ".new")
            java.io.FileOutputStream(pending).use { it.write(value); it.fd.sync() }
            java.nio.file.Files.move(pending.toPath(), File(dir, name).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
        write(asset.id + ".bin", bytes)
        write(asset.id + ".json", JSONObject().put("size", bytes.size).put("sha256", digest(bytes)).toString().toByteArray(Charsets.UTF_8))
        check(file(context, account, video, asset) != null)
        listeners.forEach { try { it() } catch (_: Exception) { } }
    }
    private fun validateImage(bytes: ByteArray) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            StickerExportPolicy.target(info.size.width, info.size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(1, 1)
            decoder.setOnPartialImageListener { false }
        }
        bitmap.recycle()
    }
}
