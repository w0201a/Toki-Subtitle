package io.github.meiyongai.toki.subtitle.hook

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.model.SharePanelItem
import io.github.meiyongai.toki.subtitle.model.SharePanelPolicy
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Narrow share adapter contracts inspected in TikTok 47.6.3; never hooks generic List/RecyclerView data globally. */
internal object SharePanelCleanupHook {
    private const val FEATURE = "SharePanelCleanupHook"
    private const val RECIPIENTS = "SharePanelRecipients"

    internal fun adapterType(recycler: Class<*>): Class<*> = recycler.getMethod("getAdapter").returnType.also {
        check(it != Any::class.java && it != Void.TYPE && !it.isPrimitive)
    }

    internal fun dataMethod(type: Class<*>, expectedBase: Class<*>): Method {
        check(expectedBase.isAssignableFrom(type))
        return type.getMethod("setData", List::class.java).apply {
            check(returnType == Void.TYPE && !Modifier.isAbstract(modifiers) && !Modifier.isStatic(modifiers))
            isAccessible = true
        }
    }

    fun init(module: XposedModule, loader: ClassLoader) {
        val actionBase = loader.loadClass("X.0GBx")
        val channelBase = loader.loadClass("X.0GDJ")
        val actionItem = loader.loadClass("X.0Fzx")
        val channelItem = loader.loadClass("X.0Fzt")
        val recycler = loader.loadClass("androidx.recyclerview.widget.RecyclerView")
        // TikTok keeps RecyclerView named but obfuscates its nested Adapter (47.6.3: X.00IC).
        val getAdapter = recycler.getMethod("getAdapter")
        val adapter = adapterType(recycler)
        check(adapter != Any::class.java && adapter.isAssignableFrom(actionBase) && adapter.isAssignableFrom(channelBase))
        check(actionItem.isInterface && channelItem.isInterface)
        val actionKey = actionItem.getMethod("key").apply { check(returnType == String::class.java) }
        val channelKey = channelItem.getMethod("key").apply { check(returnType == String::class.java) }
        listOf(actionBase, channelBase).forEach { base ->
            check(base.getMethod("setData", List::class.java).returnType == Void.TYPE)
            check(base.declaredConstructors.isNotEmpty())
        }
        val panel = loader.loadClass("com.ss.android.ugc.aweme.share.core.ui.SkeletonSharePanelFragment")
        val created = panel.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
        val destroyed = panel.getMethod("onDestroyView")
        val main = Handler(Looper.getMainLooper())
        val methods = mutableSetOf<Method>()
        val panels = WeakHashMap<Any, List<WeakReference<Any>>>()
        val bindings = ShareListBindings(keyOf = { item ->
            try {
                when {
                    actionItem.isInstance(item) -> actionKey.invoke(item) as? String
                    channelItem.isInstance(item) -> channelKey.invoke(item) as? String
                    else -> null
                }
            } catch (_: Exception) { null } // An unknown/unreadable item is kept, never guessed from its visible label.
        }, render = { owner, values ->
            val base = if (actionBase.isInstance(owner)) actionBase else channelBase
            dataMethod(owner.javaClass, base).invoke(owner, values)
        }, report = { HookRuntime.event(FEATURE, it) })
        HookRuntime.onDispose(FEATURE) {
            fun restore() {
                bindings.close()
                panels.clear()
                synchronized(methods) { methods.clear() }
            }
            if (Looper.myLooper() == main.looper) restore() else main.post { restore() }
        }

        fun register(owner: Any) {
            val base = if (actionBase.isInstance(owner)) actionBase else channelBase
            val method = dataMethod(owner.javaClass, base)
            synchronized(methods) {
                if (!methods.add(method)) return
                module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
                    val source = chain.args[0] as? List<*> ?: run {
                        val result = chain.proceed()
                        chain.thisObject?.let(bindings::forget)
                        return@intercept result
                    }
                    val filtered = bindings.incoming(checkNotNull(chain.thisObject), source)
                    if (filtered === source) chain.proceed() else chain.proceed(arrayOf(filtered))
                }
            }
        }
        for (base in listOf(actionBase, channelBase)) for (constructor in base.declaredConstructors) {
            module.trackHook(FEATURE, constructor, requiresConfiguration = false).intercept { chain ->
                val result = chain.proceed()
                register(checkNotNull(chain.thisObject))
                result
            }
        }
        module.trackHook(FEATURE, created, requiresConfiguration = false).intercept { chain ->
            val result = chain.proceed()
            val queue = ArrayDeque<View>()
            queue.add(chain.args[0] as View)
            val owners = mutableListOf<WeakReference<Any>>()
            var visited = 0
            while (queue.isNotEmpty() && visited++ < 1600) {
                val view = queue.removeFirst()
                if (recycler.isInstance(view)) {
                    val current = getAdapter.invoke(view)
                    if (current != null && (actionBase.isInstance(current) || channelBase.isInstance(current))) {
                        register(current)
                        owners.add(WeakReference(current))
                    }
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) queue.add(view.getChildAt(index))
            }
            panels[checkNotNull(chain.thisObject)] = owners
            result
        }
        module.trackHook(FEATURE, destroyed, requiresConfiguration = false).intercept { chain ->
            try { chain.proceed() } finally {
                if (panel.isInstance(chain.thisObject)) panels.remove(chain.thisObject)?.forEach { it.get()?.let(bindings::forget) }
            }
        }
        HookRuntime.subscribe(FEATURE) { snapshot ->
            bindings.configure(SharePanelPolicy.hiddenKeys(snapshot))
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
        }
        HookRuntime.dependent(FEATURE, RECIPIENTS)
        HookRuntime.install(RECIPIENTS) {
            val type = loader.loadClass("com.ss.android.ugc.aweme.internalshare.impl.refactor.assem.SharePanelContactRootAssem")
            val method = type.getMethod("onViewCreated", View::class.java)
            val owner = CleanViewGate.Owner(hiddenVisibility = View.GONE)
            HostViewVisibility.acquire(module, RECIPIENTS)
            HookRuntime.onDispose(RECIPIENTS) {
                if (Looper.myLooper() == main.looper) HostViewVisibility.gate.detach(owner)
                else main.post { HostViewVisibility.gate.detach(owner) }
            }
            module.trackHook(RECIPIENTS, method, requiresConfiguration = false).intercept { chain ->
                val result = chain.proceed()
                if (type.isInstance(chain.thisObject)) {
                    val view = chain.args[0] as View
                    HostViewVisibility.gate.bind(owner, view)
                    HostViewVisibility.gate.refresh(view)
                }
                result
            }
            HookRuntime.subscribe(RECIPIENTS) { snapshot ->
                owner.clean = snapshot?.boolean(SharePanelItem.RECIPIENTS.key) == true
                HostViewVisibility.gate.refresh()
            }
        }
    }
}
