package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.meiyongai.toki.subtitle.model.LayoutElement

class LayoutComponentSelectorTest {
    private open class Banner
    private open class Playlist : Banner()
    private class PlaylistVariant : Playlist()
    private open class FcpStandardTemplateBase
    private class FcpStandardTemplateVerify : FcpStandardTemplateBase()

    /** 验证顶部业务入口与视频开关统一，活动和奖励分别管理。无参数，返回 Unit。Callers: JUnit。 */
    @Test fun toolbarUsesVerifiedSemanticTags() {
        assertEquals(LayoutElement.TAKO, LayoutElement.toolbar("tako"))
        assertEquals(LayoutElement.REWARDS, LayoutElement.toolbar("coin"))
        assertEquals(LayoutElement.QUICK_MESSAGE, LayoutElement.toolbar("dm_notice"))
        assertEquals(LayoutElement.CREATION_BUTTONS, LayoutElement.toolbar("story_camera"))
        assertEquals(LayoutElement.TOP_SEARCH, LayoutElement.toolbar("search"))
        assertEquals(LayoutElement.ACTIVITIES, LayoutElement.toolbar("special_event"))
        assertNull(LayoutElement.toolbar("unknown"))
    }

    /** 共享标准渲染器及验证变体不能仅凭类名绑定为视频模板。 */
    @Test fun genericStandardRenderersAreRejectedBeforeClassWideTemplateRegistration() {
        val prefix = "com.ss.android.ugc.feed.platform.cell.interact.next.standardized.template."
        val names = listOf(
            "button.ui.FcpStandardTemplateAssem",
            "button.template.FcpStandardButtonAssem",
            "tag.template.FcpStandardTagAssem",
            "button.FcpStandardButtonVerifyAssem",
            "tag.verify.FcpStandardTagVerifyAssem",
            "tag.verify.FcpStandardBottomTagVerifyAssem")
        for (name in names) assertEquals(name, false,
            TemplateComponentSemantics.permitsClassOnlyMapping(prefix + name, LayoutElement.VIDEO_TEMPLATE))
    }

    /** 保留已确认的模板按钮与其他既有语义；防护不是整批关掉所有净化。 */
    @Test fun templateSpecificComponentAndOtherExplicitPoliciesRemainAvailable() {
        assertEquals(true, TemplateComponentSemantics.permitsClassOnlyMapping(
            "com.ss.android.ugc.aweme.feed.assem.pugc.VideoTemplateButtonAssem", LayoutElement.VIDEO_TEMPLATE))
        assertEquals(true, TemplateComponentSemantics.permitsClassOnlyMapping(
            "com.ss.android.ugc.feed.platform.cell.interact.next.standardized.template.button.ui.FcpStandardTemplateAssem",
            LayoutElement.CREATION_BUTTONS))
    }

    /** 验证父子 Hook 共用入口时具体语义稳定。无参数，返回 Unit。Callers: JUnit。 */
    @Test fun nearestOwnerWinsRegardlessOfRegistrationOrder() {
        val entries = listOf(Banner::class.java to "banner", Playlist::class.java to "playlist")
        assertEquals("playlist", LayoutComponentSelector.select(PlaylistVariant(), entries))
        assertEquals("playlist", LayoutComponentSelector.select(PlaylistVariant(), entries.reversed()))
        assertEquals("banner", LayoutComponentSelector.select(Banner(), entries))
        assertNull(LayoutComponentSelector.select(Any(), entries))
    }

    /** 只证明继承链匹配机制，不证明标准渲染器的业务归属。无参数，返回 Unit。Callers: JUnit。 */
    @Test fun inheritedMatchDoesNotByItselfProveTemplateBusinessSemantics() {
        val entries = listOf(FcpStandardTemplateBase::class.java to LayoutElement.VIDEO_TEMPLATE)
        assertEquals(LayoutElement.VIDEO_TEMPLATE, LayoutComponentSelector.select(FcpStandardTemplateVerify(), entries))
        assertEquals(LayoutElement.VIDEO_TEMPLATE, LayoutComponentSelector.select(FcpStandardTemplateBase(), entries))
        assertNull(LayoutComponentSelector.select(Any(), entries))
    }
}
