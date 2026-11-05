package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.LayoutElement
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class IndividualCleanupPolicyTest {
    @Test fun eachIndividualSwitchDefaultsOffAndDoesNotHideItsSiblingsOrAggregate() {
        val children = LayoutElement.entries.filter { it.aggregate != null }
        assertEquals(16, children.size)
        for (child in children) {
            assertFalse(ConfigSchema.booleanDefault(child.key))
            val input = mapOf(child.key to true); ConfigSchema.validate(input); val config = ConfigSnapshot(input)
            assertTrue(LayoutCleanupPolicy.hidden(child, config))
            assertFalse(LayoutCleanupPolicy.hidden(child.aggregate!!, config))
            children.filter { it != child }.forEach { assertFalse(it.name, LayoutCleanupPolicy.hidden(it, config)) }
        }
    }
    @Test fun oldAggregateStillHidesItsOwnChildrenOnlyAndDisablingItRestoresIndependentChoices() {
        val children = LayoutElement.entries.filter { it.aggregate != null }
        for (parent in children.mapNotNull { it.aggregate }.toSet()) {
            val config = ConfigSnapshot(mapOf(parent.key to true))
            children.forEach { assertEquals(it.name, it.aggregate == parent, LayoutCleanupPolicy.hidden(it, config)) }
            val own = children.first { it.aggregate == parent }
            val selected = ConfigSnapshot(mapOf(parent.key to false, own.key to true))
            children.forEach { assertEquals(it.name, it == own, LayoutCleanupPolicy.hidden(it, selected)) }
        }
    }
    @Test fun anchorCategoriesAreDisjointAndUnknownTypesStayNative() {
        val codes = listOf(28, -28, 64, 120, 110, 111, 31, 87, 29, 65, 66, 78, 127)
        assertEquals(TemplateAnchorPolicy.controls, codes.mapNotNull(TemplateAnchorPolicy::element).toSet())
        for (unknown in listOf(0, 1, -1, 1100, Int.MIN_VALUE)) assertNull(TemplateAnchorPolicy.element(unknown))
        for (control in TemplateAnchorPolicy.controls) {
            assertFalse(ConfigSchema.booleanDefault(control.key))
            val config = ConfigSnapshot(mapOf(control.key to true))
            assertEquals(setOf(control), TemplateAnchorPolicy.enabled(config))
        }
        assertTrue(TemplateAnchorPolicy.enabled(null).isEmpty())
    }
    @Test fun templateAggregateKeepsOldScopeAndEveryKeyHasExactlyOneInstallationOwner() {
        assertEquals(TemplateAnchorPolicy.controls + LayoutElement.VIDEO_TEMPLATE,
            TemplateAnchorPolicy.enabled(ConfigSnapshot(mapOf(LayoutElement.VIDEO_TEMPLATE.key to true))))
        // The legacy creation group used to hide its own template button, not every shared effect anchor.
        assertTrue(TemplateAnchorPolicy.enabled(ConfigSnapshot(mapOf(LayoutElement.CREATION_BUTTONS.key to true))).isEmpty())
        val owners = ConfigSchema.featureSwitches
        for (control in TemplateAnchorPolicy.controls) {
            assertEquals(listOf("TemplateCleanupHook"), owners.filterValues { control.key in it }.keys.toList())
            val plan = HostFeaturePlan(ConfigSnapshot(mapOf(control.key to true)))
            assertEquals(setOf("TemplateCleanupHook"), plan.directHostFeatures)
            assertFalse(plan.requiresScan)
        }
        for (control in LayoutElement.entries.filter { it.aggregate != null })
            assertEquals(listOf("LayoutCleanupHook"), owners.filterValues { control.key in it }.keys.toList())
    }
}
