package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.model.LayoutElement

/** FCP "template" means a shared UI schema, not a video/effect-template business identity.
 * Class existence, onViewCreated, and inheritance are insufficient evidence for class-wide hiding.
 * Future standard-renderer support needs a separate per-instance business/anchor/root contract.
 */
internal object TemplateComponentSemantics {
    private const val SHARED_SCHEMA = "com.ss.android.ugc.feed.platform.cell.interact.next.standardized.template."

    fun permitsClassOnlyMapping(className: String, element: LayoutElement): Boolean =
        element != LayoutElement.VIDEO_TEMPLATE || !className.startsWith(SHARED_SCHEMA)
}
