package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Window
import android.view.WindowManager
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import io.github.meiyongai.toki.subtitle.provider.ExecutionEvidence

/** Paired window and native-panel updates, admitted only after the inspected 47.6.3 code gate. */
internal object ImmersiveLiveRuntime {
    private const val FEATURE = "ImmersiveFullScreenHook"
    private var updateStatus: (() -> Unit)? = null
    fun refreshStatus() { updateStatus?.invoke() }
    fun install(module: XposedModule, loader: ClassLoader, feed: Class<*>, screen: Class<*>,
        mainActivity: Class<*>, bottom: ImmersiveFullScreenHook.ViewContract) {
        check(ImmersiveLayoutPreparation.supported)
        val panel = Class.forName(ImmersiveLayoutPreparation.PANEL, false, loader)
        val contract = ImmersiveScreenBindings.Contract(panel, feed, screen)
        val main = Handler(Looper.getMainLooper())
        var failures = 0
        val failed: (Exception) -> Unit = { failures++; HookRuntime.event(FEATURE, "hot restore/refresh unavailable type=${it.javaClass.simpleName}") }
        val windows = ImmersiveWindowBindings(failed)
        val screens = ImmersiveScreenBindings(contract, failed)
        val bottomOwner = bottom.bottomView.declaringClass.getDeclaredField("LL").apply {
            check(java.lang.reflect.Modifier.isStatic(modifiers) && type == bottom.bottomView.declaringClass)
            isAccessible = true
        }
        var configuration: ConfigSnapshot? = null
        var closed = false
        fun visible(root: View) = root.isAttachedToWindow && root.isShown && root.width > 0 && root.height > 0 && root.getGlobalVisibleRect(Rect())
        fun updateWindows(wanted: Boolean) {
            windows.configure(wanted)
            HostSettingsEntry.activeActivities().filter { mainActivity.isInstance(it) }.forEach(windows::observe)
            bottomOwner.get(null)?.let { bottom.bottomView.invoke(it) as? View }?.let(windows::background)
        }
        val layoutRefresh = ImmersiveRefreshPlan(ImmersiveLayoutPreparation::targets, ::visible,
            ImmersiveLayoutPreparation::current, ::updateWindows, screens::restoreCaches,
            { owner -> contract.refresh.invoke(owner) }, contract::invalidate, failed)
        val refresh = Runnable {
            if (closed) return@Runnable
            val config = configuration
            val wanted = config?.boolean(ImmersiveFullScreenHook.KEY_IMMERSIVE_FULL_SCREEN) == true
            val beforeFailures = failures
            val layout = layoutRefresh.refresh(wanted)
            val stats = windows.result()
            val outcome = when {
                config == null -> ExecutionEvidence.Outcome.CONFIG_MISSING
                failures > beforeFailures || layout.deferred > 0 -> ExecutionEvidence.Outcome.PARTIAL
                !wanted -> ExecutionEvidence.Outcome.DISABLED
                layout.commands == 0 && stats.known == 0 -> ExecutionEvidence.Outcome.NO_TARGET
                else -> ExecutionEvidence.Outcome.COMMANDS_RETURNED
            }
            HookRuntime.execution(FEATURE, ExecutionEvidence(ExecutionEvidence.Scope.IMMERSIVE,
                if (wanted) ExecutionEvidence.Action.APPLY else ExecutionEvidence.Action.RELEASE, outcome,
                config?.revision, config?.boolean(ImmersiveFullScreenHook.KEY_IMMERSIVE_FULL_SCREEN), mapOf(
                    ExecutionEvidence.Metric.KNOWN to stats.known.toLong(), ExecutionEvidence.Metric.COMMANDS to layout.commands.toLong(),
                    ExecutionEvidence.Metric.FAILED to (failures - beforeFailures).toLong(),
                    ExecutionEvidence.Metric.DEFERRED to layout.deferred.toLong())))
            if (config != null) HookRuntime.appliedConfiguration(FEATURE, config, wanted && failures == beforeFailures && layout.panels > 0)
        }
        fun schedule() { main.removeCallbacks(refresh); main.post(refresh) }
        module.trackHook(FEATURE, contract.apply, requiresConfiguration = false).intercept { chain ->
            val input = chain.args[0]
            val owner = chain.thisObject
            if (closed || owner == null || input == null || Looper.myLooper() != main.looper) return@intercept chain.proceed()
            val args = chain.args.toTypedArray()
            args[0] = screens.project(owner, input, layoutRefresh.active)
            val result = chain.proceed(args)
            layoutRefresh.panelApplied(owner, screens.original(checkNotNull(args[0])) !== args[0])
            result
        }
        val phoneWindow = Class.forName("com.android.internal.policy.PhoneWindow", false, Window::class.java.classLoader)
        val setters = linkedMapOf("setStatusBarColor" to ImmersiveWindowBindings.Property.STATUS,
            "setNavigationBarColor" to ImmersiveWindowBindings.Property.NAVIGATION)
        if (Build.VERSION.SDK_INT >= 29) {
            setters["setStatusBarContrastEnforced"] = ImmersiveWindowBindings.Property.STATUS_CONTRAST
            setters["setNavigationBarContrastEnforced"] = ImmersiveWindowBindings.Property.NAVIGATION_CONTRAST
        }
        if (Build.VERSION.SDK_INT >= 30) setters["setDecorFitsSystemWindows"] = ImmersiveWindowBindings.Property.FITS
        for ((name, property) in setters) {
            val argument = if (property in setOf(ImmersiveWindowBindings.Property.STATUS, ImmersiveWindowBindings.Property.NAVIGATION))
                Int::class.javaPrimitiveType else Boolean::class.javaPrimitiveType
            module.trackHook(FEATURE, phoneWindow.getMethod(name, argument), requiresConfiguration = false).intercept { chain ->
                if (Looper.myLooper() != main.looper) return@intercept chain.proceed()
                val args = chain.args.toTypedArray()
                args[0] = windows.native(chain.thisObject as Window, property, args[0]!!)
                chain.proceed(args)
            }
        }
        module.trackHook(FEATURE, phoneWindow.getMethod("setAttributes", WindowManager.LayoutParams::class.java), requiresConfiguration = false).intercept { chain ->
            if (Looper.myLooper() != main.looper) return@intercept chain.proceed()
            val args = chain.args.toTypedArray()
            args[0] = windows.nativeAttributes(chain.thisObject as Window, args[0] as WindowManager.LayoutParams)
            chain.proceed(args)
        }
        module.trackHook(FEATURE, View::class.java.getMethod("setSystemUiVisibility", Int::class.javaPrimitiveType), requiresConfiguration = false).intercept { chain ->
            if (Looper.myLooper() != main.looper) return@intercept chain.proceed()
            val args = chain.args.toTypedArray(); args[0] = windows.nativeFlags(chain.thisObject as View, args[0] as Int)
            chain.proceed(args)
        }
        module.trackHook(FEATURE, View::class.java.getMethod("setBackground", Drawable::class.java), requiresConfiguration = false).intercept { chain ->
            if (Looper.myLooper() != main.looper) return@intercept chain.proceed()
            val args = chain.args.toTypedArray(); args[0] = windows.nativeBackground(chain.thisObject as View, args[0] as? Drawable)
            chain.proceed(args)
        }
        module.trackHook(FEATURE, bottom.bottomView, requiresConfiguration = false).intercept { chain ->
            val view = chain.proceed() as? View
            if (!closed && view != null && Looper.myLooper() == main.looper) windows.background(view)
            view
        }
        for (method in listOf("onPostResume", "onDestroy")) module.trackHook(FEATURE,
            Activity::class.java.getDeclaredMethod(method).apply { isAccessible = true }, requiresConfiguration = false).intercept { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as Activity
            if (!closed && mainActivity.isInstance(activity) && Looper.myLooper() == main.looper) {
                if (method == "onDestroy") windows.release(activity) else { windows.observe(activity); layoutRefresh.invalidate(); schedule() }
            }
            result
        }
        HookRuntime.own(FEATURE, ImmersiveLayoutPreparation.subscribe { layoutRefresh.invalidate(); schedule() })
        HookRuntime.onDispose(FEATURE) {
            fun restore() {
                if (closed) return
                configuration = null
                layoutRefresh.invalidate(); layoutRefresh.refresh(false)
                screens.restoreCaches(); windows.close(); main.removeCallbacks(refresh); closed = true
                updateStatus = null
            }
            if (Looper.myLooper() == main.looper) restore() else main.post { restore() }
        }
        HookRuntime.subscribe(FEATURE) { config ->
            configuration = config
            schedule()
        }
        updateStatus = { if (Looper.myLooper() == main.looper) schedule() else main.post { schedule() } }
    }
}
