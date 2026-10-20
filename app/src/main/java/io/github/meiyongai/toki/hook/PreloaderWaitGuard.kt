package io.github.meiyongai.toki.subtitle.hook

import android.app.Application
import android.content.ContextWrapper
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/** Modern API capable injectors can also encounter already-running preloader waits. */
internal object PreloaderWaitGuard {
    private val installed = AtomicBoolean(false)
    fun install(module: XposedModule, loader: ClassLoader, path: String) {
        if (!installed.compareAndSet(false, true)) return
        HookRuntime.install("PreloaderWaitGuard") {
            val contract = PreloaderWaitContract.verify(path) ?: return@install
            val marker = Class.forName(contract.markerOwner, false, loader).getDeclaredField(contract.marker).apply { isAccessible = true }
            val point = ContextWrapper::class.java.getMethod("getBaseContext")
            module.trackHook("PreloaderWaitGuard", point, requiresConfiguration = false).intercept { chain ->
                val result = chain.proceed()
                if (chain.thisObject is Application && Thread.currentThread().name.startsWith("AsyncTask") &&
                    (result == null || marker.getLong(null) == 0L)) {
                    val waiting = Thread.currentThread().stackTrace.any { it.className == contract.owner && it.methodName == contract.method }
                    if (waiting) try { Thread.sleep(10) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                }
                result
            }
            HookRuntime.detail("PreloaderWaitGuard", "verified startup wait checkpoint yields 10ms; no marker mutation")
        }
    }
}
