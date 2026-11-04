package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import io.github.meiyongai.toki.subtitle.provider.ExecutionEvidence
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Cover both direct native setText callbacks and the rich Mq route; never decorate every TextView globally. */
internal object AuthorLocationLabels {
    private const val FEATURE = "AuthorLocationHook"
    private const val COMPONENT = "com.ss.android.ugc.aweme.feed.assem.videoauthorinfo.VideoAuthorInfoRelationAssem"
    internal class Resolved(val component: Class<*>, val label: AuthorLabelContract,
        val created: Method, val itemBound: Method, val nickname: Method, val userUpdated: Method, val invalidate: (Any) -> Unit)
    private var updateStatus: (() -> Unit)? = null
    fun refreshStatus() { updateStatus?.invoke() }
    fun resolve(loader: ClassLoader, quiet: Boolean = false): Resolved? = try {
        val component = loader.loadClass(COMPONENT)
        val params = loader.loadClass("com.ss.android.ugc.aweme.feed.model.VideoItemParams")
        val aweme = loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme")
        val user = loader.loadClass("com.ss.android.ugc.aweme.profile.model.User")
        val native = AuthorDisplayedUserContract(component, loader.loadClass("com.bytedance.assem.arch.reused.ReusedAssem"),
            params, aweme, user, loader.loadClass("X.090D"), loader.loadClass("X.08zj"))
        fun callback(type: Class<*>, name: String, result: Class<*>, vararg arguments: Class<*>): Method =
            type.getDeclaredMethod(name, *arguments).apply {
                check(!Modifier.isStatic(modifiers) && returnType == result); isAccessible = true
            }
        Resolved(component, AuthorLabelContract(component, params, aweme, user, native::current),
            callback(component, "onViewCreated", Void.TYPE, View::class.java),
            callback(component, "i4", Void.TYPE, Any::class.java),
            callback(loader.loadClass("X.093W"), "invoke", Any::class.java, Any::class.java, Any::class.java),
            callback(loader.loadClass("X.093E"), "invoke", Any::class.java, Any::class.java, Any::class.java), native::invalidate)
    } catch (error: Exception) {
        if (!quiet) HookRuntime.event(FEATURE, "作者原生绑定契约不可用；type=" + error.javaClass.simpleName)
        null
    } catch (error: LinkageError) {
        if (!quiet) HookRuntime.event(FEATURE, "作者原生绑定契约不可用；type=" + error.javaClass.simpleName)
        null
    }

    fun install(module: XposedModule, resolved: Resolved) {
        val main = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "toki-author-code-check").apply { isDaemon = true } }
        var ready = false; var checked = false; var configuration: ConfigSnapshot? = null
        var publishing = false
        lateinit var bindings: AuthorLabelBindings
        fun publish() {
            if (closed.get() || publishing) return
            publishing = true
            try {
                val wanted = configuration?.boolean(AuthorLocationHook.KEY_SHOW_AUTHOR_LOCATION) == true
                val stats = bindings.statistics()
                HookRuntime.state(FEATURE, when {
                    wanted && !checked -> "正在核验作者显示代码"
                    wanted && !ready -> "部分功能已注册"
                    wanted && stats.decorated == 0 -> "等待作者标签或地区数据"
                    else -> "已注册"
                })
                val outcome = when {
                    configuration == null -> ExecutionEvidence.Outcome.CONFIG_MISSING
                    !wanted -> ExecutionEvidence.Outcome.DISABLED
                    !checked -> ExecutionEvidence.Outcome.CODE_PENDING
                    !ready -> ExecutionEvidence.Outcome.CODE_UNSUPPORTED
                    stats.known == 0 -> ExecutionEvidence.Outcome.NO_TARGET
                    stats.waiting + stats.mismatch + stats.missingRegion > 0 -> ExecutionEvidence.Outcome.PARTIAL
                    stats.decorated == 0 -> ExecutionEvidence.Outcome.NO_TARGET
                    else -> ExecutionEvidence.Outcome.RULE_REFRESHED
                }
                HookRuntime.execution(FEATURE, ExecutionEvidence(ExecutionEvidence.Scope.AUTHOR, ExecutionEvidence.Action.BIND, outcome,
                    configuration?.revision, configuration?.let { wanted && ready }, mapOf(
                        ExecutionEvidence.Metric.KNOWN to stats.known.toLong(), ExecutionEvidence.Metric.DECORATED to stats.decorated.toLong(),
                        ExecutionEvidence.Metric.WAITING_MODEL to stats.waiting.toLong(), ExecutionEvidence.Metric.NAME_MISMATCH to stats.mismatch.toLong(),
                        ExecutionEvidence.Metric.MISSING_REGION to stats.missingRegion.toLong())))
                configuration?.let { HookRuntime.appliedConfiguration(FEATURE, it, wanted && ready) }
            } finally { publishing = false }
        }
        bindings = AuthorLabelBindings({ owner -> resolved.label.label.get(owner) as? TextView },
            { owner -> if (ready) resolved.label.current(owner) else null }, ::publish, { error ->
                HookRuntime.event(FEATURE, "作者标签读取／刷新失败，保留原生内容；type=" + error.javaClass.simpleName)
            })
        fun configure() {
            if (closed.get()) return
            bindings.configure(ready && configuration?.boolean(AuthorLocationHook.KEY_SHOW_AUTHOR_LOCATION) == true)
            publish()
        }
        HookRuntime.onDispose(FEATURE) {
            closed.set(true); worker.shutdownNow(); updateStatus = null
            if (Looper.myLooper() == main.looper) bindings.close() else main.post { bindings.close() }
        }
        for (method in listOf(resolved.created, resolved.itemBound, resolved.label.bind, resolved.nickname, resolved.userUpdated)) {
            module.trackHook(FEATURE, method, requiresConfiguration = false).intercept { chain ->
                if (closed.get() || Looper.myLooper() != main.looper) return@intercept chain.proceed()
                val owner = if (method == resolved.nickname || method == resolved.userUpdated) chain.args[0] else chain.thisObject
                if (owner == null || !resolved.component.isInstance(owner)) return@intercept chain.proceed()
                val before = (resolved.label.label.get(owner) as? TextView)?.text
                val result = chain.proceed()
                resolved.invalidate(owner)
                val raw = when (method) {
                    resolved.label.bind -> chain.args[1] as? CharSequence
                    resolved.nickname -> chain.args[1] as? CharSequence
                    else -> null
                }
                bindings.observe(owner, raw, before)
                HookRuntime.count(FEATURE, if (method == resolved.label.bind) "富文本作者绑定回调" else "直接作者／生命周期绑定回调")
                result
            }
        }
        updateStatus = { if (Looper.myLooper() == main.looper) configure() else main.post { configure() } }
        HookRuntime.subscribe(FEATURE) { snapshot -> configuration = snapshot; configure() }
        val app = checkNotNull(HookRuntime.hostContext)
        worker.execute {
            val supported = AuthorLocationCodeGate.verify(app)
            main.post {
                if (!closed.get() && HookRuntime.scope(FEATURE).active) { checked = true; ready = supported; configure() }
            }
        }
        HookRuntime.detail(FEATURE, "观察真实直接setText及Mq分支、当前cellProxy及原生主页协作者选择；仅写已核对主作者TextView，不改User／Aweme，不记录昵称、账号或视频ID。")
    }
}
