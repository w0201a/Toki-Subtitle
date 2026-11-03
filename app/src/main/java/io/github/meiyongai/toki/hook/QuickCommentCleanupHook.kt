package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.model.LayoutElement
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Independent of top/bottom navigation adaptation: hides only inspected comment-input component roots. */
internal object QuickCommentCleanupHook {
    private const val FEATURE = "QuickCommentCleanupHook"
    internal val componentNames = listOf(
        "com.ss.android.ugc.aweme.feed.assem.quickcomment.VideoQuickCommentAssem",
        "com.ss.android.ugc.aweme.feed.assem.quickcomment.ZeroCommentQuickCommentAssem",
        "com.ss.android.ugc.aweme.feed.assem.quickcomment.HelpPostQuickCommentAssem",
        "com.ss.android.ugc.aweme.comment.detailpage.bottombarv2.assem.NewBottomDefaultCommentInputAssem",
    )
    internal class Contract(val type: Class<*>) {
        val created: Method = type.getMethod("onViewCreated", View::class.java).apply {
            check(returnType == Void.TYPE && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        val attached: Method? = if (type.name.endsWith(".NewBottomDefaultCommentInputAssem")) {
            type.getDeclaredMethod("sq", ViewGroup::class.java).apply {
                check(returnType == Void.TYPE && !Modifier.isStatic(modifiers)); isAccessible = true
            }
        } else null
        val views: List<Method> = listOf("getContainerView", "getContentView").mapNotNull { name ->
            try { type.getMethod(name).apply {
                check(View::class.java.isAssignableFrom(returnType) && !Modifier.isStatic(modifiers)); isAccessible = true
            } } catch (_: ReflectiveOperationException) { null }
        }
    }

    fun init(module: XposedModule, loader: ClassLoader) {
        val contracts = componentNames.mapNotNull { name ->
            try { Contract(loader.loadClass(name)) } catch (error: Exception) {
                HookRuntime.event(FEATURE, "component unavailable " + name + " type=" + error.javaClass.simpleName)
                null
            } catch (error: LinkageError) {
                HookRuntime.event(FEATURE, "component unavailable " + name + " type=" + error.javaClass.simpleName)
                null
            }
        }
        check(contracts.isNotEmpty()) { "No verified quick-comment binding available" }
        val main = Handler(Looper.getMainLooper())
        val gate = HostViewVisibility.gate
        val owner = CleanViewGate.Owner(hiddenVisibility = View.GONE)
        val tracked = WeakHashMap<View, Unit>()
        var closed = false
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                if (!closed) { gate.bind(owner, view); gate.refresh(view) }
            }
            override fun onViewDetachedFromWindow(view: View) { gate.unbind(owner, view) }
        }
        HostViewVisibility.acquire(module, FEATURE)
        fun bind(instance: Any, root: View?, contract: Contract) {
            if (closed || root == null || Looper.myLooper() != main.looper) return
            val targets = linkedSetOf(root)
            contract.views.forEach { getter ->
                try { (getter.invoke(instance) as? View)?.let { targets.add(it) } } catch (_: Exception) { }
            }
            targets.forEach { view ->
                if (view !in tracked && tracked.size >= 384) return@forEach
                if (view !in tracked) view.addOnAttachStateChangeListener(detach)
                tracked[view] = Unit
                gate.bind(owner, view)
                gate.refresh(view)
            }
        }
        for (method in contracts.flatMap { listOfNotNull(it.created, it.attached) }.distinct()) {
            module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
                val result = chain.proceed()
                val instance = chain.thisObject ?: return@intercept result
                val contract = contracts.firstOrNull { it.type.isInstance(instance) } ?: return@intercept result
                bind(instance, chain.args[0] as? View, contract)
                result
            }
        }
        HookRuntime.subscribe(FEATURE) { snapshot ->
            owner.clean = snapshot?.boolean(LayoutElement.QUICK_COMMENT.key) == true
            gate.refresh()
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
        }
        HookRuntime.onDispose(FEATURE) {
            closed = true
            fun restore() {
                tracked.keys.toList().forEach { it.removeOnAttachStateChangeListener(detach) }
                gate.detach(owner)
                tracked.clear()
            }
            if (Looper.myLooper() == main.looper) restore() else main.post { restore() }
        }
        HookRuntime.event(FEATURE, "registered component contracts=" + contracts.size)
    }
}
