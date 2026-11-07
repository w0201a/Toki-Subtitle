package io.github.meiyongai.toki.subtitle.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Requests the installed manager's existing companion service; never loads or modifies a framework. */
internal class FrameworkServicePullClient(
    context: Context,
    private val connected: () -> Boolean,
    private val report: (String) -> Unit,
    private val execute: (() -> Unit) -> Unit = { work ->
        check(workerBusy.compareAndSet(false, true)) { "Previous service request is still running" }
        try { worker.execute { try { work() } finally { workerBusy.set(false) } } }
        catch (error: Exception) { workerBusy.set(false); throw error }
    },
    private val discover: () -> List<ComponentName> = { discoverServices(context) },
) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var cycle = 0L
    private var running = false
    private var attempts = 0
    private var lastRequestAt: Long? = null
    private var current: Request? = null
    private var next: Runnable? = null

    private inner class Request(val generation: Long, val target: ComponentName) : ServiceConnection {
        var bound = false
        var completed = false
        var released = false
        val timeout = Runnable { complete(this, "connection request timed out") }
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (current !== this || completed) return
            try {
                execute {
                    val outcome = try {
                        if (requestPush(service)) "manager accepted delivery request; awaiting companion Binder"
                        else "manager did not accept delivery request"
                    } catch (error: Exception) { "delivery request failed: ${error.javaClass.simpleName}" }
                    main.post { complete(this, outcome) }
                }
            } catch (error: Exception) {
                complete(this, "delivery worker unavailable: ${error.javaClass.simpleName}")
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { complete(this, "pull service disconnected") }
        override fun onBindingDied(name: ComponentName) { complete(this, "pull service binding died") }
        override fun onNullBinding(name: ComponentName) { complete(this, "pull service returned no Binder") }
    }

    /** Called only on process start or a foreground transition, with bounded retries for a cold manager. */
    fun request() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (connected()) { cancel(); return }
        if (running) return
        val now = SystemClock.elapsedRealtime()
        if (lastRequestAt?.let { now - it < RETRY_MS } == true) return
        lastRequestAt = now
        running = true
        attempts = 0
        attempt(++cycle)
    }

    /** A received real service cancels pending discovery/retry work, without manufacturing activation. */
    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { cancel() }; return }
        ++cycle
        running = false
        next?.let(main::removeCallbacks)
        next = null
        current?.let { request ->
            request.completed = true
            main.removeCallbacks(request.timeout)
            release(request)
        }
        current = null
    }

    private fun attempt(generation: Long) {
        if (!running || generation != cycle) return
        if (connected()) { cancel(); return }
        val targets = try { discover() } catch (error: Exception) {
            report("pull service discovery failed: ${error.javaClass.simpleName}")
            running = false
            return
        }
        if (targets.isEmpty()) { report("no installed companion pull service; waiting for normal framework delivery"); running = false; return }
        val request = Request(generation, targets[attempts % targets.size])
        ++attempts
        current = request
        main.postDelayed(request.timeout, TIMEOUT_MS)
        try {
            // Resolve by action, then bind explicitly. No hard-coded manager package or UI launch.
            request.bound = app.bindService(Intent(ACTION).setComponent(request.target), request, Context.BIND_AUTO_CREATE)
            // Cover a synchronous callback/failure during bindService without leaking the resulting binding.
            if (request.completed) release(request)
            else if (!request.bound) complete(request, "pull service could not be bound")
        } catch (error: Exception) { complete(request, "pull service bind failed: ${error.javaClass.simpleName}") }
    }

    private fun release(request: Request) {
        if (!request.bound || request.released) return
        request.released = true
        try { app.unbindService(request) }
        catch (error: IllegalArgumentException) { report("pull binding was already released") }
    }

    private fun complete(request: Request, outcome: String) {
        if (current !== request || request.completed || request.generation != cycle) return
        request.completed = true
        main.removeCallbacks(request.timeout)
        release(request)
        current = null
        report(outcome)
        if (connected()) { cancel(); return }
        if (attempts >= MAX_ATTEMPTS) { running = false; return }
        next = Runnable { next = null; attempt(request.generation) }.also { main.postDelayed(it, RETRY_MS) }
    }

    companion object {
        const val ACTION = "org.lsposed.lspatch.action.REQUEST_PUSH"
        private const val DESCRIPTOR = "org.lsposed.lspatch.IXposedServicePull"
        internal const val MAX_ATTEMPTS = 3
        internal const val RETRY_MS = 3_500L
        internal const val TIMEOUT_MS = 5_000L
        private val workerBusy = AtomicBoolean()
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "TokiFrameworkPull").apply { isDaemon = true }
        }

        /** This is the one-method protocol verified in the installed original 487 manager. */
        internal fun requestPush(service: IBinder): Boolean {
            check(service.interfaceDescriptor == DESCRIPTOR) { "Unsupported companion pull protocol" }
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                check(service.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0)) { "Unsupported pull transaction" }
                reply.readException()
                return reply.readInt() != 0
            } finally { reply.recycle(); data.recycle() }
        }

        @Suppress("DEPRECATION")
        internal fun discoverServices(context: Context): List<ComponentName> =
            context.packageManager.queryIntentServices(Intent(ACTION), 0).mapNotNull { result ->
                val service = result.serviceInfo ?: return@mapNotNull null
                if (!service.exported || !service.enabled || !service.applicationInfo.enabled ||
                    (!service.permission.isNullOrEmpty() && context.checkSelfPermission(service.permission) != PackageManager.PERMISSION_GRANTED)) null
                else ComponentName(service.packageName, service.name)
            }.distinct().sortedBy { it.flattenToString() }
    }
}
