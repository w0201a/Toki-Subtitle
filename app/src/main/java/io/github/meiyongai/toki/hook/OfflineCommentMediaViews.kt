package io.github.meiyongai.toki.subtitle.hook

import android.app.AlertDialog
import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.provider.OperationDiagnostics
import io.github.meiyongai.toki.subtitle.provider.OperationJournal
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Cache-only images and animated stickers. Neither row binding nor export has a URL download path. */
internal object OfflineCommentMediaViews {
    private val main = Handler(Looper.getMainLooper())
    private val worker = ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64),
        { task -> Thread(task, "toki-offline-media-view").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    fun bind(container: LinearLayout, context: Context, snapshot: OfflineCommentData.Snapshot,
        row: OfflineCommentData.Row, loader: ClassLoader, valid: () -> Boolean) {
        for (i in 0 until container.childCount) ((container.getChildAt(i) as? ImageView)?.drawable as? AnimatedImageDrawable)?.stop()
        container.removeAllViews(); container.visibility = if (row.media.isEmpty()) View.GONE else View.VISIBLE
        val edge = (96 * context.resources.displayMetrics.density).toInt()
        for (asset in row.media) {
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = context.getString(if (asset.kind == OfflineCommentData.MediaKind.IMAGE) R.string.offline_comment_image else R.string.offline_comment_sticker)
                layoutParams = LinearLayout.LayoutParams(edge, edge).apply { marginEnd = 8 }
                tag = asset.id
            }
            image.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) { (image.drawable as? AnimatedImageDrawable)?.start() }
                override fun onViewDetachedFromWindow(view: View) { (image.drawable as? AnimatedImageDrawable)?.stop() }
            })
            container.addView(image)
            decode(image, context, snapshot, asset, edge) { valid() && image.parent === container && image.tag == asset.id }
            image.setOnClickListener {
                if (valid()) preview(context, snapshot, asset, loader, valid)
            }
            image.setOnLongClickListener { if (valid()) { save(context, snapshot, asset, loader, valid); true } else false }
        }
    }
    private fun decode(image: ImageView, context: Context, snapshot: OfflineCommentData.Snapshot,
        asset: OfflineCommentData.Media, edge: Int, valid: () -> Boolean) {
        try { worker.execute {
            val drawable = try {
                val file = OfflineCommentMediaStore.file(context, snapshot.account, snapshot.video, asset) ?: error("Media missing")
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val target = StickerExportPolicy.target(info.size.width, info.size.height)
                    val scale = minOf(1f, edge.toFloat() / maxOf(target.width, target.height))
                    decoder.setTargetSize(maxOf(1, (target.width * scale).toInt()), maxOf(1, (target.height * scale).toInt()))
                    decoder.setOnPartialImageListener { false }
                }
            } catch (_: Exception) { null }
            main.post {
                if (valid()) {
                    image.setImageDrawable(drawable)
                    if (drawable != null && image.isAttachedToWindow) (drawable as? AnimatedImageDrawable)?.start()
                    if (drawable == null) { image.setBackgroundColor(0x22333333); image.contentDescription = context.getString(R.string.offline_comment_media_missing) }
                } else (drawable as? AnimatedImageDrawable)?.stop()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { image.contentDescription = context.getString(R.string.offline_comment_media_missing) }
    }
    private fun preview(context: Context, snapshot: OfflineCommentData.Snapshot, asset: OfflineCommentData.Media,
        loader: ClassLoader, valid: () -> Boolean) {
        val image = ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        val dialog = TokiDialogs.builder(context).setTitle(context.getString(R.string.offline_comment_media_preview)).setView(image)
            .setPositiveButton(R.string.offline_comment_media_save) { _, _ -> if (valid()) save(context, snapshot, asset, loader, valid) }
            .setNegativeButton(R.string.common_done, null).create()
        dialog.setOnDismissListener { (image.drawable as? AnimatedImageDrawable)?.stop() }
        dialog.show()
        decode(image, context, snapshot, asset, 2048) { valid() && dialog.isShowing }
    }
    private fun save(context: Context, snapshot: OfflineCommentData.Snapshot, asset: OfflineCommentData.Media,
        loader: ClassLoader, valid: () -> Boolean) {
        val modes = listOf(StickerExportMode.ORIGINAL, StickerExportMode.PNG, StickerExportMode.JPEG, StickerExportMode.WEBP) +
            if (asset.animated) listOf(StickerExportMode.ANIMATED_GIF, StickerExportMode.ANIMATED_WEBP) else emptyList()
        val names = modes.map { mode -> when (mode) {
            StickerExportMode.ORIGINAL -> context.getString(R.string.offline_comment_media_original)
            StickerExportMode.PNG -> "PNG"; StickerExportMode.JPEG -> "JPEG"; StickerExportMode.WEBP -> "WebP"
            StickerExportMode.ANIMATED_GIF -> "GIF"; StickerExportMode.ANIMATED_WEBP -> context.getString(R.string.offline_comment_media_animated_webp)
        } }
        TokiDialogs.builder(context).setTitle(R.string.offline_comment_media_save).setItems(names.toTypedArray()) { _, which ->
            if (!valid()) return@setItems
            val operation = OperationDiagnostics.start(OperationJournal.Kind.STICKER_EXPORT)
            try { worker.execute {
                val result = try {
                    val bytes = OfflineCommentMediaStore.bytes(context, snapshot.account, snapshot.video, asset)
                    val directory = if (ConfigClient.getBoolean(DownloadHook.KEY_DOWNLOAD_PATH_ENABLED))
                        ConfigClient.getString(DownloadHook.KEY_DOWNLOAD_IMAGE_PATH, DownloadHook.DEFAULT_IMAGE_RELATIVE_PATH)!!
                        else DownloadHook.DEFAULT_IMAGE_RELATIVE_PATH
                    CommentStickerDownloadHook.saveCached(context, loader, asset.id, bytes, directory, modes[which], operation, valid)
                    operation.finish(OperationJournal.Result.SUCCESS, OperationJournal.Reason.NONE); true
                } catch (error: Exception) {
                    operation.attemptFailed(error); operation.finish(OperationJournal.Result.FAILED, OperationJournal.Reason.OUTPUT_FAILED, error.javaClass.simpleName); false
                }
                main.post { if (valid()) Toast.makeText(context, context.getString(if (result) R.string.sticker_saved else R.string.offline_comment_media_save_missing), Toast.LENGTH_LONG).show() }
            } } catch (_: java.util.concurrent.RejectedExecutionException) {
                operation.finish(OperationJournal.Result.FAILED, OperationJournal.Reason.QUEUE_FULL)
            }
        }.show()
    }
}
