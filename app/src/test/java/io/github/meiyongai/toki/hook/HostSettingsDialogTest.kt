package io.github.meiyongai.toki.subtitle.hook

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.provider.ConfigStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="b+zh+Hans-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class HostSettingsDialogTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun embeddedSettingsShowEntryAndPersistRequestedValuesWithoutExternalActivity() {
        fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        for (name in listOf("listeners", "stateListeners")) (field(ConfigClient::class.java,name).get(null) as MutableCollection<*>).clear()
        for (name in listOf("initialized", "host")) field(ConfigClient::class.java,name).set(null,false)
        for (name in listOf("connectionChanged", "hostSessionTransform", "effectiveCache")) field(ConfigClient::class.java,name).set(null,null)
        val store=field(ConfigClient::class.java,"store").get(null)
        for (name in listOf("current", "syncError", "local", "remote")) field(ConfigStore::class.java,name).set(store,null)
        for (name in listOf("isReady", "host")) field(ConfigStore::class.java,name).set(store,false)
        val app=RuntimeEnvironment.getApplication()
        val remote=app.getSharedPreferences("host-dialog-remote",Context.MODE_PRIVATE)
        remote.edit().putInt(ConfigStore.PROTOCOL,1).putLong(ConfigStore.REVISION,1).putBoolean("video_translate_enabled",true).commit()
        ConfigClient.initHost({remote},{})
        ConfigClient.attachHostSettings(app)
        ConfigClient.configureHostSession { snapshot ->
            io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot(snapshot.configuration()+mapOf("video_translate_enabled" to false),snapshot.revision,snapshot.importRevision)
        }
        val owner=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val activity=owner.get()
        lateinit var dialog: HostSettingsDialog
        try {
            compose.runOnUiThread {
                dialog=HostSettingsDialog(activity,ModuleResources(app.applicationInfo,javaClass.classLoader!!))
                dialog.show()
            }
            compose.onNodeWithText("Toki 设置").assertIsDisplayed()
            assertTrue(ConfigClient.getBoolean(dialog.context,"video_translate_enabled"))
            assertFalse(ConfigClient.getBoolean("video_translate_enabled"))
            compose.onNodeWithText("字幕设置").assertIsDisplayed()
            compose.runOnUiThread { ConfigClient.putString(dialog.context,"subtitle_size","30") }
            assertEquals("30",ConfigClient.requestedSnapshot().string("subtitle_size"))
            assertEquals("30",app.getSharedPreferences("toki_host_settings",Context.MODE_PRIVATE).getString("subtitle_size",null))
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            compose.runOnUiThread { dialog.dismiss() }
            owner.pause().stop().destroy()
        }
    }
}
