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
    private data class Owner(val account: String, val video: String, val comment: String, val source: String)
    private class Binding(val owner: Owner, var snapshot: OfflineCommentData.Snapshot, var row: OfflineCommentData.Row,
        var loader: ClassLoader, var valid: () -> Boolean, var status: ((OfflineCommentData.Media) -> String)? = null) {
        val tiles = mutableListOf<Tile>()
    }
    private class Tile(var asset: OfflineCommentData.Media, val frame: android.widget.FrameLayout, val image: ImageView,
        val badge: TextView) {
        var loading = false
        var attemptedRevision = -1L
    }
    // A row owns its decoded drawables. Rebinding the same identity only replaces callbacks,
    // never detaches the image, clears its drawable or restarts its animation.
    private val mediaRevision = java.util.concurrent.atomic.AtomicLong()
    private val mediaChanges = OfflineCommentMediaStore.observe { mediaRevision.incrementAndGet() }
    fun bind(container: LinearLayout, context: Context, snapshot: OfflineCommentData.Snapshot,
        row: OfflineCommentData.Row, loader: ClassLoader, valid: () -> Boolean) = bind(container, context, snapshot, row, loader, valid, null)
    fun bind(container: LinearLayout, context: Context, snapshot: OfflineCommentData.Snapshot,
        row: OfflineCommentData.Row, loader: ClassLoader, valid: () -> Boolean, status: ((OfflineCommentData.Media) -> String)?) {
        val owner = Owner(snapshot.account, snapshot.video, row.id, row.source)
        val previous = container.tag as? Binding
        val reusable = previous?.owner == owner && previous.tiles.map { it.asset.id } == row.media.map { it.id }
        val binding = if (reusable) checkNotNull(previous) else {
            previous?.tiles?.forEach { (it.image.drawable as? AnimatedImageDrawable)?.stop() }
            container.removeAllViews()
            Binding(owner, snapshot, row, loader, valid).also { container.tag = it }
        }
        binding.status = status
        binding.snapshot = snapshot; binding.row = row; binding.loader = loader; binding.valid = valid
        container.visibility = if (row.media.isEmpty()) View.GONE else View.VISIBLE
        val edge = (96 * context.resources.displayMetrics.density).toInt()
        fun current(tile: Tile): Boolean = container.tag === binding && binding.valid() && tile.frame.parent === container
        for ((index, asset) in row.media.withIndex()) {
            val tile = if (reusable) binding.tiles[index].also { it.asset = asset } else {
                val image = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER; tag = asset.id
                    contentDescription = context.getString(if (asset.kind == OfflineCommentData.MediaKind.IMAGE) R.string.offline_comment_image else R.string.offline_comment_sticker)
                }
                val frame = android.widget.FrameLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(if(asset.kind == OfflineCommentData.MediaKind.IMAGE)(edge * 1.4f).toInt() else edge, edge).apply { marginEnd = (8 * context.resources.displayMetrics.density).toInt() }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0x183F6D73); cornerRadius = 10 * context.resources.displayMetrics.density
                    }
                    clipToOutline = true
                }
                val badge = TextView(context).apply {
                    tag = "offline-media-badge"; textSize = 12f; gravity = android.view.Gravity.CENTER
                    setTextColor(context.getColor(R.color.toki_ui_secondary))
                    text = context.getString(if (asset.kind == OfflineCommentData.MediaKind.IMAGE) R.string.offline_comment_image else R.string.offline_comment_sticker)
                }
                frame.addView(image, android.widget.FrameLayout.LayoutParams(-1, -1))
                frame.addView(badge, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
                container.addView(frame)
                Tile(asset, frame, image, badge).also { created ->
                    binding.tiles += created
                    image.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) { if (current(created)) (image.drawable as? AnimatedImageDrawable)?.start() }
                        override fun onViewDetachedFromWindow(view: View) { (image.drawable as? AnimatedImageDrawable)?.stop() }
                    })
                    image.setOnClickListener { if (current(created)) preview(context, binding.snapshot, created.asset, binding.loader) { current(created) } }
                    image.setOnLongClickListener { if (current(created)) { save(context, binding.snapshot, created.asset, binding.loader) { current(created) }; true } else false }
                }
            }
            if (tile.image.drawable == null) {
                tile.badge.text = status?.invoke(asset)?.takeIf { it.isNotBlank() } ?: context.getString(R.string.offline_comment_media_missing)
                tile.badge.visibility = View.VISIBLE
            }
            val revision = mediaRevision.get()
            if (tile.image.drawable == null && !tile.loading && tile.attemptedRevision != revision) {
                tile.loading = true; tile.attemptedRevision = revision
                decode(tile.image, context, snapshot, asset, edge, { current(tile) }) {
                    tile.loading = false
                    // A callback rejected during a generation change may be retried on the next bind.
                    if (tile.image.drawable == null && !binding.valid()) tile.attemptedRevision = -1L
                }
            }
        }
    }
    private fun decode(image: ImageView, context: Context, snapshot: OfflineCommentData.Snapshot,
        asset: OfflineCommentData.Media, edge: Int, valid: () -> Boolean, finished: () -> Unit = {}) {
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
                    if (drawable != null && asset.kind == OfflineCommentData.MediaKind.IMAGE && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                        val frame=image.parent as? android.widget.FrameLayout
                        val width=(edge * 1.4f).toInt()
                        val height=(width.toFloat() * drawable.intrinsicHeight / drawable.intrinsicWidth).toInt().coerceIn(edge/2,edge*2)
                        frame?.layoutParams=frame?.layoutParams?.apply { this.width=width;this.height=height }
                    }
                    if (drawable != null && image.isAttachedToWindow) (drawable as? AnimatedImageDrawable)?.start()
                    (image.parent as? android.widget.FrameLayout)?.findViewWithTag<TextView>("offline-media-badge")?.apply {
                        visibility = if (drawable == null) View.VISIBLE else View.GONE
                        if (drawable == null) {
                            val binding = (image.parent?.parent as? View)?.tag as? Binding
                            text = binding?.status?.invoke(asset)?.takeIf { it.isNotBlank() }
                                ?: "媒体加载失败（本地文件缺失或无法解码）"
                        }
                    }
                    if (drawable == null) { image.contentDescription = context.getString(R.string.offline_comment_media_missing) }
                } else (drawable as? AnimatedImageDrawable)?.stop()
                finished()
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { image.contentDescription = context.getString(R.string.offline_comment_media_missing); finished() }
    }
    private fun preview(context: Context, snapshot: OfflineCommentData.Snapshot, asset: OfflineCommentData.Media,
        loader: ClassLoader, valid: () -> Boolean) {
        val image = ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        val dialog = TokiDialogs.builder(context).setTitle(context.getString(R.string.offline_comment_media_preview)).setView(image)
            .setPositiveButton(R.string.offline_comment_media_save) { _, _ -> if (valid()) save(context, snapshot, asset, loader, valid) }
            .setNegativeButton(R.string.common_done, null).create()
        dialog.setOnDismissListener { (image.drawable as? AnimatedImageDrawable)?.stop() }
        dialog.show()
        decode(image, context, snapshot, asset, 2048, { valid() && dialog.isShowing })
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
