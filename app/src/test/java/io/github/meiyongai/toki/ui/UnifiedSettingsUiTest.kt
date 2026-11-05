package io.github.meiyongai.toki.subtitle.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.ui.screen.HomeScreen
import io.github.meiyongai.toki.subtitle.ui.screen.SettingsCategory
import io.github.meiyongai.toki.subtitle.ui.screen.TokiSettingsHub
import io.github.meiyongai.toki.subtitle.util.AppLanguage
import io.github.meiyongai.toki.subtitle.util.LocaleHelper
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UnifiedSettingsUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<ComponentActivity>
    private lateinit var view: ComposeView
    @Before fun prepare() {
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        ConfigClient.init(controller.get())
        view = ComposeView(controller.get()); controller.get().setContentView(view)
    }
    @After fun finish() {
        compose.runOnUiThread { view.disposeComposition() }
        controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idle()
    }
    private fun scene(dark: Boolean = false, large: Boolean = false, content: @Composable () -> Unit) {
        view.setContent {
            val configuration = Configuration(controller.get().resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val context = LocaleHelper.wrapContext(controller.get(), AppLanguage.SIMPLIFIED_CHINESE, configuration)
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalDensity provides Density(1f, if (large) 1.5f else 1f), LocalLayoutDirection provides LayoutDirection.Ltr) {
                LocalizedTokiApp(systemConfiguration = context.resources.configuration) { TokiAppTheme { Surface(Modifier.fillMaxSize(), color = androidx.compose.material3.MaterialTheme.colorScheme.background) { content() } } }
            }
        }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        val directory = File("build/reports/unified-ui"); check(directory.isDirectory || directory.mkdirs())
        val image = compose.runOnIdle {
            val decor = controller.get().window.decorView
            Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888).also { decor.draw(Canvas(it)) }
        }
        File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
        image.recycle()
    }
    @Test fun everyCategoryIsReachableAndReturningDoesNotChangeFeatureConfiguration() {
        val before = ConfigClient.snapshot().configuration()
        scene { TokiSettingsHub() }
        capture("settings-hub-light")
        for (category in SettingsCategory.entries) {
            compose.onNodeWithTag("settings-category-" + category.name).performScrollTo().performClick()
            compose.onNodeWithTag("settings-category-" + category.name).assertDoesNotExist()
            if (category == SettingsCategory.SUBTITLES) capture("subtitles-light")
            compose.runOnUiThread { controller.get().onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.onNodeWithTag("settings-category-" + category.name).assertExists()
        }
        compose.runOnIdle { assertEquals(before, ConfigClient.snapshot().configuration()) }
    }
    @Test fun darkLargeTypeHubKeepsAllCategoriesScrollable() {
        scene(dark = true, large = true) { TokiSettingsHub() }
        for (category in SettingsCategory.entries) compose.onNodeWithTag("settings-category-" + category.name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-category-SUBTITLES").performScrollTo()
        capture("settings-hub-dark-large")
    }
    @Test fun standaloneOverviewUsesTheSameThemeAndPreservesRealStatusContent() {
        scene { HomeScreen(onHookStatusClick = {}, onLanguageClick = {}) }
        compose.onNodeWithText("Toki Subtitle").assertExists()
        capture("manager-overview-light")
    }
}
