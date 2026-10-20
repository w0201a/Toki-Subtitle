package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.app.Application
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.R
import java.util.WeakHashMap

internal object HostSettingsEntry : Application.ActivityLifecycleCallbacks {
    private lateinit var module: XposedModule
    private lateinit var resources: ModuleResources
    private val hooked = mutableSetOf<java.lang.reflect.Method>()
    private val pages = WeakHashMap<Activity, Page>()
    private val main = Handler(Looper.getMainLooper())
    private var dialog: HostSettingsDialog? = null
    private var dialogActivity: Activity? = null
    private var target: View? = null
    private var downX = 0f
    private var downY = 0f
    private var opened = false
    private val hold = Runnable {
        val view = target ?: return@Runnable
        val activity = pages.keys.firstOrNull { it.window.decorView === view.rootView } ?: return@Runnable
        opened = true
        // End the host gesture before opening the window; no click follows the hold.
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        activity.window.decorView.dispatchTouchEvent(cancel); cancel.recycle()
        open(activity)
    }
    fun attach(app: Application, module: XposedModule, resources: ModuleResources) {
        this.module = module; this.resources = resources
        app.registerActivityLifecycleCallbacks(this)
    }
    fun openSubtitle(activity: Activity) = open(activity, subtitleOnly = true)
    fun open(activity: Activity, subtitleOnly: Boolean = false) {
        if (!::resources.isInitialized || activity.isFinishing || activity.isDestroyed) return
        if (dialog?.isShowing == true && dialogActivity === activity) return
        dialog?.dismiss()
        dialogActivity = activity
        try {
            dialog = HostSettingsDialog(activity, resources, subtitleOnly).also { window ->
                window.setOnDismissListener { if (dialog === window) { dialog = null; dialogActivity = null } }
                window.show()
            }
        } catch (error: Exception) {
            dialog?.dismiss(); dialog = null; dialogActivity = null
            HookRuntime.event("HostSettings", "window unavailable: ${error.javaClass.simpleName}")
            android.widget.Toast.makeText(activity, "Toki 设置暂时无法打开", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    internal fun homeLabel(view: View): Boolean {
        val labels = listOf(view.contentDescription?.toString(), (view as? TextView)?.text?.toString())
        return labels.any { it?.substringBefore(',')?.trim()?.lowercase() in setOf("home", "首页", "首頁", "ホーム", "beranda", "inicio", "accueil") }
    }
    internal fun homeAt(activity: Activity, x: Float, y: Float): View? {
        val root = activity.window.decorView
        val bounds = Rect(); root.getGlobalVisibleRect(bounds)
        // Only the bottom navigation band, never a video caption or a profile label.
        if (y < bounds.bottom - 96 * root.resources.displayMetrics.density) return null
        val navTop = bounds.bottom - 96 * root.resources.displayMetrics.density
        val maxWidth = bounds.width() / 3
        val queue = ArrayDeque<View>();queue.add(root);var visited = 0
        val candidates = mutableListOf<View>()
        while (queue.isNotEmpty() && visited++ < 1200) {
            val view = queue.removeFirst()
            if (!view.isShown || !view.getGlobalVisibleRect(bounds) || bounds.bottom < navTop) continue
            if (homeLabel(view)) {
                var candidate: View? = view
                repeat(4) {
                    val item = candidate ?: return@repeat
                    if (item.getGlobalVisibleRect(bounds) && bounds.top >= navTop && bounds.width() <= maxWidth &&
                        bounds.contains(x.toInt(),y.toInt())) candidates.add(item)
                    candidate = item.parent as? View
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        val leaves = candidates.distinct().filter { candidate -> candidates.none { other ->
            other !== candidate && generateSequence(other.parent) { it.parent }.any { it === candidate }
        } }
        return leaves.singleOrNull()
    }
    private fun touch(activity: Activity, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                main.removeCallbacks(hold);opened = false
                target = homeAt(activity, event.rawX, event.rawY)
                downX = event.rawX;downY = event.rawY
                if (target != null) main.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.rawX-downX) > ViewConfiguration.get(activity).scaledTouchSlop ||
                kotlin.math.abs(event.rawY-downY) > ViewConfiguration.get(activity).scaledTouchSlop) { main.removeCallbacks(hold);target = null }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                main.removeCallbacks(hold);target = null
                val consumed = opened;opened = false;return consumed
            }
        }
        return opened
    }
    internal fun settingsHeading(value: String?): Boolean = value?.trim()?.lowercase()
        ?.replace("&", "and")?.replace(Regex("\\s+"), " ") in setOf(
            "settings and privacy", "设置和隐私", "设置与隐私", "設定與隱私", "設定和隱私", "設定とプライバシー")
    private class Page(val activity: Activity) : ViewTreeObserver.OnGlobalLayoutListener {
        private var entry: TextView? = null
        private var disposed = false
        private var pending = false
        private val check = Runnable { pending = false; if (!disposed) scan() }
        override fun onGlobalLayout() {
            if (!disposed && !pending) { pending = true; main.postDelayed(check, 250) }
        }
        private fun scan() {
            val root = activity.window.decorView as? ViewGroup ?: return
            val queue = ArrayDeque<View>();queue.add(root);var visited = 0
            var settings = false
            while(queue.isNotEmpty() && visited++ < 1000) {
                val view=queue.removeFirst()
                if(view === entry || !view.isShown) continue
                val label=(view as? TextView)?.text?.toString()?.trim()?.lowercase()
                if(settingsHeading(label)) settings=true
                if(view is ViewGroup) for(i in 0 until view.childCount) queue.add(view.getChildAt(i))
            }
            if(settings && entry==null) {
                entry=TextView(resources.createActivityContext(activity)).apply {
                    text=context.getString(R.string.host_settings_title);textSize=16f
                    setTextColor(android.graphics.Color.WHITE);setBackgroundColor(0xEE303440.toInt())
                    setPadding(24,16,24,16);elevation=24f;setOnClickListener { open(activity) }
                }
                root.addView(entry, android.widget.FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply { bottomMargin=96;rightMargin=20 })
            } else if(!settings) removeEntry()
        }
        fun dispose() { disposed = true; main.removeCallbacks(check); removeEntry() }
        fun removeEntry() { entry?.let { (it.parent as? ViewGroup)?.removeView(it) };entry=null }
    }
    override fun onActivityResumed(activity: Activity) {
        val method=activity.javaClass.getMethod("dispatchTouchEvent", MotionEvent::class.java)
        if(hooked.add(method)) HookRuntime.install("HostSettingsEntry:${method.declaringClass.name}") {
            module.trackHook("HostSettingsEntry:${method.declaringClass.name}",method).intercept { chain ->
                val screen=chain.thisObject as Activity
                if(pages.containsKey(screen) && touch(screen,chain.args[0] as MotionEvent)) true else chain.proceed()
            }
        }
        val page=Page(activity);pages[activity]=page
        activity.window.decorView.viewTreeObserver.addOnGlobalLayoutListener(page)
        page.onGlobalLayout()
    }
    override fun onActivityPaused(activity: Activity) {
        pages.remove(activity)?.let { activity.window.decorView.viewTreeObserver.removeOnGlobalLayoutListener(it);it.dispose() }
        main.removeCallbacks(hold);target=null;opened=false
        if(dialogActivity===activity) dialog?.dismiss()
    }
    override fun onActivityDestroyed(activity: Activity) = onActivityPaused(activity)
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
