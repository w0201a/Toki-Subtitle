package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Dedicated local actions on the selected offline video; never opens native share/download menus. */
internal object OfflineVideoHook {
    const val KEY = "offline_video_controls_enabled"
    private const val FEATURE = "OfflineVideoHook"
    private class Binding(holder: Any, video: OfflineVideoContract.Video, activity: Activity) {
        val owner = WeakReference(holder)
        val view = WeakReference(video.view)
        val activity = WeakReference(activity)
        val id = video.id
        val offline = video.offline
    }

    private class Lifetime { @Volatile var enabled = false; @Volatile var closed = false }

    fun init(module: XposedModule, loader: ClassLoader) {
        val contract = OfflineVideoContract(loader)
        val state = OfflinePlaybackContext.state
        val main = Handler(Looper.getMainLooper())
        val gesture = OfflineLongPressState()
        val busy = AtomicBoolean(false)
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), ThreadPoolExecutor.AbortPolicy())
        val cache = HostLocalVideoCache(loader)
        val commentReader = HostOfflineComments(loader)
        val activities = mutableSetOf<Method>()
        var binding: Binding? = null
        var timer: Runnable? = null
        var dialog: AlertDialog? = null
        var comments: AlertDialog? = null
        val life = Lifetime()
        var cancellingNative = false

        fun cancelTouch() { timer?.let(main::removeCallbacks); timer = null; gesture.cancel() }
        fun closeMenu() { dialog?.dismiss(); dialog = null; comments?.dismiss(); comments = null }
        fun current(target: Binding): Boolean = !life.closed && life.enabled && binding === target && target.offline &&
            target.owner.get()?.let { state.owns(it, target.id) } == true
        fun export(target: Binding) {
            if (!current(target)) return
            val activity = target.activity.get() ?: return
            val owner = target.owner.get() ?: return
            val video = contract.read(owner)?.takeIf { it.id == target.id && it.offline } ?: return
            if (!busy.compareAndSet(false, true)) { HostSettingsEntry.showMessage(R.string.offline_video_busy); return }
            val app = activity.applicationContext
            val configured = if (ConfigClient.getBoolean(DownloadHook.KEY_DOWNLOAD_PATH_ENABLED))
                ConfigClient.getString(DownloadHook.KEY_DOWNLOAD_VIDEO_PATH, DownloadHook.DEFAULT_VIDEO_RELATIVE_PATH)
                else DownloadHook.DEFAULT_VIDEO_RELATIVE_PATH
            val directory = configured?.takeIf { it.substringBefore('/') in setOf("Movies", "DCIM", "Pictures", "Download") }
                ?: DownloadHook.DEFAULT_VIDEO_RELATIVE_PATH
            try {
                worker.execute {
                    val message = try {
                        check(!life.closed && life.enabled)
                        val local = cache.resolve(app, video.model)
                        OfflineVideoExporter.save(app, local, directory) { !life.closed && life.enabled }
                        R.string.offline_video_saved
                    } catch (error: LocalVideoFile.Unavailable) {
                        HookRuntime.event(FEATURE, "local export unavailable reason=" + error.reason.name)
                        when (error.reason) {
                            LocalVideoFile.Failure.MISSING -> R.string.offline_video_missing
                            LocalVideoFile.Failure.INCOMPLETE -> R.string.offline_video_incomplete
                            LocalVideoFile.Failure.UNSUPPORTED -> R.string.offline_video_unsupported
                            else -> R.string.offline_video_export_failed
                        }
                    } catch (error: LinkageError) {
                        HookRuntime.event(FEATURE, "local export adapter unavailable type=" + error.javaClass.simpleName)
                        R.string.offline_video_unsupported
                    } catch (error: Exception) {
                        HookRuntime.event(FEATURE, "local export failed type=" + error.javaClass.simpleName)
                        R.string.offline_video_export_failed
                    } finally { busy.set(false) }
                    main.post { if (!life.closed) HostSettingsEntry.showMessage(message) }
                }
                HostSettingsEntry.showMessage(R.string.offline_video_saving)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                busy.set(false); HostSettingsEntry.showMessage(R.string.offline_video_busy)
            }
        }
        fun showMenu(target: Binding) {
            if (!current(target)) return
            val activity = target.activity.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return
            val context = HostSettingsEntry.localizedContext(activity)
            closeMenu()
            val shown = AlertDialog.Builder(context).setTitle(context.getString(R.string.offline_video_menu))
                .setItems(arrayOf(context.getString(R.string.offline_video_save), context.getString(R.string.offline_video_subtitles),
                    context.getString(R.string.offline_comments_open))) { _, action ->
                    try {
                        if (current(target)) {
                            when (action) {
                                0 -> export(target)
                                1 -> HostSettingsEntry.openSubtitle(activity)
                                2 -> {
                                    comments?.dismiss()
                                    comments = OfflineCommentsDialog.show(activity, target.id, commentReader, { current(target) }) { closed ->
                                        if (comments === closed) comments = null
                                    }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        HookRuntime.event(FEATURE, "local action unavailable type=" + error.javaClass.simpleName)
                        HostSettingsEntry.showMessage(R.string.offline_video_export_failed)
                    }
                }.setNegativeButton(context.getString(R.string.common_close), null).create()
            dialog = shown
            shown.setOnDismissListener { if (dialog === shown) dialog = null }
            shown.show()
        }
        fun touch(activity: Activity, event: MotionEvent): Boolean {
            if (cancellingNative) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN && gesture.claimed) cancelTouch()
            if (gesture.claimed) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) cancelTouch()
                return true
            }
            val target = binding
            if (target == null || target.activity.get() !== activity || !current(target) || dialog?.isShowing == true) {
                cancelTouch(); return false
            }
            val view = target.view.get() ?: run { cancelTouch(); return false }
            val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelTouch()
                    if (event.pointerCount != 1 || !videoHit(view, event.rawX, event.rawY) ||
                        !activity.hasWindowFocus()) return false
                    val token = gesture.begin(event.rawX, event.rawY)
                    val down = event.downTime
                    val x = event.x; val y = event.y
                    val revision = state.revision
                    timer = Runnable {
                        timer = null
                        if (!current(target) || state.revision != revision || !view.isAttachedToWindow || !view.isShown ||
                            !activity.hasWindowFocus() || !gesture.claim(token)) return@Runnable
                        // Cancel the native video/speed gesture before opening a local action window.
                        val cancel = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y, 0)
                        try {
                            cancellingNative = true
                            activity.dispatchTouchEvent(cancel)
                        } catch (error: Exception) {
                            HookRuntime.event(FEATURE, "native gesture cancellation failed type=" + error.javaClass.simpleName)
                            cancelTouch(); return@Runnable
                        } finally { cancellingNative = false; cancel.recycle() }
                        try { showMenu(target) } catch (error: Exception) {
                            HookRuntime.event(FEATURE, "local menu failed type=" + error.javaClass.simpleName)
                            cancelTouch()
                        }
                    }.also { main.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                }
                MotionEvent.ACTION_MOVE -> gesture.move(event.rawX, event.rawY, event.pointerCount, slop)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelTouch()
            }
            return false
        }
        fun select(owner: Any) {
            if (life.closed || !life.enabled || Looper.myLooper() != main.looper) return
            val video = contract.read(owner) ?: run {
                cancelTouch(); closeMenu(); binding = null; state.select(owner, "", true); return
            }
            state.select(owner, video.id, video.offline)
            val activity = activity(video.view.context) ?: run { cancelTouch(); closeMenu(); binding = null; return }
            val previous = binding
            val changed = previous == null || previous.owner.get() !== owner || previous.id != video.id ||
                previous.offline != video.offline || previous.view.get() !== video.view || previous.activity.get() !== activity
            if (changed) { cancelTouch(); closeMenu(); binding = Binding(owner, video, activity) }
            val dispatch = activity.javaClass.getMethod("dispatchTouchEvent", MotionEvent::class.java)
            if (activities.add(dispatch)) module.trackHook(FEATURE, dispatch, requiresConfiguration = false).intercept { chain ->
                if (touch(chain.thisObject as Activity, chain.args[0] as MotionEvent)) true else chain.proceed()
            }
        }
        for (method in listOf(contract.selected, contract.resumed)) {
            module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
                chain.thisObject?.let(::select) // Local classification must precede callbacks inside native selection.
                val result = chain.proceed()
                chain.thisObject?.let(::select)
                result
            }
        }
        module.trackHook(FEATURE, contract.progress, requiresConfiguration = false).intercept { chain ->
            val owner = chain.thisObject
            val id = chain.args[0] as? String
            if (owner != null && id != null && state.owns(owner, id)) select(owner)
            chain.proceed()
        }
        for (method in contract.released) module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
            val owner = chain.thisObject
            if (binding?.owner?.get() === owner) { cancelTouch(); closeMenu(); binding = null; if (owner != null) state.retire(owner) }
            chain.proceed()
        }
        module.trackHook(FEATURE, Activity::class.java.getDeclaredMethod("onPause"), requiresConfiguration = false).intercept { chain ->
            if (binding?.activity?.get() === chain.thisObject) { cancelTouch(); closeMenu(); binding = null; state.clear() }
            chain.proceed()
        }
        HookRuntime.subscribe(FEATURE) { snapshot ->
            life.enabled = snapshot?.boolean(KEY) == true
            if (!life.enabled) { cancelTouch(); closeMenu(); binding = null; state.clear() }
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
        }
        HookRuntime.onDispose(FEATURE) {
            life.closed = true; life.enabled = false; worker.shutdown()
            fun release() { cancelTouch(); closeMenu(); binding = null; state.clear() }
            if (Looper.myLooper() == main.looper) release() else main.post { release() }
        }
    }

    private fun activity(context: Context): Activity? {
        var current = context
        repeat(16) {
            if (current is Activity) return current as Activity
            current = (current as? ContextWrapper)?.baseContext ?: return null
        }
        return null
    }

    /** Reject long presses on unrelated buttons/inputs over the video, including translucent overlays. */
    internal fun videoHit(video: View, x: Float, y: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !video.isShown || !video.isAttachedToWindow) return false
        val rect = Rect()
        if (!video.getGlobalVisibleRect(rect) || !rect.contains(x.toInt(), y.toInt())) return false
        fun related(view: View): Boolean = view === video ||
            generateSequence(view.parent) { it.parent }.any { it === video } ||
            generateSequence(video.parent) { it.parent }.any { it === view }
        val queue = ArrayDeque<View>().apply { add(video.rootView) }
        var visited = 0
        while (queue.isNotEmpty()) {
            if (++visited > 1200) return false
            val view = queue.removeFirst()
            if (!view.isShown || !view.getGlobalVisibleRect(rect) || !rect.contains(x.toInt(), y.toInt())) continue
            if (!related(view) && (view.isClickable || view.isLongClickable || view is EditText)) return false
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        return true
    }
}
