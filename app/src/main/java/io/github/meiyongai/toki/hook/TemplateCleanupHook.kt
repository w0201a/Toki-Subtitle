package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.model.LayoutElement
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Prepared while disabled so later toggles can update known current roots without re-entering the feed. */
internal object TemplateCleanupHook {
    const val FEATURE = "TemplateCleanupHook"
    private var updateStatus: (() -> Unit)? = null
    fun refreshStatus() { updateStatus?.invoke() }
    fun init(module: XposedModule, loader: ClassLoader) {
        val contracts = TemplateAnchorContract.resolve(loader)
        val main = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "toki-template-code-check").apply { isDaemon = true } }
        val owners = mutableListOf<WeakReference<Any>>()
        var reading = false
        var ready = false
        var checked = false
        var config: ConfigSnapshot? = null
        fun current(owner: Any): TemplateRootBindings.Snapshot? {
            if (!ready || closed.get() || reading) return null
            val contract = contracts.firstOrNull { it.type.isInstance(owner) } ?: return null
            reading = true
            return try { contract.current(owner) } finally { reading = false }
        }
        val roots = TemplateRootBindings(HostViewVisibility.gate, ::current, { error ->
            HookRuntime.event(FEATURE, "模板根重新核对失败，保留原界面；type=" + error.javaClass.simpleName)
        })
        fun forget(owner: Any) {
            roots.release(owner); owners.removeAll { it.get() == null || it.get() === owner }
        }
        fun observe(owner: Any) {
            owners.removeAll { it.get() == null }
            if (owners.none { it.get() === owner }) {
                if (owners.size >= 256) { HookRuntime.count(FEATURE, "观察容量已满，保持原界面"); return }
                owners += WeakReference(owner)
            }
            if (!ready) return // Before code match, retain only weak owner identity; do not inspect business/root methods.
            try {
                val value = current(owner)
                roots.observe(owner, value)
                HookRuntime.count(FEATURE, if (value?.target == true) "已观察目标模板／特效根" else "非目标或未就绪根保持原样")
            } catch (error: Exception) {
                roots.release(owner)
                HookRuntime.event(FEATURE, "模板实例读取失败，保留原界面；type=" + error.javaClass.simpleName)
            }
        }
        fun configure() {
            if (closed.get() || !HookRuntime.scope(FEATURE).active) return
            val desired = config?.boolean(LayoutElement.VIDEO_TEMPLATE.key) == true
            roots.configure(desired && ready)
            HookRuntime.state(FEATURE, when {
                desired && !checked -> "正在核验页面组件"
                desired && !ready -> "部分功能已注册"
                else -> "已注册"
            })
            HookRuntime.detail(FEATURE, "只隐藏已核对业务类型的当前渲染根和专用视频模板组件；codeReady=$ready; trackedOwners=${owners.size}; types=${TemplateAnchorContract.allowedAnchorNames.size}; 不处理未知／普通CapCut链接，不按共享schema类名隐藏；首次注入前已有实例仍需宿主回调")
            config?.let { HookRuntime.appliedConfiguration(FEATURE, it, desired && ready) }
        }
        HookRuntime.onDispose(FEATURE) {
            closed.set(true); worker.shutdownNow(); updateStatus = null
            fun restore() { roots.close(); owners.clear() }
            if (Looper.myLooper() == main.looper) restore() else main.post { restore() }
        }
        HostViewVisibility.acquire(module, FEATURE)
        for (method in contracts.flatMap { it.bound + it.released }.distinct()) {
            module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
                val owner = chain.thisObject
                if (owner == null || closed.get() || reading || Looper.myLooper() != main.looper) return@intercept chain.proceed()
                val matching = contracts.filter { it.type.isInstance(owner) && (method in it.bound || method in it.released) }
                if (matching.isEmpty()) return@intercept chain.proceed()
                if (matching.any { method in it.released }) {
                    forget(owner)
                    reading = true
                    try { return@intercept chain.proceed() } finally { reading = false }
                }
                val result = chain.proceed()
                observe(owner)
                result
            }
        }
        updateStatus = { if (Looper.myLooper() == main.looper) configure() else main.post { configure() } }
        HookRuntime.subscribe(FEATURE) { value -> config = value; configure() }
        val app = checkNotNull(HookRuntime.hostContext)
        worker.execute {
            val supported = TemplateCleanupCodeGate.verify(app)
            main.post {
                if (!closed.get() && HookRuntime.scope(FEATURE).active) {
                    checked = true; ready = supported
                    if (ready) owners.mapNotNull { it.get() }.toList().forEach(::observe)
                    configure()
                }
            }
        }
    }
}
