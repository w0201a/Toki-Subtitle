package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.LayoutElement
import io.github.meiyongai.toki.subtitle.model.LayoutGroup
import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import org.junit.Assert.*
import org.junit.Test

class LayoutCleanupPolicyTest {
    @Test fun bottomSuggestionsHaveAnIndependentValidatedSwitchWithoutHidingTopSearch() {
        val option = LayoutElement.BOTTOM_SEARCH_RECOMMENDATIONS
        assertEquals(LayoutGroup.BOTTOM, option.group)
        val values = mapOf(option.key to true)
        ConfigSchema.validate(values)
        val config = ConfigSnapshot(values)
        assertTrue(LayoutCleanupPolicy.hidden(option, config))
        assertFalse(LayoutCleanupPolicy.hidden(LayoutElement.TOP_SEARCH, config))
        assertFalse(LayoutCleanupPolicy.hidden(LayoutElement.BOTTOM_HOME, config))
        assertFalse(LayoutCleanupPolicy.hidden(LayoutElement.SEARCH_LABEL, config))
        assertFalse(LayoutCleanupPolicy.hidden(option, ConfigSnapshot(emptyMap())))
        assertFalse(LayoutCleanupPolicy.hidden(option, null))
    }

    @Test fun oldSearchAndCreationTogglesRemainBackwardCompatible() {
        assertTrue(LayoutCleanupPolicy.hidden(LayoutElement.BOTTOM_SEARCH_RECOMMENDATIONS,
            ConfigSnapshot(mapOf(LayoutElement.SEARCH_LABEL.key to true))))
        assertTrue(LayoutCleanupPolicy.hidden(LayoutElement.VIDEO_TEMPLATE,
            ConfigSnapshot(mapOf(LayoutElement.CREATION_BUTTONS.key to true))))
        assertFalse(LayoutCleanupPolicy.hidden(LayoutElement.TOP_SEARCH,
            ConfigSnapshot(mapOf(LayoutElement.SEARCH_LABEL.key to true))))
    }
}
