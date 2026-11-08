package io.github.meiyongai.toki.subtitle.ui.screen

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.update.AppRelease
import io.github.meiyongai.toki.subtitle.update.ReleaseSource
import io.github.meiyongai.toki.subtitle.update.ReleaseVersion
import io.github.meiyongai.toki.subtitle.update.UpdateEngine
import io.github.meiyongai.toki.subtitle.update.UpdateState
import io.github.meiyongai.toki.subtitle.update.UpdateStateStore
import io.github.meiyongai.toki.subtitle.util.AppLanguage
import io.github.meiyongai.toki.subtitle.util.LocaleHelper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AppUpdateUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var view: ComposeView
    private class Store : UpdateStateStore {
        var state = UpdateState()
        override fun read() = state
        override fun write(state: UpdateState) { this.state = state }
    }
    @Before fun createHost() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        view = ComposeView(controller.get()); controller.get().setContentView(view)
    }
    @After fun destroyHost() {
        compose.runOnUiThread { view.disposeComposition() }
        controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle()
    }
    private fun scene(source: ReleaseSource, store: Store = Store(), language: AppLanguage = AppLanguage.ENGLISH, onOpen: (String) -> Unit = {}) {
        val engine = UpdateEngine(ReleaseVersion(2, 1, 23), store, source, { 1_000_000 })
        view.setContent {
            val context = LocaleHelper.wrapContext(controller.get(), language, controller.get().resources.configuration)
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
                MaterialTheme { Surface { Column(Modifier.width(360.dp).padding(16.dp)) {
                    RootOnlyReleaseNotice()
                    AppUpdateSection(onOpenUrl = onOpen, engine = engine)
                } } }
            }
        }
        compose.waitForIdle()
    }
    private fun awaitIdle(calls: AtomicInteger) {
        compose.waitUntil(10_000) { calls.get() > 0 }
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("manual-update-check").assertIsEnabled(); true }.getOrDefault(false)
        }
    }
    @Test fun noUpdateIsSilentAutomaticallyAndManualCheckShowsInlineFeedbackOnly() {
        val calls = AtomicInteger(); scene(ReleaseSource { calls.incrementAndGet(); null })
        awaitIdle(calls)
        compose.onAllNodesWithText("You are using the latest available stable version.").assertCountEquals(0)
        compose.onNodeWithTag("manual-update-check").performClick()
        compose.waitUntil(10_000) { calls.get() == 2 }
        compose.onNodeWithText("You are using the latest available stable version.").assertIsDisplayed()
        compose.onAllNodesWithText("New features and bug fixes").assertCountEquals(0)
    }
    @Test fun newVersionShowsFeatureAndFixNotesOnceAndOnlyOpensOfficialReleaseAfterClick() {
        val calls = AtomicInteger(); val store = Store(); var opened: String? = null
        val item = AppRelease(ReleaseVersion(2, 1, 24), "v2.1.24", "New: subtitles.\nFix: comment performance.")
        scene(ReleaseSource { calls.incrementAndGet(); item }, store, onOpen = { opened = it })
        compose.waitUntil(10_000) { compose.onAllNodesWithText("New version 2.1.24").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(item.notes).assertIsDisplayed(); assertNull(opened)
        compose.onNodeWithText("View update and download").performClick()
        assertEquals("https://github.com/w0201a/Toki-Subtitle/releases/tag/v2.1.24", opened)
        compose.onNodeWithText("Close").performClick()
        assertEquals(item.version, store.state.notified)
        compose.runOnUiThread { controller.pause(); controller.resume() }
        compose.waitForIdle(); assertEquals(1, calls.get())
        compose.onAllNodesWithText("New version 2.1.24").assertCountEquals(0)
    }
    @Test fun rootOnlyWarningAndCurrentFeatureFixNotesAreVisibleInChinese() {
        val calls = AtomicInteger(); val chinese = AppLanguage.entries.first { it.languageTag == "zh-Hans" }
        scene(ReleaseSource { calls.incrementAndGet(); null }, language = chinese); awaitIdle(calls)
        compose.onNodeWithText("本版本需要 Root").assertIsDisplayed()
        compose.onNodeWithTag("root-only-release-notice").assertIsDisplayed()
        val output = File("build/reports/app-updates").apply { check(isDirectory || mkdirs()) }
        val bitmap = compose.runOnIdle {
            val decor = controller.get().window.decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        File(output, "root-only-update-panel-zh.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        compose.onNodeWithTag("current-release-notes").performClick()
        compose.onNodeWithText("新增功能与 Bug 修复").assertIsDisplayed()
        val context = LocaleHelper.wrapContext(controller.get(), chinese, controller.get().resources.configuration)
        compose.onNodeWithText(context.getString(R.string.app_update_current_body)).assertIsDisplayed()
    }
    @Test fun automaticFailureIsSilentAndManualFailureIsInlineNotAPopup() {
        val calls = AtomicInteger(); scene(ReleaseSource { calls.incrementAndGet(); error("offline") }); awaitIdle(calls)
        compose.onAllNodesWithText("Could not check. You can try again manually later.").assertCountEquals(0)
        compose.onNodeWithTag("manual-update-check").performClick()
        compose.waitUntil(10_000) { calls.get() == 2 }
        compose.onNodeWithText("Could not check. You can try again manually later.").assertIsDisplayed()
        compose.onAllNodesWithText("New features and bug fixes").assertCountEquals(0)
    }
}
