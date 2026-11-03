package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.model.PageCleanupItem
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import java.util.concurrent.Executors

/** Independent page-internal cleanup. Native component lifecycle/data/events always proceed unchanged. */
internal object PageCleanupHook {
    const val FEATURE = "PageCleanupHook"
    @Volatile private var refresh: (() -> Unit)? = null
    /** Reassert component readiness after generic installation/configuration diagnostics have been updated. */
    fun refreshStatus() { refresh?.invoke() }
    fun init(module: XposedModule, loader: ClassLoader) {
        val contracts = PageCleanupItem.entries.mapNotNull { item ->
            try { PageCleanupContract.resolve(item, loader) }
            catch (error: Exception) { HookRuntime.event(FEATURE, "component unavailable item=" + item.name + " type=" + error.javaClass.simpleName); null }
            catch (error: LinkageError) { HookRuntime.event(FEATURE, "component unavailable item=" + item.name + " type=" + error.javaClass.simpleName); null }
        }
        check(contracts.isNotEmpty()) { "No inspected page cleanup contract available" }
        val main = Handler(Looper.getMainLooper())
        val bindings = PageCleanupBindings(HostViewVisibility.gate)
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "toki-page-cleanup-check").apply { isDaemon = true } }
        var snapshot: ConfigSnapshot? = null
        var supported = emptySet<PageCleanupItem>()
        var checked = false
        var closed = false
        HookRuntime.onDispose(FEATURE) {
            refresh = null
            worker.shutdownNow()
            fun restore() { closed = true; bindings.close() }
            if (Looper.myLooper() == main.looper) restore() else main.post { restore() }
        }
        HostViewVisibility.acquire(module, FEATURE)
        fun configure() {
            if (closed || !HookRuntime.scope(FEATURE).active) return
            val requested = PageCleanupItem.entries.filterTo(linkedSetOf()) { snapshot?.boolean(it.key) == true }
            val enabled = requested.intersect(supported)
            val missing = requested - supported
            bindings.configure(enabled)
            HookRuntime.state(FEATURE, when {
                !checked && requested.isNotEmpty() -> "正在核验页面组件"
                checked && missing.isNotEmpty() -> "部分功能已注册"
                else -> "已注册"
            })
            HookRuntime.detail(FEATURE, "component contracts=" + contracts.size + " verified items=" + supported.joinToString { it.name } +
                "; unavailable requested=" + missing.joinToString { it.name } +
                "; only actual inflated content roots; lifecycle and data unchanged")
            snapshot?.let { HookRuntime.appliedConfiguration(FEATURE, it, enabled.isNotEmpty()) }
        }
        refresh = {
            if (Looper.myLooper() == main.looper) configure() else main.post { configure() }
        }
        val methods = contracts.flatMap { it.bound + it.released }.distinct()
        for (method in methods) module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
            val owner = chain.thisObject
            if (owner == null || closed || Looper.myLooper() != main.looper) return@intercept chain.proceed()
            val matches = contracts.filter { it.type.isInstance(owner) && (method in it.bound || method in it.released) }
            if (matches.any { method in it.released }) bindings.release(owner)
            val result = chain.proceed()
            matches.filter { method in it.bound }.forEach { contract ->
                try {
                    val root = contract.root(owner, chain.args.toTypedArray(), result)
                    if (root == null) bindings.release(owner) else bindings.bind(owner, contract.item, root)
                } catch (error: Exception) {
                    bindings.release(owner)
                    HookRuntime.event(FEATURE, "root unavailable item=" + contract.item.name + " type=" + error.javaClass.simpleName)
                }
            }
            result
        }
        HookRuntime.subscribe(FEATURE) { value ->
            snapshot = value
            configure()
        }
        val app = checkNotNull(HookRuntime.hostContext)
        worker.execute {
            val inspected = PageCleanupCodeGate.verify(app)
            main.post {
                if (!closed && HookRuntime.scope(FEATURE).active) {
                    checked = true
                    supported = inspected.intersect(contracts.mapTo(mutableSetOf()) { it.item })
                    configure()
                }
            }
        }
    }
}
