package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.zip.ZipFile
import java.security.MessageDigest

/** Read-only early observations, independent of the user's immersive switch and the broad symbol scan. */
internal object ImmersiveLayoutPreparation {
    const val FEATURE = "ImmersivePreparation"
    const val PANEL = "com.ss.android.ugc.feed.platform.panel.adaption.ScreenAdaptionComponent"
    const val FEED = "com.ss.android.ugc.feed.platform.cell.component.adaption.FeedCellAdaptionComponentV2"
    data class Target(val owner: Any, val root: View, val panel: Boolean)
    private class Row(owner: Any, val panel: Boolean, root: View?) {
        val owner = WeakReference(owner)
        var root = WeakReference(root)
    }
    private val rows = mutableListOf<Row>()
    private val listeners = mutableSetOf<() -> Unit>()
    private var panelRoot: java.lang.reflect.Field? = null
    @Volatile private var epoch = 0L
    @Volatile var started = false
        private set
    @Volatile var pending = true
        private set
    @Volatile var supported = false
        private set
    private fun note(owner: Any, panel: Boolean, root: View? = null) {
        if (Looper.myLooper() != Looper.getMainLooper() || owner.javaClass.name != if (panel) PANEL else FEED) return
        rows.removeAll { it.owner.get() == null }
        val row = rows.firstOrNull { it.owner.get() === owner }
        if (row != null) { if (root != null) row.root = WeakReference(root) }
        else if (rows.size < 48) rows += Row(owner, panel, root)
        if (root != null) synchronized(listeners) { listeners.toList() }.forEach { it() }
    }
    fun targets(): List<Target> = rows.mapNotNull { row ->
        val owner = row.owner.get() ?: return@mapNotNull null
        val root = row.root.get() ?: if (row.panel) panelRoot?.get(owner) as? View else null
        root?.let { Target(owner, it, row.panel) }
    }
    fun current(target: Target): Boolean = targets().any { it.owner === target.owner && it.root === target.root && it.panel == target.panel }
    fun subscribe(listener: () -> Unit): AutoCloseable {
        synchronized(listeners) { listeners += listener }
        return AutoCloseable { synchronized(listeners) { listeners -= listener } }
    }
    fun prepare(module: XposedModule, loader: ClassLoader, context: Context, ready: (Boolean) -> Unit) {
        if (started) return
        started = true
        val generation = ++epoch
        val scope = HookRuntime.scope(FEATURE)
        HookRuntime.install(FEATURE) {
            val panel = Class.forName(PANEL, false, loader)
            val feed = Class.forName(FEED, false, loader)
            panelRoot = panel.getDeclaredField("LLL").apply { check(View::class.java.isAssignableFrom(type)); isAccessible = true }
            for ((type, isPanel) in listOf(panel to true, feed to false)) {
                module.trackHook(FEATURE, type.getDeclaredConstructor(), requiresConfiguration = false).intercept { chain ->
                    val result = chain.proceed()
                    if (scope.active && epoch == generation) chain.thisObject?.let { note(it, isPanel) }
                    result
                }
                val created = if (isPanel) type.getDeclaredMethod("LLLLLLL", View::class.java, Bundle::class.java)
                    else type.getMethod("onViewCreated", View::class.java)
                module.trackHook(FEATURE, created, requiresConfiguration = false).intercept { chain ->
                    val result = chain.proceed()
                    val owner = chain.thisObject
                    if (scope.active && epoch == generation && owner != null && type.isInstance(owner)) note(owner, isPanel, chain.args[0] as? View)
                    result
                }
                val release = type.getMethod(if (isPanel) "onDestroy" else "onHostDestroy")
                module.trackHook(FEATURE, release, requiresConfiguration = false).intercept { chain ->
                    val owner = chain.thisObject
                    fun retire() { if (epoch == generation) rows.removeAll { it.owner.get() === owner } }
                    if (Looper.myLooper() == Looper.getMainLooper()) retire() else Handler(Looper.getMainLooper()).post { retire() }
                    chain.proceed()
                }
            }
            val worker = Executors.newSingleThreadExecutor()
            HookRuntime.onDispose(FEATURE) {
                worker.shutdownNow()
                if (epoch == generation) { started = false; pending = false; supported = false }
                fun clear() { if (epoch == generation) { synchronized(listeners) { listeners.clear() }; rows.clear() } }
                if (Looper.myLooper() == Looper.getMainLooper()) clear() else Handler(Looper.getMainLooper()).post { clear() }
            }
            worker.execute {
                val verified = verify(context)
                Handler(Looper.getMainLooper()).post {
                    if (scope.active && epoch == generation) {
                        pending = false; supported = verified
                        HookRuntime.state(FEATURE, if (verified) "已核实47.6.3，允许准备热应用" else "未匹配代码，保持原启动契约")
                        ready(verified)
                    }
                }
            }
        }
        if (!scope.active) { pending = false; supported = false; ready(false) }
    }
    private fun verify(context: Context): Boolean = try {
        val expected = mapOf("classes4.dex" to "2757ca393a37b513c91d4cbea971b9933e6bee17985a6cbb4d9f31c9b2f60c10",
            "classes5.dex" to "60d095d1bf85b222fa691453133c29b976b274d4ceed215a30603858ac5e18ff")
        ZipFile(context.applicationInfo.sourceDir).use { zip -> expected.all { (name, digest) ->
            val entry = zip.getEntry(name) ?: return@all false
            if (entry.size !in 1..32L * 1024 * 1024) return@all false
            val hash = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(entry).use { input ->
                var total = 0L
                val bytes = ByteArray(128 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) return false
                    val count = input.read(bytes); if (count < 0) break
                    total += count; require(total <= 32L * 1024 * 1024)
                    hash.update(bytes, 0, count)
                }
            }
            hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == digest
        } }
    } catch (_: Exception) { false }
}
