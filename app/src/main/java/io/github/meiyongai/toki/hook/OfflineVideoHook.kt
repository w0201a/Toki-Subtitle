package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.OperationDiagnostics
import io.github.meiyongai.toki.subtitle.provider.OperationJournal
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
    const val KEY_ENTRY = "offline_comment_button_enabled"
    private const val FEATURE = "OfflineVideoHook"
    private class Binding(holder: Any, video: OfflineVideoContract.Video, activity: Activity) {
        val owner = WeakReference(holder)
        val view = WeakReference(video.view)
        val activity = WeakReference(activity)
        val id = video.id
        val offline = video.offline
    }

    private class Lifetime { @Volatile var enabled = false; @Volatile var longPress = false; @Volatile var closed = false }

    fun init(module: XposedModule, loader: ClassLoader) {
        val contract = OfflineVideoContract(loader)
        val state = OfflinePlaybackContext.state
        val main = Handler(Looper.getMainLooper())
        val gesture = OfflineLongPressState()
        val busy = AtomicBoolean(false)
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), ThreadPoolExecutor.AbortPolicy())
        val cache = HostLocalVideoCache(loader)
        val commentReader = HostOfflineComments(loader)
        val commentNetwork = HostCommentPageNetwork(loader)
        val commentReplies = HostCommentReplyNetwork(loader)
        val activities = mutableSetOf<Method>()
        var binding: Binding? = null
        var timer: Runnable? = null
        var dialog: AlertDialog? = null
        var comments: AlertDialog? = null
        val life = Lifetime()
        var cancellingNative = false
        val entryReady = AtomicBoolean(false)
        val speedReady = AtomicBoolean(false)
        val speedPress = OfflineHoldArbiter()

        fun cancelTouch() { timer?.let(main::removeCallbacks); timer = null; gesture.cancel() }
        fun closeMenu() { dialog?.dismiss(); dialog = null; comments?.dismiss(); comments = null }
        HookRuntime.onDispose(FEATURE) {
            life.closed = true; life.enabled = false; entryReady.set(false); speedReady.set(false); speedPress.clear(); worker.shutdown()
            fun release() { cancelTouch(); closeMenu(); binding = null; state.clear() }
            if (Looper.myLooper() == main.looper) release() else main.post { release() }
        }
        fun current(target: Binding): Boolean = !life.closed && life.enabled && binding === target && target.offline &&
            target.owner.get()?.let { state.owns(it, target.id) } == true
        fun export(target: Binding) {
            if (!current(target) || !life.longPress) return
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
            val operation = OperationDiagnostics.start(OperationJournal.Kind.OFFLINE_EXPORT)
            val selectedRevision = state.revision
            val exportActive = {
                !life.closed && life.enabled && life.longPress && state.revision == selectedRevision && state.owns(owner, target.id)
            }
            try {
                worker.execute {
                    val message = try {
                        check(!life.closed && life.enabled && life.longPress)
                        val local = cache.resolve(app, video.model, exportActive, operation)
                        OfflineVideoExporter.save(app, local, directory, exportActive, operation)
                        operation.finish(OperationJournal.Result.SUCCESS)
                        R.string.offline_video_saved
                    } catch (error: LocalVideoFile.Unavailable) {
                        operation.finish(if (!exportActive()) OperationJournal.Result.CANCELLED else OperationJournal.Result.UNAVAILABLE,
                            if (!exportActive()) OperationJournal.Reason.CANCELLED else when (error.reason) {
                                LocalVideoFile.Failure.MISSING -> OperationJournal.Reason.CACHE_MISSING
                                LocalVideoFile.Failure.INCOMPLETE -> OperationJournal.Reason.CACHE_INCOMPLETE
                                LocalVideoFile.Failure.CHANGED -> OperationJournal.Reason.SOURCE_CHANGED
                                LocalVideoFile.Failure.CODE_MISMATCH -> OperationJournal.Reason.CODE_MISMATCH
                                LocalVideoFile.Failure.TIMELINE -> OperationJournal.Reason.TIMELINE
                                LocalVideoFile.Failure.PLATFORM -> OperationJournal.Reason.PLATFORM
                                else -> OperationJournal.Reason.UNSUPPORTED
                            }, error.javaClass.simpleName)
                        HookRuntime.event(FEATURE, "local export unavailable reason=" + error.reason.name)
                        when (error.reason) {
                            LocalVideoFile.Failure.MISSING -> R.string.offline_video_missing
                            LocalVideoFile.Failure.INCOMPLETE -> R.string.offline_video_incomplete
                            LocalVideoFile.Failure.UNSUPPORTED -> R.string.offline_video_unsupported
                            LocalVideoFile.Failure.TIMELINE -> R.string.offline_video_timeline_failed
                            LocalVideoFile.Failure.CODE_MISMATCH -> R.string.offline_video_code_mismatch
                            LocalVideoFile.Failure.PLATFORM -> R.string.offline_video_android_required
                            else -> R.string.offline_video_export_failed
                        }
                    } catch (error: LinkageError) {
                        operation.finish(OperationJournal.Result.UNAVAILABLE, OperationJournal.Reason.UNSUPPORTED, error.javaClass.simpleName)
                        HookRuntime.event(FEATURE, "local export adapter unavailable type=" + error.javaClass.simpleName)
                        R.string.offline_video_unsupported
                    } catch (error: Exception) {
                        operation.failed(error, !exportActive())
                        HookRuntime.event(FEATURE, "local export failed type=" + error.javaClass.simpleName)
                        R.string.offline_video_export_failed
                    } catch (error: OutOfMemoryError) {
                        operation.failed(error)
                        HookRuntime.event(FEATURE, "local export unavailable: insufficient sample memory")
                        R.string.offline_video_export_failed
                    } finally { busy.set(false) }
                    main.post { if (!life.closed) HostSettingsEntry.showMessage(message) }
                }
                HostSettingsEntry.showMessage(R.string.offline_video_saving)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                operation.finish(OperationJournal.Result.FAILED, OperationJournal.Reason.QUEUE_FULL)
                busy.set(false); HostSettingsEntry.showMessage(R.string.offline_video_busy)
            }
        }
        fun openComments(target: Binding): Boolean {
            if (!current(target)) return false
            val screen = target.activity.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return false
            val owner = target.owner.get() ?: return false
            val video = contract.read(owner)?.takeIf { it.id == target.id && it.offline } ?: return false
            val existing = comments
            if (existing?.isShowing == true) return true
            closeMenu()
            comments = OfflineCommentsDialog.show(screen, target.id, commentReader, commentNetwork, commentReplies, video.model,
                { current(target) }) { closed -> if (comments === closed) comments = null }
            return comments?.isShowing == true
        }
        val speedFeature = "OfflineHoldSpeedArbitration"
        HookRuntime.dependent(FEATURE, speedFeature)
        HookRuntime.install(speedFeature) {
            val speed = HoldSpeedContract(loader)
            module.deoptimize(speed.touch)
            module.deoptimize(speed.begin)
            module.trackHook(speedFeature, speed.touch, requiresConfiguration = false).intercept { chain ->
                val owner = chain.thisObject?.let { speed.owner.get(it) }
                val event = chain.args[1] as? MotionEvent
                val result = chain.proceed()
                val target = binding
                if (owner != null && event != null && Looper.myLooper() == main.looper) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && speedReady.get() && target != null && current(target)) {
                        val player = target.view.get(); val screen = target.activity.get()
                        val root = target.owner.get()?.let { contract.rootView.invoke(it) as? View }
                        val surface = chain.thisObject?.let { speed.view.get(it) as? View }
                        if (player != null && screen != null && root != null && surface != null &&
                            OfflineNativeHoldContract.belongs(surface, root, player, screen.window.decorView))
                            speedPress.candidate(owner, player, state.revision)
                    } else if (event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN)) speedPress.finish(owner)
                }
                result
            }
            module.trackHook(speedFeature, speed.begin, requiresConfiguration = false).intercept { chain ->
                val result = chain.proceed()
                chain.thisObject?.let { speedPress.began(it, speed.active.invoke(it) == true) }
                result
            }
            for (method in listOf(speed.finish, speed.rebound, speed.destroyed)) {
                module.trackHook(speedFeature, method, requiresConfiguration = false).intercept { chain ->
                    try { chain.proceed() } finally { chain.thisObject?.let(speedPress::finish) }
                }
            }
        }
        val entryFeature = "OfflineCommentButtonEntry"
        HookRuntime.dependent(FEATURE, entryFeature)
        HookRuntime.install(entryFeature) {
            val entry = OfflineCommentEntryContract(loader)
            module.trackHook(entryFeature, entry.click, requiresConfiguration = false).intercept { chain ->
                val clicked = chain.args[0] as? View
                val owner = chain.thisObject
                val target = binding
                val consume = if (clicked == null || owner == null || target == null ||
                    Looper.myLooper() != main.looper || !current(target) || !ConfigClient.isReady ||
                    !ConfigClient.getBoolean(KEY_ENTRY) || !entryReady.get()) false else try {
                    val bound = entry.current(owner, clicked)
                    val screen = target.activity.get()
                    if (bound != null && screen != null && OfflineCommentEntryPolicy.intercept(
                            ConfigClient.isReady && ConfigClient.getBoolean(KEY_ENTRY), entryReady.get(), target.id, bound.video,
                            target.offline, bound.offline, bound.root.rootView === screen.window.decorView &&
                                clicked.rootView === target.view.get()?.rootView,
                            clicked.isShown && clicked.isAttachedToWindow && bound.root.isShown && bound.root.isAttachedToWindow)) {
                        cancelTouch(); openComments(target)
                    } else false
                } catch (error: Exception) {
                    HookRuntime.event(entryFeature, "entry unavailable; native retained type=" + error.javaClass.simpleName); false
                } catch (error: LinkageError) {
                    HookRuntime.event(entryFeature, "entry adapter unavailable; native retained type=" + error.javaClass.simpleName); false
                }
                if (consume) null else chain.proceed()
            }
            val app = checkNotNull(HookRuntime.hostContext)
            worker.execute {
                val inspected = OfflineCommentEntryCodeGate.verify(app)
                if (!life.closed && HookRuntime.scope(entryFeature).active) entryReady.set(inspected)
                if (!inspected) HookRuntime.state(entryFeature, "代码不匹配，保留原生评论入口")
                HookRuntime.detail(entryFeature, if (inspected) "Inspected comment-button code ready; option and selected offline owner still required"
                    else "Unsupported comment-button code; native entry retained")
            }
        }
        fun showMenu(target: Binding): Boolean {
            if (!current(target)) return false
            val activity = target.activity.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return false
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
                                2 -> openComments(target)
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
            return shown.isShowing
        }
        val nativeFeature = "OfflineNativeHold"
        HookRuntime.dependent(FEATURE, nativeFeature)
        HookRuntime.install(nativeFeature) {
            val native = OfflineNativeHoldContract(loader)
            val ready = AtomicBoolean(false)
            module.trackHook(nativeFeature, native.run, requiresConfiguration = false).intercept { chain ->
                val target = binding
                if (!ready.get() || life.closed || !life.enabled || !life.longPress || !ConfigClient.isReady ||
                    !ConfigClient.getBoolean(KEY) || Looper.myLooper() != main.looper ||
                    target == null || !current(target)) return@intercept chain.proceed()
                val screen = target.activity.get()
                val owner = target.owner.get()
                val player = target.view.get()
                val source = chain.thisObject?.let(native::surface)
                val root = owner?.let { contract.rootView.invoke(it) as? View }
                if (screen == null || owner == null || player == null || source == null || root == null ||
                    screen.isFinishing || screen.isDestroyed || !screen.hasWindowFocus() ||
                    !OfflineNativeHoldContract.belongs(source, root, player, screen.window.decorView))
                    return@intercept chain.proceed()
                // Re-read metadata at the native timeout boundary; never consume an online/stale card.
                val fresh = contract.read(owner)
                if (fresh == null || !fresh.offline || fresh.id != target.id || fresh.view !== player)
                    return@intercept chain.proceed()
                if (dialog?.isShowing == true || comments?.isShowing == true) return@intercept chain.proceed()
                if (speedPress.owns(player, state.revision)) { cancelTouch(); return@intercept null }
                cancelTouch()
                if (!showMenu(target)) return@intercept chain.proceed()
                // Native hold is mature: drain its trailing up without a click or the disabled-action toast.
                gesture.claim(gesture.begin(0f, 0f))
                HookRuntime.count(nativeFeature, "已接管当前离线卡片的原生长按")
                HookRuntime.detail(FEATURE, "原生长按已成立，本地操作菜单已显示；未执行保存／导出")
                null
            }
            module.deoptimize(native.run)
            worker.execute {
                val checked = OfflineNativeHoldContract.verify(checkNotNull(HookRuntime.hostContext))
                speedReady.set(OfflineSpeedGestureCodeGate.verify(checkNotNull(HookRuntime.hostContext)))
                if (!life.closed && HookRuntime.scope(nativeFeature).active) ready.set(checked)
                HookRuntime.detail(nativeFeature, if (checked) "代码已核对，等待当前离线卡片原生长按"
                    else "原生长按代码不匹配，保留宿主行为")
            }
            HookRuntime.onDispose(nativeFeature) { ready.set(false) }
        }
        fun touch(activity: Activity, event: MotionEvent): Boolean {
            if (cancellingNative) return false
            if (event.actionMasked == MotionEvent.ACTION_DOWN && gesture.claimed) cancelTouch()
            if (gesture.claimed) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) cancelTouch()
                return true
            }
            val target = binding
            if (target == null || target.activity.get() !== activity || !current(target) || !ConfigClient.getBoolean(KEY) ||
                dialog?.isShowing == true || comments?.isShowing == true) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN)
                    HookRuntime.count(FEATURE, if (target == null) "长按无当前绑定" else if (!target.offline) "长按当前非离线卡片" else "长按当前窗口或会话不符")
                cancelTouch(); return false
            }
            val view = target.view.get() ?: run { cancelTouch(); return false }
            val slop = ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelTouch()
                    if (event.pointerCount != 1 || !videoHit(view, event.rawX, event.rawY) ||
                        !activity.hasWindowFocus()) {
                        HookRuntime.count(FEATURE, "保守命中未通过，等待原生长按回调")
                        return false
                    }
                    HookRuntime.count(FEATURE, "离线长按候选已命中")
                    val token = gesture.begin(event.rawX, event.rawY)
                    val down = event.downTime
                    val x = event.x; val y = event.y
                    val revision = state.revision
                    timer = Runnable {
                        timer = null
                        if (speedPress.owns(view, revision)) { cancelTouch(); return@Runnable }
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
                        HookRuntime.count(FEATURE, "离线长按已接管，取消原生禁用提示")
                        try { showMenu(target) } catch (error: Exception) {
                            HookRuntime.event(FEATURE, "local menu failed type=" + error.javaClass.simpleName)
                            cancelTouch()
                        }
                    }.also { main.postDelayed(it, maxOf(200L, ViewConfiguration.getLongPressTimeout().toLong() - 100L)) }
                }
                MotionEvent.ACTION_MOVE -> gesture.move(event.rawX, event.rawY, event.pointerCount, slop)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancelTouch()
            }
            return false
        }
        fun select(owner: Any) {
            if (life.closed || !life.enabled || Looper.myLooper() != main.looper) return
            val video = contract.read(owner) ?: run {
                HookRuntime.count(FEATURE, "卡片绑定缺少模型或播放器")
                cancelTouch(); closeMenu(); binding = null; state.select(owner, "", true); return
            }
            state.select(owner, video.id, video.offline)
            val activity = activity(video.view.context) ?: run { cancelTouch(); closeMenu(); binding = null; return }
            val previous = binding
            val changed = previous == null || previous.owner.get() !== owner || previous.id != video.id ||
                previous.offline != video.offline || previous.view.get() !== video.view || previous.activity.get() !== activity
            if (changed) {
                cancelTouch(); closeMenu(); binding = Binding(owner, video, activity)
                HookRuntime.count(FEATURE, if (video.offline) "已绑定选中离线卡片" else "已绑定选中在线卡片")
                if(video.offline)OfflineCommentDownloadHook.passive(video.model)
            }
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
            life.enabled = snapshot?.let { it.boolean(KEY) || it.boolean(KEY_ENTRY) } == true
            life.longPress = snapshot?.boolean(KEY) == true
            if (snapshot?.boolean(KEY) != true) { cancelTouch(); dialog?.dismiss(); dialog = null }
            if (!life.enabled) { cancelTouch(); closeMenu(); binding = null; state.clear() }
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
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
        val videoBounds = Rect()
        if (!video.getGlobalVisibleRect(videoBounds) || !videoBounds.contains(x.toInt(), y.toInt())) return false
        fun related(view: View): Boolean = view === video ||
            generateSequence(view.parent) { it.parent }.any { it === video } ||
            generateSequence(video.parent) { it.parent }.any { it === view }
        val queue = ArrayDeque<View>().apply { add(video.rootView) }
        var visited = 0
        while (queue.isNotEmpty()) {
            if (++visited > 1200) return false
            val view = queue.removeFirst()
            if (!view.isShown || !view.getGlobalVisibleRect(rect) || !rect.contains(x.toInt(), y.toInt())) continue
            if (!related(view) && (view.isClickable || view.isLongClickable || view is EditText)) {
                // Native full-surface gesture hosts are siblings of the player, not its ancestors.
                // Still inspect their children and reject buttons, text input and focused overlays.
                val surfaceHost = view is ViewGroup && view !is android.widget.AbsListView &&
                    !view.isFocusable && rect.contains(videoBounds)
                if (!surfaceHost || view is EditText) return false
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        return true
    }
}
