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
    class Anchor(var kind: Int, var component: String = "anchor") { fun getType(): Int = kind; fun getComponentKey(): String = component }
    class Video { var anchors = listOf<Anchor>() }
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
        assertTrue("CAPCUT" in TemplateAnchorContract.allowedAnchorNames)
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
    class Area(var root: View) {
        @JvmField var LLJJJJJIL: android.widget.RelativeLayout? = null
        fun getContainerView(): View = root
        fun onViewCreated(view: View) = Unit
        fun i4(value: Any) = Unit
        fun unBind() = Unit
        fun onDestroyView() = Unit
    }
    class Params(var video: Video?) { fun getAweme(): Video? = video }
    class Drawing(view: View) {
        @JvmField var LJIILL: Params? = null
        @JvmField var LJIIJ: View = view
        @JvmField var LJI: String = "anchor"
    }
    class Standard {
        @JvmField var LLJZ: Drawing? = null
        fun onViewCreated(view: View) = Unit
        fun i4(value: Any) = Unit
        fun unBind() = Unit
        fun onDestroyView() = Unit
    }
    @Test fun capcutAreaTargetsOnlyItsCheckedChildNeverTheVideoPage() {
        val root = frame()
        val player = android.view.SurfaceView(root.context); root.addView(player)
        val child = android.widget.RelativeLayout(root.context).apply { id = 2131367119 }; root.addView(child)
        val owner = Area(root).apply { LLJJJJJIL = child }
        val contract = TemplateAnchorContract.capcutArea(Area::class.java)
        val value = checkNotNull(contract.current(owner))
        assertSame(child, value.root); assertNotSame(root, value.root)
        assertEquals(io.github.meiyongai.toki.subtitle.model.LayoutElement.ANCHOR_CAPCUT, value.element)
        owner.LLJJJJJIL = android.widget.RelativeLayout(root.context)
        assertNull(contract.current(owner))
    }
    @Test fun standardizedTemplateComponentRequiresItsExactComponentAnchorAndVideo() {
        val anchor = Anchor(54)
        val video = Video().apply { anchors = listOf(anchor, Anchor(1, "unrelated")) }
        val owner = Standard().apply { LLJZ = Drawing(frame()).apply { LJIILL = Params(video) } }
        val contract = TemplateAnchorContract.standardComponent(Standard::class.java, Drawing::class.java, Params::class.java, Anchor::class.java, Video::class.java, setOf(54,110))
        assertTrue(contract.current(owner)!!.target)
        assertEquals(io.github.meiyongai.toki.subtitle.model.LayoutElement.ANCHOR_CAPCUT, contract.current(owner)!!.element)
        anchor.kind = 1
        assertFalse(contract.current(owner)!!.target)
        anchor.component = "wrong-component"
        assertNull(contract.current(owner))
        anchor.component = "anchor"; video.anchors = listOf(anchor, Anchor(110))
        assertNull("Ambiguous component ownership is never hidden", contract.current(owner))
        owner.LLJZ!!.LJIILL!!.video = null
        assertNull(contract.current(owner))
    }

    class Business(var anchors: List<Anchor>) { fun eb(): List<Anchor> = anchors }
    class LegacyDrawing(val view: View) { fun rootView(): View = view }
    class BindInput
    class BindOwner
    class Coordinator(view: View, video: Video, business: Business) {
        @JvmField var LJI: Business = business
        @JvmField var LJII: Video = video
        val drawing = LegacyDrawing(view)
        fun LJ(): LegacyDrawing = drawing
        fun LJII(input: BindInput, owner: BindOwner): Int = 1
        fun LIZJ() = Unit
        fun LJFF() = Unit
    }
    @Test fun legacyCoordinatorNeverHidesASharedMultiAnchorArea() {
        val source = Business(listOf(Anchor(54)))
        val owner = Coordinator(frame(), Video(), source)
        val contract = TemplateAnchorContract.legacyCoordinator(Coordinator::class.java, Business::class.java,
            LegacyDrawing::class.java, Anchor::class.java, Video::class.java, BindInput::class.java, BindOwner::class.java, setOf(54))
        assertTrue(contract.current(owner)!!.target)
        source.anchors = listOf(Anchor(54), Anchor(1))
        assertNull(contract.current(owner))
        source.anchors = listOf(Anchor(1))
        assertFalse(contract.current(owner)!!.target)
    }

}
