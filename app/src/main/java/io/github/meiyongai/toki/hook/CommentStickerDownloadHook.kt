package io.github.meiyongai.toki.subtitle.hook

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

/** Sticker-only long press menu, with the complete native comment menu available as a second action. */
internal object CommentStickerDownloadHook {
    const val KEY = "comment_sticker_download_enabled"
    private const val FEATURE = "CommentStickerDownloadHook"
    private val bypass = ThreadLocal<Boolean>()
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val dialogs = java.util.WeakHashMap<Activity, AlertDialog>()

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
        data class Asset(val id: String, val urls: List<String>)
        fun read(value: Any?): Asset? {
            if (!comment.isInstance(value)) return null
            val data = sticker.invoke(value) ?: return null
            fun locations(model: Any?): List<String> = if (model == null) emptyList() else sizes.flatMap { field ->
                field.get(model)?.let { (urls.invoke(it) as? List<*>)?.filterIsInstance<String>() }.orEmpty()
            }
            val choices = CommentStickerMedia.urls(locations(animated.invoke(data)), locations(static.invoke(data)))
            return if (choices.isEmpty()) null else Asset(id.invoke(data) as? String ?: "sticker", choices)
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
            if (bypass.get() == true || !ConfigClient.getBoolean(KEY)) return@intercept chain.proceed()
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
                try { contract.menu.invoke(null, *args) }
                catch (error: Exception) {
                    HookRuntime.event(FEATURE, "native menu failed=${error.javaClass.simpleName}")
                    Toast.makeText(activity, context.getString(R.string.sticker_save_failed), Toast.LENGTH_SHORT).show()
                } finally { bypass.remove() }
            }
            val dialog = AlertDialog.Builder(context)
                .setTitle(context.getString(R.string.sticker_menu_title))
                .setItems(arrayOf(context.getString(R.string.sticker_download), context.getString(R.string.sticker_more))) { _, index ->
                    if (index == 1) nativeMenu() else {
                        val key = asset.urls.first()
                        if (inFlight.add(key)) {
                            val app = activity.applicationContext
                            val saved = context.getString(R.string.sticker_saved)
                            val failed = context.getString(R.string.sticker_save_failed)
                            val path = if (ConfigClient.getBoolean(DownloadHook.KEY_DOWNLOAD_PATH_ENABLED))
                                ConfigClient.getString(DownloadHook.KEY_DOWNLOAD_IMAGE_PATH, DownloadHook.DEFAULT_IMAGE_RELATIVE_PATH)!!
                            else "Pictures/TikTok/Stickers"
                            try {
                                worker.execute {
                                    val success = runCatching { save(app, asset, path) }.onFailure {
                                        HookRuntime.event(FEATURE, "save failed=${it.javaClass.simpleName}")
                                    }.isSuccess
                                    inFlight.remove(key)
                                    main.post { Toast.makeText(app, if (success) saved else failed, Toast.LENGTH_LONG).show() }
                                }
                                Toast.makeText(activity, context.getString(R.string.sticker_saving), Toast.LENGTH_SHORT).show()
                            } catch (_: java.util.concurrent.RejectedExecutionException) {
                                inFlight.remove(key)
                                Toast.makeText(activity, context.getString(R.string.sticker_save_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }.create()
            dialogs[activity] = dialog
            dialog.setOnDismissListener { if (dialogs[activity] === dialog) dialogs.remove(activity) }
            dialog.show()
            null
        }
    }

    private fun fetch(location: String): ByteArray {
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
                        CommentStickerMedia.read(input)
                    }
                }
            } finally { request.disconnect() }
        }
        error("Too many redirects")
    }

    private fun save(context: Context, asset: Reader.Asset, path: String) {
        var downloaded: Pair<ByteArray, CommentStickerMedia.Format>? = null
        for (url in asset.urls) {
            val candidate = runCatching { fetch(url) }.getOrNull() ?: continue
            val format = CommentStickerMedia.format(candidate) ?: continue
            downloaded = candidate to format
            break
        }
        val (bytes, format) = checkNotNull(downloaded) { "No readable image" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, CommentStickerMedia.fileName(asset.id, format.extension, UUID.randomUUID().toString()))
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, path)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot write image")
        if (Build.VERSION.SDK_INT >= 29) check(resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }, null, null) > 0)
        HookRuntime.count(FEATURE, "保存贴纸成功")
    }
}
