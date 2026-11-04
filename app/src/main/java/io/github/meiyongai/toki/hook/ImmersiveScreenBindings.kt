package io.github.meiyongai.toki.subtitle.hook

import java.lang.reflect.Modifier
import java.lang.ref.WeakReference

/** Exact inspected panel/cache bridge. It never writes an Aweme, a User, or final screen-result fields. */
internal class ImmersiveScreenBindings(val contract: Contract, private val report: (Exception) -> Unit = {}) {
    class Contract(panel: Class<*>, feed: Class<*>, val screen: Class<*>) {
        val apply = panel.getDeclaredMethod("Aq", screen).apply { check(returnType == Void.TYPE); isAccessible = true }
        val refresh = panel.getDeclaredMethod("adaptation").apply { check(returnType == Void.TYPE); isAccessible = true }
        val handler = panel.getDeclaredMethod("sq").apply { check(!Modifier.isStatic(modifiers)); isAccessible = true }
        private val base = handler.returnType.superclass.superclass
        val cache = base.getDeclaredField("LLJJIJIIJIL").apply {
            check(!Modifier.isStatic(modifiers) && type.isAssignableFrom(screen)); isAccessible = true
        }
        val publish = base.getDeclaredMethod("LIZLLL", cache.type).apply { check(returnType == Void.TYPE); isAccessible = true }
        private val mirror = handler.returnType.getDeclaredField("LLJJJJJIL").apply {
            check(Modifier.isStatic(modifiers) && type == handler.returnType); isAccessible = true
        }
        private val getters = listOf("getTopSpaceHeight", "getBottomSpaceHeight", "getScreenHeight", "getScreenWidth",
            "getStatusBarHeight", "getAppNavHeight", "getVirtualBarHeight").map { screen.getMethod(it) }
        private val copy = screen.getMethod("copy", *getters.map { it.returnType }.toTypedArray()).apply { check(returnType == screen) }
        private val reason = feed.superclass.declaredMethods.single { it.name == "Wp" && it.parameterCount == 3 }.apply {
            check(returnType == Void.TYPE && parameterTypes[0].isEnum && parameterTypes[1] == Integer::class.java && parameterTypes[2] == Integer::class.java)
            isAccessible = true
        }
        private val changed = reason.parameterTypes[0].getField("CONTAINER_ADAPTION").apply {
            check(Modifier.isStatic(modifiers) && type == reason.parameterTypes[0])
        }
        fun project(original: Any): Any {
            val args = getters.map { it.invoke(original) }.toTypedArray()
            args[0] = 0; args[1] = 0
            return checkNotNull(copy.invoke(original, *args))
        }
        fun linkedHandlers(panel: Any): List<Any> = buildList {
            listOfNotNull(handler.invoke(panel), mirror.get(null)).forEach { candidate -> if (none { it === candidate }) add(candidate) }
        }
        fun invalidate(feed: Any) { reason.invoke(feed, changed.get(null), null, null) }
    }
    private class Projection(value: Any, val original: Any) { val value = WeakReference(value) }
    private class Owned(owner: Any, output: Any, val original: Any) {
        val owner = WeakReference(owner)
        val output = WeakReference(output)
    }
    private val projections = mutableListOf<Projection>()
    private val owned = mutableListOf<Owned>()
    private var publishing = false
    fun original(value: Any): Any {
        projections.removeAll { it.value.get() == null }
        return projections.firstOrNull { it.value.get() === value }?.original ?: value
    }
    /** Called by the real panel apply hook; only replace a cache still holding this exact source/output. */
    fun project(panel: Any, input: Any, enabled: Boolean): Any {
        if (publishing || !contract.screen.isInstance(input)) return input
        val baseline = original(input)
        val handlers = contract.linkedHandlers(panel)
        val first = handlers.firstOrNull() ?: return input
        val cached = contract.cache.get(first)
        if (cached !== input && cached !== baseline && owned.none { it.owner.get() === first && it.output.get()?.let { value -> value === cached } == true }) {
            report(IllegalStateException("Native screen cache changed before projection")); return input
        }
        if (enabled && baseline !== input) return input
        if (!enabled && baseline === input) return input
        if (enabled && projections.size >= 96) { report(IllegalStateException("Live screen projection capacity reached")); return input }
        owned.removeAll { it.owner.get() == null }
        val output = if (enabled) contract.project(baseline) else baseline
        if (enabled) projections += Projection(output, baseline)
        for (handler in handlers) {
            val current = contract.cache.get(handler)
            if (current !== input && current !== baseline && owned.none { it.owner.get() === handler && it.output.get()?.let { value -> value === current } == true }) continue
            contract.cache.set(handler, output)
            owned.removeAll { it.owner.get() == null || it.owner.get() === handler }
            if (enabled) owned += Owned(handler, output, baseline)
            try {
                publishing = true
                contract.publish.invoke(handler, output)
            } catch (error: Exception) { report(error) }
            finally { publishing = false }
        }
        return output
    }
    fun restoreCaches() {
        owned.toList().forEach { entry -> try {
            val handler = entry.owner.get() ?: return@forEach
            val output = entry.output.get() ?: return@forEach
            if (contract.cache.get(handler) === output) {
                contract.cache.set(handler, entry.original)
                publishing = true
                try { contract.publish.invoke(handler, entry.original) } finally { publishing = false }
            }
        } catch (error: Exception) { report(error) } }
        owned.clear()
    }
}
