package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference

/** 在宿主前台活动中显示独立 Material 3 窗口，使用模块资源和独立生命周期。 */
internal class HostScanController private constructor(
    private val moduleResources: ModuleResources,
) : Application.ActivityLifecycleCallbacks {
    companion object {
        val session = HostScanSession()
        private var controller: HostScanController? = null

        /**
         * 在宿主主进程订阅活动生命周期，窗口随前台活动释放。
         * @param app 当前宿主Application。
         * @param moduleResources 框架提供的模块 APK 资源来源。
         * @return Unit。
         * Callers: TokiModule.hookApplication。
         */
        fun attach(app: Application, moduleResources: ModuleResources) {
            if (controller != null) return
            controller = HostScanController(moduleResources).also {
                app.registerActivityLifecycleCallbacks(it)
                session.onChanged = it::scheduleRefresh
            }
        }

        /** 扫描结束后通知主线程更新结果提示。@return Unit。Callers: HostSymbols 扫描线程。 */
        fun reopenProgress() { Handler(Looper.getMainLooper()).post { controller?.reopen() } }

        fun onScanFinished() {
            Handler(Looper.getMainLooper()).post { controller?.render() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var foreground = WeakReference<Activity>(null)
    private var dialog: HostScanDialog? = null
    private var backgroundMode = false
    private var badge: android.widget.TextView? = null
    private var badgeHost: android.view.ViewGroup? = null
    private var notifiedPhase: HostScanPhase? = null

    private val refreshQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun scheduleRefresh() {
        if (refreshQueued.compareAndSet(false, true)) main.post { refreshQueued.set(false); refresh.run() }
    }

    private fun dismissBadge() {
        badge?.let { badgeHost?.removeView(it) }; badge = null; badgeHost = null
    }

    private fun renderBadge(activity: Activity, state: HostScanStatus) {
        val host = activity.window.decorView as? android.view.ViewGroup ?: return
        if (badgeHost !== host) dismissBadge()
        if (badge == null) {
            val context = moduleResources.createActivityContext(activity)
            badge = android.widget.TextView(context).apply {
                setTextColor(android.graphics.Color.WHITE); textSize = 13f
                setPadding(20, 12, 20, 12)
                background = android.graphics.drawable.GradientDrawable().apply { setColor(0xE6303440.toInt()); cornerRadius = 24f }
                elevation = 20f
                setOnClickListener { backgroundMode = false; dismissBadge(); render(); scheduleRefresh() }
            }
            badgeHost = host
            host.addView(badge, android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.TOP or android.view.Gravity.END).apply {
                topMargin = host.rootWindowInsets?.systemWindowInsetTop ?: 36
                rightMargin = 12
            })
        }
        badge!!.text = "Toki · " + HostScanPresentation.summary(moduleResources.createActivityContext(activity), state) + "  ›"
        badge!!.contentDescription = badge!!.text.toString() + "，点击查看适配详情"
    }
    private val refresh = object : Runnable {
        /** 更新可见窗口；无前台活动时停止刷新。@return Unit。Callers: 主线程Handler。 */
        override fun run() {
            render()
            val phase = session.status.phase
            if (foreground.get() != null && phase in setOf(HostScanPhase.SCANNING, HostScanPhase.EXTENSIONS, HostScanPhase.SAVING)) {
                main.postDelayed(this, 500)
            }
        }
    }

    /**
     * 更新当前活动的模态窗口，后台扫描不会抢占其他应用前台。
     * @return Unit。
     * Callers: refresh、onActivityResumed。
     */
    private fun render() {
        val activity = foreground.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        val state = session.status
        if (state.phase == HostScanPhase.IDLE) { dismissDialog(); dismissBadge(); return }
        if (state.phase in setOf(HostScanPhase.SCANNING, HostScanPhase.EXTENSIONS, HostScanPhase.SAVING)) notifiedPhase = null
        if (backgroundMode) {
            renderBadge(activity, state)
            if (state.phase in setOf(HostScanPhase.READY, HostScanPhase.PARTIAL, HostScanPhase.FAILED) && notifiedPhase != state.phase) {
                val text = when (state.phase) {
                    HostScanPhase.READY -> io.github.meiyongai.toki.subtitle.R.string.host_scan_background_complete
                    HostScanPhase.PARTIAL -> io.github.meiyongai.toki.subtitle.R.string.host_scan_background_partial
                    else -> io.github.meiyongai.toki.subtitle.R.string.host_scan_background_failed
                }
                android.widget.Toast.makeText(moduleResources.createActivityContext(activity), text, android.widget.Toast.LENGTH_LONG).show()
                notifiedPhase = state.phase
            }
            return
        }
        dismissBadge()
        if (dialog == null) {
            dialog = HostScanDialog(activity, moduleResources, { session.dismissResult(); dismissDialog() }, {
                backgroundMode = true
                dismissDialog()
                android.widget.Toast.makeText(moduleResources.createActivityContext(activity), io.github.meiyongai.toki.subtitle.R.string.host_scan_background_hint, android.widget.Toast.LENGTH_LONG).show()
                render()
                scheduleRefresh()
            }).also { it.render(state); it.show() }
        }
        dialog!!.render(state)
    }

    fun reopen() {
        if (session.status.phase != HostScanPhase.IDLE) { backgroundMode = false; render(); scheduleRefresh() }
    }

    /** 释放窗口，保留未确认的扫描结果。@return Unit。Callers: 生命周期、结果确认、render。 */
    private fun dismissDialog() {
        main.removeCallbacks(refresh)
        dialog?.dismiss()
        dialog = null
    }

    /** 活动进入前台后恢复窗口。@param activity 宿主活动。@return Unit。Callers: Android Application。 */
    override fun onActivityResumed(activity: Activity) {
        dismissDialog()
        dismissBadge()
        foreground = WeakReference(activity)
        main.post(refresh)
    }

    /** 活动离开前台时释放窗口。@param activity 宿主活动。@return Unit。Callers: Android Application。 */
    override fun onActivityPaused(activity: Activity) {
        if (foreground.get() === activity) { dismissDialog(); dismissBadge(); foreground.clear() }
    }

    /** 销毁活动时清理引用。@param activity 宿主活动。@return Unit。Callers: Android Application。 */
    override fun onActivityDestroyed(activity: Activity) {
        if (foreground.get() === activity) { dismissDialog(); dismissBadge(); foreground.clear() }
    }

    /** 创建阶段不显示窗口。@param activity 活动。@param savedInstanceState 保存状态。@return Unit。Callers: Android Application。 */
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    /** 启动阶段等待恢复回调。@param activity 活动。@return Unit。Callers: Android Application。 */
    override fun onActivityStarted(activity: Activity) = Unit
    /** 暂停阶段已完成窗口释放。@param activity 活动。@return Unit。Callers: Android Application。 */
    override fun onActivityStopped(activity: Activity) = Unit
    /** 扫描状态属于进程，不写入活动状态。@param activity 活动。@param outState 保存状态。@return Unit。Callers: Android Application。 */
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
