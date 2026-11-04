package io.github.meiyongai.toki.subtitle.provider

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ProviderInfo
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class HostSettingsProviderTest {
    private val host = ManualHostAdaptation.packages[0]
    private lateinit var context: Context
    private lateinit var provider: SubtitlePositionProvider
    @Before fun prepare() {
        fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        for (name in listOf("listeners", "stateListeners")) (field(ConfigClient::class.java, name).get(null) as MutableCollection<*>).clear()
        for (name in listOf("initialized", "host")) field(ConfigClient::class.java, name).set(null, false)
        for (name in listOf("connectionChanged", "hostSessionTransform", "effectiveCache")) field(ConfigClient::class.java, name).set(null, null)
        val store = field(ConfigClient::class.java, "store").get(null)
        for (name in listOf("current", "syncError", "local", "remote")) field(ConfigStore::class.java, name).set(store, null)
        for (name in listOf("isReady", "host")) field(ConfigStore::class.java, name).set(store, false)
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = "io.github.meiyongai.toki.subtitle"
            override fun getApplicationContext(): Context = this
        }
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = host
            applicationInfo = ApplicationInfo().apply { packageName = host; uid = 12051 }
        })
        provider = org.robolectric.Robolectric.buildContentProvider(SubtitlePositionProvider::class.java).create().get()
        context = checkNotNull(provider.context)
        ConfigClient.init(context)
        ShadowBinder.setCallingUid(12051)
    }
    @After fun finish() { ShadowBinder.setCallingUid(android.os.Process.myUid()); HostSettingsMirror.detach() }
    private fun send(patch: HostSettingsPatch) = provider.call(HostSettingsWire.METHOD, null,
        Bundle().apply { putString("patch", HostSettingsWire.encode(patch)) })

    @Test fun authorizedNoRootHostUpdatesStandaloneStateAndNotifiesOpenUi() {
        val states = mutableListOf<ConfigSnapshot?>()
        val listener = ConfigClient.addStateListener { states.add(it) }
        val reply = send(HostSettingsPatch(host, "no-root", mapOf("feed_remove_ads" to true, "subtitle_size" to "27")))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(reply.getBoolean("saved")); assertEquals("no-root", reply.getString("id"))
        assertEquals("27", ConfigClient.getString("subtitle_size", null))
        assertTrue(states.any { it?.string("subtitle_size") == "27" })
        assertEquals(12051, Binder.getCallingUid()) // Calling identity is restored after nested publication.
        listener.close()
    }
    @Test fun rootedHostAlsoPublishesTheMirroredEditToFrameworkPreferences() {
        val remote = context.getSharedPreferences("framework-output", Context.MODE_PRIVATE)
        ConfigClient.connectFramework { remote }
        val reply = send(HostSettingsPatch(host, "root", mapOf("feed_remove_ads" to true)))
        assertTrue(reply.getBoolean("saved")); assertTrue(remote.getBoolean("feed_remove_ads", false))
        assertTrue(remote.getLong(ConfigStore.REVISION, 0) > 0)
        assertFalse(remote.contains(HostSettingsWire.RECEIPTS))
    }
    @Test fun unknownUidAndImpersonationCannotWriteAnySettings() {
        val before = ConfigClient.snapshot().configuration()
        val patch = HostSettingsPatch(host, "bad", mapOf("feed_remove_ads" to true))
        ShadowBinder.setCallingUid(12052)
        assertThrows(IllegalStateException::class.java) { send(patch) }
        ShadowBinder.setCallingUid(12051)
        assertThrows(IllegalStateException::class.java) { send(patch.copy(host = ManualHostAdaptation.packages[1])) }
        assertThrows(IllegalStateException::class.java) { provider.call("settings-read", null, null) }
        assertEquals(before, ConfigClient.snapshot().configuration())
    }
    @Test fun duplicateIpcDoesNotReplayAfterANewerStandaloneEdit() {
        val patch = HostSettingsPatch(host, "same", mapOf("subtitle_size" to "24"))
        val first = send(patch).getLong("revision")
        ConfigClient.putString(context, "subtitle_size", "36")
        assertEquals(first, send(patch).getLong("revision"))
        assertEquals("36", ConfigClient.getString("subtitle_size", null))
    }
}
