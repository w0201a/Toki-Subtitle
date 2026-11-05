package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.OperationDiagnostics
import io.github.meiyongai.toki.subtitle.provider.OperationJournal
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Sticker-only source/export choices with an explicit route back to the full native comment menu. */
internal object CommentStickerDownloadHook {
    const val KEY = "comment_sticker_download_enabled"
    private const val FEATURE = "CommentStickerDownloadHook"
    private val bypass = ThreadLocal<Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val dialogs = java.util.WeakHashMap<Activity, AlertDialog>()

    internal fun isStickerComment(comment: Any): Boolean = try {
        comment.javaClass.getMethod("getStickerStruct").invoke(comment) != null
    } catch (_: ReflectiveOperationException) { false }

    internal class Reader(private val comment: Class<*>) {
        constructor(loader: ClassLoader) : this(loader.loadClass("com.ss.android.ugc.aweme.comment.model.Comment"))
        private val sticker = comment.getMethod("getStickerStruct")
        private val animated = sticker.returnType.getMethod("getAnimatedUrl")
        private val static = sticker.returnType.getMethod("getStaticUrl")
        private val id = sticker.returnType.getMethod("getId")
        private val sizes = listOf("highResolutionUrl", "midResolutionUrl", "lowResolutionUrl")
            .map { animated.returnType.getDeclaredField(it).apply { isAccessible = true } }
        private val urls = sizes.first().type.getMethod("getUrlList")
        init { check(animated.returnType == static.returnType && sizes.all { it.type == sizes.first().type }) }
        data class Asset(val id: String, val animatedUrls: List<String>, val staticUrls: List<String>) {
            val urls: List<String> get() = candidates(StickerSource.AUTOMATIC)
            fun candidates(source: StickerSource): List<String> = StickerExportPolicy.sources(animatedUrls, staticUrls, source)
            val sourceChoices: List<StickerSource> get() = StickerSource.entries.filter { candidates(it).isNotEmpty() }
        }
        fun read(value: Any?): Asset? {
            if (!comment.isInstance(value)) return null
            val data = sticker.invoke(value) ?: return null
            fun locations(model: Any?): List<String> = if (model == null) emptyList() else sizes.flatMap { field ->
                field.get(model)?.let { (urls.invoke(it) as? List<*>)?.filterIsInstance<String>() }.orEmpty()
            }
            val animation = CommentStickerMedia.urls(locations(animated.invoke(data)), emptyList())
            val preview = CommentStickerMedia.urls(emptyList(), locations(static.invoke(data)))
            val asset = Asset(id.invoke(data) as? String ?: "sticker", animation, preview)
            return asset.takeIf { it.urls.isNotEmpty() }
        }
    }

    fun init(module: XposedModule, loader: ClassLoader) {
        val roles = listOf("copy", "clip", "menu", "getComment", "item", "display", "text", "extra", "cid", "translated")
        val contract = CommentCopyHook.Contract(loader, roles.associateWith { HostSymbols.members(HostSymbol.COMMENT_COPY, it).single() })
        val reader = Reader(loader)
        val fragmentIndex = contract.menu.parameterTypes.indices.single { index ->
            contract.menu.parameterTypes[index].methods.any {
                it.name == "getActivity" && it.parameterCount == 0 && Activity::class.java.isAssignableFrom(it.returnType)
            }
        }
        val getActivity = contract.menu.parameterTypes[fragmentIndex].getMethod("getActivity")
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(6))
        HookRuntime.onDispose(FEATURE) {
            worker.shutdown(); inFlight.clear()
            dialogs.values.toList().forEach { it.dismiss() }; dialogs.clear()
        }
        module.trackHook(FEATURE, Activity::class.java.getDeclaredMethod("onPause"), requiresConfiguration = false).intercept { chain ->
            dialogs.remove(chain.thisObject)?.dismiss()
            chain.proceed()
        }
        module.trackHook(FEATURE, contract.menu).intercept { chain ->
            if (CommentNativeMenuScope.active || bypass.get() == true || !ConfigClient.getBoolean(KEY)) return@intercept chain.proceed()
            val args = chain.args.toTypedArray()
            val comment = args[contract.itemIndex]?.let { contract.getComment.invoke(it) }
            val asset = reader.read(comment) ?: return@intercept chain.proceed()
            val activity = args[fragmentIndex]?.let { getActivity.invoke(it) } as? Activity
                ?: return@intercept chain.proceed()
            if (activity.isFinishing || activity.isDestroyed) return@intercept chain.proceed()
            dialogs.remove(activity)?.dismiss()
            val context = HostSettingsEntry.localizedContext(activity)
            fun nativeMenu() {
                if (activity.isFinishing || activity.isDestroyed) return
                bypass.set(true)
                try { CommentNativeMenuScope.native { contract.menu.invoke(null, *args) } }
                catch (error: Exception) {
                    HookRuntime.event(FEATURE, "native menu failed=${error.javaClass.simpleName}")
                    Toast.makeText(activity, context.getString(R.string.sticker_save_failed), Toast.LENGTH_SHORT).show()
                } finally { bypass.remove() }
            }
            fun showDialog(dialog: AlertDialog) {
                if (activity.isFinishing || activity.isDestroyed) return
                dialogs.remove(activity)?.dismiss()
                dialogs[activity] = dialog
                dialog.setOnDismissListener { if (dialogs[activity] === dialog) dialogs.remove(activity) }
                dialog.show()
            }
            fun queueExport(source: StickerSource, mode: StickerExportMode) {
                if (activity.isFinishing || activity.isDestroyed || !ConfigClient.isReady || !ConfigClient.getBoolean(KEY) ||
                    !HookRuntime.scope(FEATURE).active) return
                val candidates = if (mode.animated) {
                    if (source == StickerSource.STATIC) emptyList() else asset.candidates(StickerSource.ANIMATED)
                } else asset.candidates(source)
                val first = candidates.firstOrNull() ?: return
                val key = mode.name + "|" + first
                if (!inFlight.add(key)) {
                    Toast.makeText(activity, context.getString(R.string.sticker_already_saving), Toast.LENGTH_SHORT).show()
                    return
                }
                val app = activity.applicationContext
                val saved = context.getString(R.string.sticker_saved)
                val failed = context.getString(if (mode.animated) R.string.sticker_animation_failed else R.string.sticker_save_failed)
                val directory = if (ConfigClient.getBoolean(DownloadHook.KEY_DOWNLOAD_PATH_ENABLED))
                    ConfigClient.getString(DownloadHook.KEY_DOWNLOAD_IMAGE_PATH, DownloadHook.DEFAULT_IMAGE_RELATIVE_PATH)!!
                else "Pictures/TikTok/Stickers"
                val operation = OperationDiagnostics.start(OperationJournal.Kind.STICKER_EXPORT)
                operation.stage(OperationJournal.Step.QUEUED, mapOf("animated" to if (mode.animated) 1L else 0L,
                    "sourceCandidates" to candidates.size.toLong()))
                try {
                    worker.execute {
                        val success = try {
                            save(app, loader, asset.id, candidates, directory, mode, operation) {
                                ConfigClient.isReady && ConfigClient.getBoolean(KEY) && HookRuntime.scope(FEATURE).active
                            }
                            operation.finish(OperationJournal.Result.SUCCESS)
                            true
                        } catch (error: Exception) {
                            val cancelled = !ConfigClient.isReady || !ConfigClient.getBoolean(KEY) || !HookRuntime.scope(FEATURE).active
                            operation.failed(error, cancelled)
                            HookRuntime.event(FEATURE, "export failed mode=" + mode.name + " type=" + error.javaClass.simpleName)
                            false
                        } catch (error: LinkageError) {
                            operation.failed(error)
                            HookRuntime.event(FEATURE, "export codec unavailable type=" + error.javaClass.simpleName)
                            false
                        } catch (_: OutOfMemoryError) {
                            operation.finish(OperationJournal.Result.UNAVAILABLE, OperationJournal.Reason.MEMORY_LIMIT, "OutOfMemoryError")
                            HookRuntime.event(FEATURE, "export unavailable: insufficient image memory")
                            false
                        } finally { inFlight.remove(key) }
                        main.post { Toast.makeText(app, if (success) saved else failed, Toast.LENGTH_LONG).show() }
                    }
                    Toast.makeText(activity, context.getString(R.string.sticker_saving), Toast.LENGTH_SHORT).show()
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    operation.finish(OperationJournal.Result.FAILED, OperationJournal.Reason.QUEUE_FULL)
                    inFlight.remove(key)
                    Toast.makeText(activity, failed, Toast.LENGTH_SHORT).show()
                }
            }
            fun chooseFormat(source: StickerSource) {
                val modes = StickerExportPolicy.modes(source, asset.animatedUrls.isNotEmpty())
                val labels = modes.map { mode -> context.getString(when (mode) {
                    StickerExportMode.PNG -> R.string.sticker_export_png
                    StickerExportMode.JPEG -> R.string.sticker_export_jpeg
                    StickerExportMode.WEBP -> R.string.sticker_export_webp
                    StickerExportMode.ANIMATED_GIF -> R.string.sticker_export_animated_gif
                    StickerExportMode.ANIMATED_WEBP -> R.string.sticker_export_animated_webp
                    StickerExportMode.ORIGINAL -> error("Original has a separate menu action")
                }) }.toTypedArray()
                showDialog(AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.sticker_export_title))
                    .setItems(labels) { _, index -> queueExport(source, modes[index]) }
                    .setNeutralButton(context.getString(R.string.sticker_animation_help)) { _, _ ->
                        showDialog(AlertDialog.Builder(context)
                            .setTitle(context.getString(R.string.sticker_animation_help))
                            .setMessage(context.getString(R.string.sticker_animation_limits))
                            .setPositiveButton(context.getString(R.string.sticker_animation_back)) { _, _ -> chooseFormat(source) }.create())
                    }
                    .setNegativeButton(context.getString(R.string.common_cancel), null).create())
            }
            fun chooseSource(original: Boolean) {
                val sources = asset.sourceChoices
                fun selected(source: StickerSource) {
                    if (original) queueExport(source, StickerExportMode.ORIGINAL) else chooseFormat(source)
                }
                if (sources.size <= 2) { selected(StickerSource.AUTOMATIC); return }
                val labels = sources.map { source -> context.getString(when (source) {
                    StickerSource.AUTOMATIC -> R.string.sticker_source_automatic
                    StickerSource.ANIMATED -> R.string.sticker_source_animated
                    StickerSource.STATIC -> R.string.sticker_source_static
                }) }.toTypedArray()
                showDialog(AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.sticker_source_title))
                    .setItems(labels) { _, index -> selected(sources[index]) }
                    .setNegativeButton(context.getString(R.string.common_cancel), null).create())
            }
            showDialog(AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.sticker_menu_title))
                .setItems(arrayOf(context.getString(R.string.sticker_download), context.getString(R.string.sticker_export),
                    context.getString(R.string.sticker_more))) { _, index ->
                    when (index) { 0 -> chooseSource(true); 1 -> chooseSource(false); else -> nativeMenu() }
                }.create())
            null
        }
    }

    internal fun fetch(location: String): ByteArray {
        var next = location
        repeat(5) {
            require(CommentStickerMedia.allowed(next))
            val request = URL(next).openConnection() as HttpURLConnection
            request.connectTimeout = 8000; request.readTimeout = 12000
            request.instanceFollowRedirects = false
            try {
                val code = request.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    next = URL(URL(next), request.getHeaderField("Location") ?: error("Missing redirect")).toString()
                } else {
                    check(code == 200 && request.contentLengthLong <= CommentStickerMedia.MAX_BYTES)
                    return request.inputStream.use { input ->
                        CommentStickerMedia.read(input).also { bytes ->
                            if (request.contentLengthLong >= 0L) check(bytes.size.toLong() == request.contentLengthLong) { "Incomplete image source" }
                        }
                    }
                }
            } finally { request.disconnect() }
        }
        error("Too many redirects")
    }

    private fun save(context: Context, loader: ClassLoader, id: String, candidates: List<String>, path: String,
        mode: StickerExportMode, operation: OperationJournal.Run, active: () -> Boolean) {
        check(active() && !Thread.currentThread().isInterrupted)
        if (mode.animated) {
            operation.stage(OperationJournal.Step.CODE_CHECK)
            try { HostAnimatedStickerDecoder.requireInspectedCode(context) }
            catch (error: Exception) {
                operation.finish(OperationJournal.Result.UNAVAILABLE, OperationJournal.Reason.CODE_MISMATCH, error.javaClass.simpleName)
                throw error
            }
        }
        var exported: StickerImageExporter.Result? = null
        var lastFailure: Exception? = null
        for (location in candidates) {
            check(active() && !Thread.currentThread().isInterrupted)
            exported = try {
                operation.stage(OperationJournal.Step.SOURCE_FETCH)
                val bytes = fetch(location)
                check(active() && !Thread.currentThread().isInterrupted)
                if (mode.animated) AnimatedStickerExporter.convert(context, loader, bytes, mode, active, operation)
                else StickerImageExporter.convert(bytes, mode)
            } catch (error: Exception) { lastFailure = error; operation.attemptFailed(error); null }
            if (exported != null) break
        }
        check(active() && !Thread.currentThread().isInterrupted)
        val (bytes, format) = exported ?: throw (lastFailure ?: IllegalStateException("No readable image for selected source and format"))
        publish(context, id, path, StickerImageExporter.Result(bytes, format), operation, active)
        HookRuntime.count(FEATURE, "保存贴纸成功")
        HookRuntime.event(FEATURE, "export success mode=" + mode.name + " format=" + format.extension)
    }
    internal fun saveCached(context: Context, loader: ClassLoader, id: String, source: ByteArray, path: String,
        mode: StickerExportMode, operation: OperationJournal.Run, active: () -> Boolean) {
        check(active())
        val exported = if (mode.animated) {
            HostAnimatedStickerDecoder.requireInspectedCode(context)
            AnimatedStickerExporter.convert(context, loader, source, mode, active, operation)
        } else StickerImageExporter.convert(source, mode)
        publish(context, id, path, exported, operation, active)
    }
    private fun publish(context: Context, id: String, path: String, exported: StickerImageExporter.Result,
        operation: OperationJournal.Run, active: () -> Boolean) {
        check(active() && !Thread.currentThread().isInterrupted)
        val (bytes, format) = exported
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, CommentStickerMedia.fileName(id, format.extension, UUID.randomUUID().toString()))
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, path)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        operation.stage(OperationJournal.Step.PUBLISH, mapOf("outputBytes" to bytes.size.toLong()))
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot write image")
        check(active() && !Thread.currentThread().isInterrupted)
        if (Build.VERSION.SDK_INT >= 29) check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null) > 0)
    }

}
