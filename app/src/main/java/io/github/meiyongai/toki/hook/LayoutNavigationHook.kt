package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.model.LayoutElement
import io.github.meiyongai.toki.subtitle.model.LayoutGroup
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Restrict selection only on the host's home pager; photo and profile pagers are untouched. */
internal object LayoutNavigationHook {
    private const val FEATURE = "LayoutNavigation"
    private val intType = Int::class.javaPrimitiveType!!
    private val booleanType = Boolean::class.javaPrimitiveType!!

    internal class Contract(owner: Class<*>, ability: Class<*>) {
        val tag: Method = ability.methods.single {
            it.returnType == String::class.java && it.parameterTypes.contentEquals(arrayOf(intType, booleanType))
        }
        val pager: Method = owner.declaredMethods.single {
            !Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                ViewGroup::class.java.isAssignableFrom(it.returnType) &&
                it.returnType.methods.any { method -> method.name == "getCurrentItem" && method.parameterCount == 0 }
        }.apply { isAccessible = true }
        val current: Method = pager.returnType.getMethod("getCurrentItem").apply { check(returnType == intType) }
        val adapter: Method = pager.returnType.getMethod("getAdapter")
        val count: Method = adapter.returnType.getMethod("getCount").apply { check(returnType == intType) }
        // This is the shared native destination setter used by drag release, restored state and tab clicks.
        val select: Method = pager.returnType.declaredMethods.single {
            !Modifier.isStatic(it.modifiers) && it.returnType == Void.TYPE &&
                it.parameterTypes.contentEquals(arrayOf(intType, booleanType, booleanType, intType))
        }.apply { isAccessible = true }
        val created: Method = owner.getMethod("onViewCreated", View::class.java)

        init { check(ability.isAssignableFrom(owner)) }
    }

    fun init(module: XposedModule, loader: ClassLoader) {
        install(module, Contract(
            loader.loadClass("com.ss.android.ugc.aweme.main.assems.mainfragment.HomeViewPagerAssem"),
            loader.loadClass("com.ss.android.ugc.aweme.base.mainfragment.HomeViewPagerAbility")
        ))
    }

    internal fun install(module: XposedModule, contract: Contract) {
        val pagers = WeakHashMap<View, WeakReference<Any>>()
        val pending = WeakHashMap<View, Runnable>()
        var hidden: Set<LayoutElement> = emptySet()

        fun destination(pager: View, owner: Any, requested: Int): Int {
            if (hidden.isEmpty()) return requested
            val adapter = contract.adapter.invoke(pager) ?: return requested
            val count = contract.count.invoke(adapter) as Int
            val tags = (0 until count).map { contract.tag.invoke(owner, it, false) as String? }
            return LayoutPageSelection.target(tags, contract.current.invoke(pager) as Int, requested) { tag ->
                LayoutElement.navigation(LayoutGroup.TOP, tag) in hidden
            }
        }

        fun refresh(pager: View) {
            if (pending.containsKey(pager)) return
            val reference = WeakReference(pager)
            val task = Runnable {
                val view = reference.get() ?: return@Runnable
                pending.remove(view)
                if (!HookRuntime.scope(FEATURE).active) return@Runnable
                val owner = pagers[view]?.get() ?: return@Runnable
                try {
                    val current = contract.current.invoke(view) as Int
                    val target = destination(view, owner, current)
                    if (target != current) contract.select.invoke(view, target, false, true, 0)
                } catch (error: Exception) {
                    HookRuntime.disableAfterCallbackFailure(FEATURE, error)
                }
            }
            pending[pager] = task
            if (!pager.post(task)) pending.remove(pager)
        }

        HookRuntime.dependent("LayoutCleanupHook", FEATURE)
        HookRuntime.onDispose(FEATURE) {
            pending.forEach { (view, task) -> view.removeCallbacks(task) }
            pending.clear()
            pagers.clear()
            hidden = emptySet()
        }
        HookRuntime.subscribe(FEATURE) { snapshot ->
            hidden = LayoutElement.entries.filter {
                it.group == LayoutGroup.TOP && snapshot?.boolean(it.key) == true
            }.toSet()
            pagers.keys.toList().forEach(::refresh)
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot, hidden.isNotEmpty())
        }
        module.trackHook(FEATURE, contract.pager, requiresConfiguration = false).intercept { chain ->
            val result = chain.proceed()
            if (result is View) pagers[result] = WeakReference(checkNotNull(chain.thisObject))
            result
        }
        module.trackHook(FEATURE, contract.created, requiresConfiguration = false).intercept { chain ->
            val result = chain.proceed()
            val owner = checkNotNull(chain.thisObject)
            (contract.pager.invoke(owner) as? View)?.let { pager ->
                pagers[pager] = WeakReference(owner)
                refresh(pager)
            }
            result
        }
        module.trackHook(FEATURE, contract.select, requiresConfiguration = false).intercept { chain ->
            val pager = chain.thisObject as View
            val owner = pagers[pager]?.get()
            val requested = chain.args[0] as Int
            val target = if (owner == null) requested else destination(pager, owner, requested)
            if (target == requested) chain.proceed() else {
                // Force settling when returning to the current page after a blocked edge drag.
                HookRuntime.count(FEATURE, "已跳过隐藏栏目")
                chain.proceed(arrayOf<Any>(target, false, true, 0))
            }
        }
    }
}
