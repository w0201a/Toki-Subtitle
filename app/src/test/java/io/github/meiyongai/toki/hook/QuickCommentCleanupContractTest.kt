package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import io.github.meiyongai.toki.subtitle.model.LayoutElement
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class QuickCommentCleanupContractTest {
    class Component {
        fun onViewCreated(view: View) = Unit
        fun getContainerView(): View? = null
        fun getContentView(): View? = null
    }
    class InvalidComponent { fun onViewCreated(view: View): String = "not a lifecycle method" }

    @Test fun lifecycleAndOwnedViewContractsAreCheckedBeforeRegistration() {
        val contract = QuickCommentCleanupHook.Contract(Component::class.java)
        assertEquals("onViewCreated", contract.created.name)
        assertEquals(setOf("getContainerView", "getContentView"), contract.views.map { it.name }.toSet())
        assertNull(contract.attached)
        assertThrows(IllegalStateException::class.java) { QuickCommentCleanupHook.Contract(InvalidComponent::class.java) }
        assertTrue(QuickCommentCleanupHook.componentNames.any { it.endsWith(".NewBottomDefaultCommentInputAssem") })
    }

    @Test fun existingQuickCommentSwitchHasOneIndependentOwnerWithoutNavigationScans() {
        val key = LayoutElement.QUICK_COMMENT.key
        assertEquals(listOf("QuickCommentCleanupHook"), ConfigSchema.featureSwitches.filterValues { key in it }.keys.toList())
        val requested = ConfigSnapshot(mapOf(key to true))
        val plan = HostFeaturePlan(requested)
        assertEquals(setOf("QuickCommentCleanupHook"), plan.features)
        assertTrue(plan.symbols.isEmpty())
        val late = HostFeaturePlan(ConfigSnapshot(emptyMap()))
        assertEquals(listOf("QuickCommentCleanupHook"), late.liveCandidates(requested))
        assertTrue(late.admitLive("QuickCommentCleanupHook"))
        assertTrue(late.apply(requested).boolean(key))
        assertFalse(late.apply(requested).boolean(LayoutElement.COMMENT.key))
    }

    @Test fun authorLabelDisplayCanPrepareWithoutUnrelatedFormatterSymbolScan() {
        val requested = ConfigSnapshot(mapOf("show_author_location" to true))
        val plan = HostFeaturePlan(requested)
        assertEquals(setOf("AuthorLocationHook"), plan.directHostFeatures)
        assertTrue(plan.symbols.isEmpty())
        val late = HostFeaturePlan(ConfigSnapshot(emptyMap()))
        assertTrue(late.admitLive("AuthorLocationHook"))
        assertTrue(late.apply(requested).boolean("show_author_location"))
    }
}
