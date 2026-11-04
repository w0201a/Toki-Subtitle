package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import io.github.meiyongai.toki.subtitle.model.LayoutElement
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class DanmakuEntranceHookTest {
    class NativeEntrance(val button: View?, val expanded: View?) {
        fun onViewCreated(view: View) { }
        fun unBind() { }
    }
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun contract() = DanmakuEntranceHook.Contract(NativeEntrance::class.java, listOf("button", "expanded"))
    @Test fun hidesDedicatedEntranceChildrenWithoutHidingVideoAndRestoresNativeVisibility() {
        val root = FrameLayout(context); val video = View(context); val button = View(context)
        val expanded = View(context).apply { visibility = View.INVISIBLE }
        root.addView(video); root.addView(button); root.addView(expanded)
        val targets = contract().targets(NativeEntrance(button, expanded), root)
        assertEquals(setOf(button, expanded), targets)
        val gate = CleanViewGate(); val rule = CleanViewGate.Owner(true, View.GONE)
        gate.replace(rule, targets); gate.refresh()
        assertEquals(View.GONE, button.visibility); assertEquals(View.VISIBLE, root.visibility)
        assertEquals(View.VISIBLE, video.visibility)
        assertEquals(View.GONE, gate.visibility(button, View.VISIBLE))
        rule.clean = false; gate.refresh()
        assertEquals(View.VISIBLE, button.visibility); assertEquals(View.INVISIBLE, expanded.visibility)
        gate.detach(rule); assertSame(root, video.parent)
    }
    @Test fun callbackRootDetachedChildrenAndUnrelatedInstancesAreNeverHidden() {
        val root = FrameLayout(context); val outside = FrameLayout(context); val foreign = View(context)
        outside.addView(foreign)
        assertTrue(contract().targets(NativeEntrance(root, foreign), root).isEmpty())
        assertTrue(contract().targets(NativeEntrance(null, null), root).isEmpty())
        assertTrue(contract().targets(Any(), root).isEmpty())
        assertEquals(1, contract().released.size)
    }
    @Test fun dedicatedSwitchDoesNotRequireNavigationScanOrEnableOtherCleanupFeatures() {
        val key = LayoutElement.DANMAKU_ENTRANCE.key
        assertFalse(ConfigSchema.booleanDefault(key)); ConfigSchema.validate(mapOf(key to true))
        assertEquals(listOf(key), ConfigSchema.featureSwitches.getValue("DanmakuEntranceHook"))
        assertFalse(key in ConfigSchema.featureSwitches.getValue("LayoutCleanupHook"))
        val plan = HostFeaturePlan(ConfigSnapshot(mapOf(key to true)))
        assertEquals(setOf("DanmakuEntranceHook"), plan.features); assertFalse(plan.requiresScan)
    }
}
