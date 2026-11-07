package io.github.meiyongai.toki.subtitle.util

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** No test APKs, framework replacements, host launches or user settings are needed. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29, 35])
class FrameworkServicePullClientTest {
    private val target = ComponentName("installed.manager", "installed.manager.PullService")
    private val looper get() = shadowOf(Looper.getMainLooper())
    private class TestContext : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val requests = mutableListOf<Pair<Intent, ServiceConnection>>()
        val releases = mutableListOf<ServiceConnection>()
        var bindResult = true
        var synchronousNull = false
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
            assertEquals(Context.BIND_AUTO_CREATE, flags)
            requests += intent to connection
            if (synchronousNull) connection.onNullBinding(requireNotNull(intent.component))
            return bindResult
        }
        override fun unbindService(connection: ServiceConnection) { releases += connection }
    }
    private fun binder(accepted: Boolean = true, descriptor: String = "org.lsposed.lspatch.IXposedServicePull", calls: () -> Unit = {}): Binder =
        object : Binder() {
            init { attachInterface(null, descriptor) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(IBinder.FIRST_CALL_TRANSACTION, code)
                assertEquals(0, flags)
                data.enforceInterface(descriptor)
                calls()
                requireNotNull(reply).writeNoException()
                reply.writeInt(if (accepted) 1 else 0)
                return true
            }
        }
    private fun client(context: TestContext, connected: () -> Boolean = { false }, messages: MutableList<String> = mutableListOf()): FrameworkServicePullClient =
        FrameworkServicePullClient(context, connected, messages::add, { it() }, { listOf(target) })
    private fun connect(context: TestContext, index: Int = context.requests.lastIndex, binder: IBinder = binder()) {
        context.requests[index].second.onServiceConnected(target, binder)
        looper.idle()
    }

    @Test fun protocolAcceptsOnlyAcknowledgedTransaction() {
        var calls = 0
        assertTrue(FrameworkServicePullClient.requestPush(binder(calls = { calls++ })))
        assertFalse(FrameworkServicePullClient.requestPush(binder(false, calls = { calls++ })))
        assertEquals(2, calls)
    }

    @Test fun wrongProtocolCannotReceivePullTransaction() {
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            FrameworkServicePullClient.requestPush(binder(descriptor = "unrelated.service", calls = { calls++ }))
        }
        assertEquals(0, calls)
    }

    @Test fun missingTransactionDoesNotPretendServiceWasDelivered() {
        val unsupported = object : Binder() {
            init { attachInterface(null, "org.lsposed.lspatch.IXposedServicePull") }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
        }
        assertThrows(IllegalStateException::class.java) { FrameworkServicePullClient.requestPush(unsupported) }
    }

    @Test fun absentManagerDoesNotLaunchOrPollAnyComponent() {
        val context = TestContext()
        val client = FrameworkServicePullClient(context, { false }, {}, { it() }, { emptyList() })
        client.request()
        looper.idleFor(Duration.ofMinutes(1))
        assertTrue(context.requests.isEmpty())
        assertTrue(context.releases.isEmpty())
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
    }

    @Test fun requestIsExplicitAndPendingRequestsAreDeduplicated() {
        val context = TestContext()
        val client = client(context)
        client.request(); client.request(); client.request()
        assertEquals(1, context.requests.size)
        assertEquals(target, context.requests.single().first.component)
        assertEquals(FrameworkServicePullClient.ACTION, context.requests.single().first.action)
        client.cancel()
        assertEquals(1, context.releases.size)
    }

    @Test fun acceptedRequestIsNotProofOfActivationAndRetriesAreBounded() {
        val context = TestContext()
        val messages = mutableListOf<String>()
        val client = client(context, messages = messages)
        client.request()
        repeat(FrameworkServicePullClient.MAX_ATTEMPTS) { attempt ->
            connect(context)
            if (attempt + 1 < FrameworkServicePullClient.MAX_ATTEMPTS) looper.idleFor(Duration.ofMillis(FrameworkServicePullClient.RETRY_MS))
        }
        looper.idleFor(Duration.ofMinutes(1))
        assertEquals(FrameworkServicePullClient.MAX_ATTEMPTS, context.requests.size)
        assertEquals(FrameworkServicePullClient.MAX_ATTEMPTS, context.releases.size)
        assertEquals(FrameworkServicePullClient.MAX_ATTEMPTS, messages.size)
        assertTrue(messages.all { "awaiting companion Binder" in it })
    }

    @Test fun actualServiceDeliveryStopsRetryWithoutRestartingHost() {
        val context = TestContext()
        var connected = false
        val client = client(context, { connected })
        client.request()
        connect(context, binder = binder(false))
        looper.idleFor(Duration.ofMillis(FrameworkServicePullClient.RETRY_MS))
        assertEquals(2, context.requests.size)
        connected = true
        connect(context)
        looper.idleFor(Duration.ofMinutes(1))
        client.request()
        assertEquals(2, context.requests.size)
        assertEquals(2, context.releases.size)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test fun timedOutBindingIsReleasedAndStaleCallbacksCannotCompleteNewRequest() {
        val context = TestContext()
        val client = client(context)
        client.request()
        val retired = context.requests.single().second
        looper.idleFor(Duration.ofMillis(FrameworkServicePullClient.TIMEOUT_MS))
        assertEquals(listOf(retired), context.releases)
        looper.idleFor(Duration.ofMillis(FrameworkServicePullClient.RETRY_MS))
        retired.onServiceDisconnected(target)
        retired.onServiceConnected(target, binder())
        looper.idle()
        assertEquals(2, context.requests.size)
        assertEquals(1, context.releases.size)
        connect(context)
        assertEquals(2, context.releases.size)
        client.cancel()
    }

    @Test fun synchronousNullBindingAndCancellationReleaseExactlyOnce() {
        val context = TestContext().apply { synchronousNull = true }
        val client = client(context)
        client.request()
        assertEquals(1, context.requests.size)
        assertEquals(1, context.releases.size)
        client.cancel(); client.cancel()
        looper.idleFor(Duration.ofMinutes(1))
        assertEquals(1, context.requests.size)
        assertEquals(1, context.releases.size)
    }

    @Test fun failedBindingAndWorkerRejectionRemainBounded() {
        val context = TestContext().apply { bindResult = false }
        val client = client(context)
        client.request()
        looper.idleFor(Duration.ofMinutes(1))
        assertEquals(FrameworkServicePullClient.MAX_ATTEMPTS, context.requests.size)
        assertTrue(context.releases.isEmpty())
        val workerContext = TestContext()
        val blocked = FrameworkServicePullClient(workerContext, { false }, {}, { throw IllegalStateException("busy") }, { listOf(target) })
        blocked.request()
        connect(workerContext)
        assertEquals(1, workerContext.releases.size)
        blocked.cancel()
    }

    @Test fun recreatedCompanionRequestsAgainEvenWhenPreviousProcessHadConnection() {
        val oldContext = TestContext()
        val old = client(oldContext, { true })
        old.request()
        assertTrue(oldContext.requests.isEmpty())
        val recreatedContext = TestContext()
        val recreated = client(recreatedContext)
        recreated.request()
        assertEquals(1, recreatedContext.requests.size)
        recreated.cancel()
    }

    @Test fun discoverySupportsRenamedManagersButRejectsDisabledPrivateOrPermissionDeniedServices() {
        val app = RuntimeEnvironment.getApplication()
        val manager = shadowOf(app.packageManager)
        fun candidate(pkg: String, enabled: Boolean = true, exported: Boolean = true, permission: String? = null): ResolveInfo =
            ResolveInfo().apply { serviceInfo = ServiceInfo().apply {
                packageName = pkg; name = "$pkg.PullService"; this.enabled = enabled; this.exported = exported; this.permission = permission
                applicationInfo = ApplicationInfo().apply { this.enabled = true; packageName = pkg }
            } }
        for (entry in listOf(candidate("renamed.manager"), candidate("private.manager", exported = false),
            candidate("disabled.manager", enabled = false), candidate("restricted.manager", permission = "android.permission.MANAGE_USERS"))) {
            manager.addResolveInfoForIntent(Intent(FrameworkServicePullClient.ACTION), entry)
        }
        assertEquals(listOf(ComponentName("renamed.manager", "renamed.manager.PullService")), FrameworkServicePullClient.discoverServices(app))
    }
}
