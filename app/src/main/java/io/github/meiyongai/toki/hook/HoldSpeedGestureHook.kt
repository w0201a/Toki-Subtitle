package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedModule

/** Opt into TikTok's own downward hold-speed lock mode, scoped to native edge-speed delegates only. */
internal object HoldSpeedGestureHook {
    const val KEY = "hold_speed_down_lock_enabled"
    private const val FEATURE = "HoldSpeedGestureHook"

    fun init(module: XposedModule, loader: ClassLoader) {
        val contract = HoldSpeedContract(loader)
        val main = Handler(Looper.getMainLooper())
        val mode = NativeSpeedGestureScope()
        val sessions = HoldSpeedSessions()
        var enabled = false
        var closed = false

        fun cancel(delegate: Any, session: HoldSpeedSessions.Session) {
            session.cancelled = true
            mode.enter {
                try {
                    val handler = contract.handler.invoke(delegate) as Handler
                    val runnable = contract.runnable.get(delegate) as Runnable
                    handler.removeCallbacks(runnable)
                    val owner = session.owner.get()
                    if (owner != null && contract.component.isInstance(owner) && contract.active.invoke(owner) == true) {
                        // Withdraw the pending down-threshold state, then finish without committing a lock toggle.
                        contract.pending.invoke(owner, false)
                        contract.finish.invoke(owner, true)
                    }
                } catch (error: Exception) {
                    HookRuntime.event(FEATURE, "native cancel unavailable type=" + error.javaClass.simpleName)
                } finally { sessions.remove(delegate) }
            }
        }
        fun cancelAll() { sessions.entries().forEach { (delegate, session) -> cancel(delegate, session) } }
        fun cancelOwner(owner: Any) {
            sessions.entries().filter { it.second.owner.get() === owner }.forEach { (delegate, session) -> cancel(delegate, session) }
        }
        HookRuntime.onDispose(FEATURE) {
            closed = true
            fun restore() { cancelAll(); sessions.clear() }
            if (Looper.myLooper() == main.looper) restore() else main.post { restore() }
        }
        module.trackHook(FEATURE, contract.downGate, requiresConfiguration = false).intercept { chain ->
            val native = chain.proceed() as Boolean
            if (mode.active) true else native
        }
        module.trackHook(FEATURE, contract.legacyGate, requiresConfiguration = false).intercept { chain ->
            val native = chain.proceed() as Boolean
            if (mode.active) false else native
        }
        module.trackHook(FEATURE, contract.threshold, requiresConfiguration = false).intercept { chain ->
            val native = chain.proceed() as Float
            if (!mode.active) native else {
                val source = contract.view.get(chain.thisObject) as View
                HoldSpeedThreshold.pixels(native, source.resources.displayMetrics.density,
                    ViewConfiguration.get(source.context).scaledTouchSlop.toFloat())
            }
        }
        for (method in listOf(contract.created, contract.attached)) {
            module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
                if (method == contract.created) chain.thisObject?.let(::cancelOwner)
                if (!closed && enabled && Looper.myLooper() == main.looper) mode.enter { chain.proceed() }
                else chain.proceed()
            }
        }
        module.trackHook(FEATURE, contract.begin, requiresConfiguration = false).intercept { chain ->
            val owner = chain.thisObject
            val session = owner?.let(sessions::forOwner)
            if (session == null) chain.proceed()
            else if (!closed && !session.cancelled && (session.view.get() as? View)?.isAttachedToWindow == true)
                mode.enter { chain.proceed() }
            else {
                if (owner != null) cancelOwner(owner)
                null // A delayed native runnable from our detached/cancelled session must not start speed-up off-screen.
            }
        }
        module.trackHook(FEATURE, contract.finish, requiresConfiguration = false).intercept { chain ->
            val owner = chain.thisObject
            val session = owner?.let(sessions::forOwner)
            if (session == null) return@intercept chain.proceed()
            try {
                mode.enter {
                    if (session.cancelled) chain.proceed(arrayOf(true)) else chain.proceed()
                }
            } finally { if (owner != null) sessions.removeOwner(owner) }
        }
        for (method in listOf(contract.rebound, contract.destroyed)) {
            module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
                chain.thisObject?.let(::cancelOwner)
                chain.proceed()
            }
        }
        module.trackHook(FEATURE, Activity::class.java.getDeclaredMethod("onPause"), requiresConfiguration = false).intercept { chain ->
            cancelAll()
            chain.proceed()
        }
        module.trackHook(FEATURE, contract.touch, requiresConfiguration = false).intercept { chain ->
            if (closed || Looper.myLooper() != main.looper) return@intercept chain.proceed()
            val delegate = checkNotNull(chain.thisObject)
            val event = chain.args[1] as? MotionEvent ?: return@intercept chain.proceed()
            val owner = contract.owner.get(delegate)
            val view = contract.view.get(delegate) as? View
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                sessions[delegate]?.let { cancel(delegate, it) }
                if (enabled && event.pointerCount == 1 && owner != null && contract.component.isInstance(owner) &&
                    view?.isAttachedToWindow == true) {
                    sessions.begin(delegate, owner, view)?.let { session ->
                        val detach = object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(view: View) = Unit
                            override fun onViewDetachedFromWindow(view: View) { cancel(delegate, session) }
                        }
                        view.addOnAttachStateChangeListener(detach)
                        session.cleanup = AutoCloseable { view.removeOnAttachStateChangeListener(detach) }
                    }
                }
            }
            val session = sessions[delegate] ?: return@intercept chain.proceed()
            if (owner == null || session.owner.get() !== owner || session.view.get() !== view || view?.isAttachedToWindow != true) {
                cancel(delegate, session)
                return@intercept chain.proceed()
            }
            val multiTouch = event.actionMasked == MotionEvent.ACTION_POINTER_DOWN || event.pointerCount != 1
            if (multiTouch || event.actionMasked == MotionEvent.ACTION_CANCEL) session.cancelled = true
            try {
                mode.enter {
                    if (multiTouch) {
                        val cancelEvent = MotionEvent.obtain(event)
                        try {
                            cancelEvent.action = MotionEvent.ACTION_CANCEL
                            contract.pending.invoke(owner, false)
                            chain.proceed(arrayOf(chain.args[0], cancelEvent))
                        } finally { cancelEvent.recycle() }
                    } else {
                        if (session.cancelled) contract.pending.invoke(owner, false)
                        chain.proceed()
                    }
                }
            } finally {
                if (multiTouch || event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)
                    sessions.remove(delegate)
            }
        }
        HookRuntime.subscribe(FEATURE) { snapshot ->
            val next = snapshot?.boolean(KEY) == true
            if (!next && enabled) cancelAll()
            enabled = next
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
        }
        HookRuntime.event(FEATURE, "native downward lock mode registered; ordinary swipes and experiments unchanged outside owned calls")
    }
}
