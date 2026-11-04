package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class PhotoIndicatorTargetsTest {
    open class Indicators(val number: View?, val dots: View?)
    class Variant(number: View?, dots: View?) : Indicators(number, dots)
    class SwipeHint(val pager: View)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun reader() = PhotoIndicatorTargets(listOf(PhotoIndicatorTargets.Contract(
        Indicators::class.java, listOf("number", "dots"))))

    @Test fun hidingNumberAndDotsLeavesCarouselAndImagesVisibleAndRestoresIndicators() {
        val root = FrameLayout(context)
        val pager = FrameLayout(context)
        val image = View(context); pager.addView(image); root.addView(pager)
        val number = TextView(context); val dots = FrameLayout(context)
        root.addView(number); root.addView(dots)
        val targets = reader().targets(Variant(number, dots), root)
        assertEquals(setOf(number, dots), targets)
        val gate = CleanViewGate(); val owner = CleanViewGate.Owner(true, View.GONE)
        gate.replace(owner, targets); gate.refresh()
        assertEquals(View.GONE, number.visibility); assertEquals(View.GONE, dots.visibility)
        assertEquals(View.VISIBLE, root.visibility); assertEquals(View.VISIBLE, pager.visibility)
        assertEquals(View.VISIBLE, image.visibility); assertSame(pager, image.parent)
        owner.clean = false; gate.refresh()
        assertEquals(View.VISIBLE, number.visibility); assertEquals(View.VISIBLE, dots.visibility)
    }

    @Test fun swipeHintBehaviorNeverTurnsItsPagerOrCallbackRootIntoAHiddenTarget() {
        val root = FrameLayout(context); val pager = FrameLayout(context); root.addView(pager)
        assertTrue(reader().targets(SwipeHint(pager), root).isEmpty())
        assertTrue(reader().targets(Indicators(root, null), root).isEmpty())
        assertEquals(View.VISIBLE, pager.visibility)
    }

    @Test fun detachedReusedOrMissingIndicatorChildrenDoNotHideUnrelatedContent() {
        val root = FrameLayout(context); val otherRoot = FrameLayout(context)
        val old = View(context); otherRoot.addView(old)
        assertTrue(reader().targets(Indicators(old, null), root).isEmpty())
        assertTrue(reader().targets(Indicators(null, null), root).isEmpty())
        val current = View(context); root.addView(current)
        assertEquals(setOf(current), reader().targets(Indicators(current, old), root))
    }
}
