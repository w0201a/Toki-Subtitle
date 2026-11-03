package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.LayoutElement
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot

/** Keep legacy cleanup keys functional while separating explicit bottom-search and template controls. */
internal object LayoutCleanupPolicy {
    fun hidden(element: LayoutElement, config: ConfigSnapshot?): Boolean {
        if (config == null) return false
        if (config.boolean(element.key)) return true
        return when (element) {
            LayoutElement.BOTTOM_SEARCH_RECOMMENDATIONS -> config.boolean(LayoutElement.SEARCH_LABEL.key)
            LayoutElement.VIDEO_TEMPLATE -> config.boolean(LayoutElement.CREATION_BUTTONS.key)
            else -> false
        }
    }
}
