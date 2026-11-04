package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.model.LayoutElement
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Hide only the inspected entrance controls, never their shared video/carousel lifecycle root. */
internal object DanmakuEntranceHook {
    private const val FEATURE = "DanmakuEntranceHook"
    internal class Contract(val type: Class<*>, names: List<String>) {
        val created = type.getMethod("onViewCreated", View::class.java).apply {
            check(returnType == Void.TYPE && !Modifier.isStatic(modifiers)); isAccessible = true
        }
        val released = listOf("unBind", "onDestroyView").mapNotNull { name ->
            try { type.getMethod(name).apply {
                check(returnType == Void.TYPE && parameterCount == 0 && !Modifier.isStatic(modifiers)); isAccessible = true
            } } catch (_: NoSuchMethodException) { null }
        }
        private val fields = names.map { name -> type.getDeclaredField(name).apply {
            check(View::class.java.isAssignableFrom(this.type) && !Modifier.isStatic(modifiers)); isAccessible = true
        } }
        fun targets(owner: Any, root: View): Set<View> {
            if (!type.isInstance(owner)) return emptySet()
            return fields.mapNotNull { it.get(owner) as? View }.filterTo(linkedSetOf()) { candidate ->
                if (candidate === root) false else {
                    var cursor: View? = candidate; var depth = 0
                    while (cursor != null && cursor !== root && depth++ < 64) cursor = cursor.parent as? View
                    cursor === root
                }
            }
        }
    }

    fun init(module: XposedModule, loader: ClassLoader) {
        val contracts = mapOf(
            "com.ss.android.ugc.aweme.DanmakuCreateEntranceAssem" to listOf("LLLF", "LLLFF"),
            "com.ss.android.ugc.aweme.DanmakuSwitchAssem" to listOf("LLLFFI", "LLLI"),
        ).mapNotNull { (name, fields) ->
            try { Contract(Class.forName(name, false, loader), fields) }
            catch (error: ReflectiveOperationException) { HookRuntime.event(FEATURE, "入口契约不可用：$name type=${error.javaClass.simpleName}"); null }
            catch (error: IllegalStateException) { HookRuntime.event(FEATURE, "入口字段变化，保持原界面：$name"); null }
        }
        check(contracts.isNotEmpty()) { "No inspected danmaku entrance contract available" }
        val gate = HostViewVisibility.gate
        val rule = CleanViewGate.Owner(hiddenVisibility = View.GONE)
        val roots = WeakHashMap<Any, Set<View>>()
        val main = Handler(Looper.getMainLooper())
        fun refresh() { gate.replace(rule, roots.values.flatten().toSet()); gate.refresh() }
        HostViewVisibility.acquire(module, FEATURE)
        for (method in contracts.map { it.created }.distinct()) {
            module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
                val result = chain.proceed()
                val instance = chain.thisObject
                val root = chain.args[0] as? View
                val contract = contracts.firstOrNull { it.type.isInstance(instance) }
                if (instance != null && root != null && contract != null && Looper.myLooper() == main.looper) {
                    if (instance in roots || roots.size < 256) {
                        roots[instance] = contract.targets(instance, root)
                        refresh()
                        HookRuntime.count(FEATURE, "已绑定独立弹幕入口", roots[instance].orEmpty().size.toLong())
                    }
                }
                result
            }
        }
        for (method in contracts.flatMap { it.released }.distinct()) {
            module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
                try { chain.proceed() } finally {
                    if (Looper.myLooper() == main.looper && roots.remove(chain.thisObject) != null) refresh()
                }
            }
        }
        HookRuntime.subscribe(FEATURE) { snapshot ->
            rule.clean = snapshot?.boolean(LayoutElement.DANMAKU_ENTRANCE.key) == true
            refresh()
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
        }
        HookRuntime.onDispose(FEATURE) {
            fun restore() { gate.detach(rule); roots.clear() }
            if (Looper.myLooper() == main.looper) restore() else main.post { restore() }
        }
        HookRuntime.detail(FEATURE, "仅隐藏弹幕创建／切换入口的专用子控件；不改视频、图片轮播或弹幕内容设置")
    }
}
