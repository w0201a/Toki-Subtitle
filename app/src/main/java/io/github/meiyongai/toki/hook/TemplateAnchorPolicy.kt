package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.LayoutElement
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot

/** Only inspected anchor enum values are classified. A shared renderer or visible label is never sufficient. */
internal object TemplateAnchorPolicy {
    val controls = setOf(LayoutElement.ANCHOR_EFFECT, LayoutElement.ANCHOR_CAPCUT, LayoutElement.ANCHOR_AI,
        LayoutElement.ANCHOR_PHOTO, LayoutElement.ANCHOR_OTHER_TEMPLATE)
    fun element(code: Int): LayoutElement? = when (code) {
        28, -28, 64, 120 -> LayoutElement.ANCHOR_EFFECT
        110 -> LayoutElement.ANCHOR_CAPCUT
        111 -> LayoutElement.ANCHOR_AI
        31, 87 -> LayoutElement.ANCHOR_PHOTO
        29, 65, 66, 78, 127 -> LayoutElement.ANCHOR_OTHER_TEMPLATE
        else -> null
    }
    fun enabled(config: ConfigSnapshot?): Set<LayoutElement> = if (config == null) emptySet() else
        if (config.boolean(LayoutElement.VIDEO_TEMPLATE.key)) controls + LayoutElement.VIDEO_TEMPLATE
        else controls.filterTo(linkedSetOf()) { config.boolean(it.key) }
}
