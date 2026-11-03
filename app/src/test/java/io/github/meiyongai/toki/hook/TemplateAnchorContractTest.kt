package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class TemplateAnchorContractTest {
    class Anchor(var kind: Int) { fun getType(): Int = kind }
    class Video
    class Provider(var anchor: Anchor?, var video: Video?) {
        fun providerDisplayAnchor(): Anchor? = anchor
        fun providerAweme(): Video? = video
    }
    class Renderer(var root: View?) {
        @JvmField var LLJJLIIIJLLLLLLLZ: Provider? = null
        fun rootView(): View? = root
        fun onBind() = Unit
        fun unBind() = Unit
        fun onDestroy() = Unit
    }
    class Button(var content: View?) {
        @JvmField var LLJLL: View? = null
        fun getContentView(): View? = content
        fun onViewCreated(view: View) = Unit
        fun i4(value: Any) = Unit
        fun unBind() = Unit
        fun onDestroyView() = Unit
    }
    private fun frame() = FrameLayout(RuntimeEnvironment.getApplication())
    @Test fun rendererReadsTheSelectedBusinessAnchorAndItsRootNotAllVideoAnchors() {
        val view = frame(); val anchor = Anchor(78); val video = Video()
        val owner = Renderer(view).apply { LLJJLIIIJLLLLLLLZ = Provider(anchor, video) }
        val contract = TemplateAnchorContract.renderer(Renderer::class.java, Provider::class.java, Anchor::class.java, Video::class.java, setOf(28, 78))
        val value = contract.current(owner)!!
        assertSame(view, value.root); assertSame(anchor, value.identity); assertSame(video, value.context); assertTrue(value.target)
        anchor.kind = 1
        assertFalse(contract.current(owner)!!.target)
        owner.LLJJLIIIJLLLLLLLZ!!.anchor = null
        assertNull(contract.current(owner)); assertNull(contract.current(Any()))
        assertEquals(setOf("onBind", "rootView"), contract.bound.map { it.name }.toSet())
    }
    @Test fun videoAndRootAvailabilityAreRequiredAndUnknownCapcutOrDonationAreNotWhitelistedByName() {
        val owner = Renderer(frame()).apply { LLJJLIIIJLLLLLLLZ = Provider(Anchor(78), null) }
        val contract = TemplateAnchorContract.renderer(Renderer::class.java, Provider::class.java, Anchor::class.java, Video::class.java, setOf(78))
        assertNull(contract.current(owner))
        owner.LLJJLIIIJLLLLLLLZ!!.video = Video(); owner.root = null
        assertNull(contract.current(owner))
        assertFalse("CAPCUT" in TemplateAnchorContract.allowedAnchorNames)
        assertFalse("DONATION_STICKER" in TemplateAnchorContract.allowedAnchorNames)
        assertTrue("PROP_COMMERCIAL_EFFECT" in TemplateAnchorContract.allowedAnchorNames)
    }
    @Test fun specificButtonRequiresItsActualButtonInsideItsOwnContentRoot() {
        val root = frame(); val child = frame().also(root::addView)
        val owner = Button(root).apply { LLJLL = child }
        val contract = TemplateAnchorContract.specificButton(Button::class.java)
        assertSame(root, contract.current(owner)?.root); assertTrue(contract.current(owner)!!.target)
        owner.LLJLL = frame()
        assertNull(contract.current(owner))
        assertEquals(setOf("unBind", "onDestroyView"), contract.released.map { it.name }.toSet())
        assertEquals(setOf("onViewCreated", "i4"), contract.bound.map { it.name }.toSet())
    }
}
