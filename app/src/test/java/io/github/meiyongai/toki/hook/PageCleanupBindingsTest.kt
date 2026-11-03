package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewStub
import android.widget.FrameLayout
import io.github.meiyongai.toki.subtitle.model.PageCleanupItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PageCleanupBindingsTest {
    private val search = PageCleanupItem.SEARCH_VOICE
    private val inbox = PageCleanupItem.INBOX_SHOP_MIGRATION
    private fun frame() = FrameLayout(RuntimeEnvironment.getApplication())
    private fun child() = frame().also { frame().addView(it) }

    @Test fun switchesAreIndependentAndClosingRestoresLatestHostIntent() {
        val gate = CleanViewGate(); val bindings = PageCleanupBindings(gate)
        val a = Any(); val b = Any(); val one = child(); val two = child()
        bindings.bind(a, search, one); bindings.bind(b, inbox, two)
        bindings.configure(setOf(search))
        assertEquals(View.GONE, one.visibility); assertEquals(View.VISIBLE, two.visibility)
        one.visibility = gate.visibility(one, View.INVISIBLE)
        assertEquals(View.GONE, one.visibility)
        bindings.configure(setOf(inbox))
        assertEquals(View.INVISIBLE, one.visibility); assertEquals(View.GONE, two.visibility)
        bindings.close(); bindings.close()
        assertEquals(View.VISIBLE, two.visibility)
        bindings.configure(setOf(search)); assertEquals(View.INVISIBLE, one.visibility)
    }
    @Test fun rootReplacementReuseAndNullBindingReleaseOldOwnership() {
        val gate = CleanViewGate(); val bindings = PageCleanupBindings(gate)
        val component = Any(); val recycled = Any(); val one = child(); val two = child()
        bindings.configure(setOf(search))
        bindings.bind(component, search, one); assertEquals(View.GONE, one.visibility)
        bindings.bind(component, search, two)
        assertEquals(View.VISIBLE, one.visibility); assertEquals(View.GONE, two.visibility)
        bindings.bind(recycled, inbox, two)
        assertEquals(View.VISIBLE, two.visibility)
        bindings.release(component); assertEquals(View.VISIBLE, two.visibility)
        bindings.configure(setOf(inbox)); assertEquals(View.GONE, two.visibility)
        bindings.bind(recycled, inbox, null); assertEquals(View.VISIBLE, two.visibility)
        bindings.close()
    }
    @Test fun anotherGateOwnerIsNotRestoredPrematurely() {
        val gate = CleanViewGate(); val bindings = PageCleanupBindings(gate); val view = child()
        val other = CleanViewGate.Owner(true)
        gate.bind(other, view)
        bindings.configure(setOf(search)); bindings.bind(Any(), search, view)
        assertEquals(View.GONE, view.visibility)
        bindings.close(); assertEquals(View.INVISIBLE, view.visibility)
        other.clean = false; gate.refresh(); assertEquals(View.VISIBLE, view.visibility)
    }
    @Test fun equalityCollisionsDoNotMixComponentsAndCapacityDoesNotHideUntrackedRoots() {
        class EqualOwner { override fun equals(other: Any?) = other is EqualOwner; override fun hashCode() = 1 }
        val one = EqualOwner(); val two = EqualOwner(); val first = child(); val second = child()
        val bindings = PageCleanupBindings(CleanViewGate(), capacity = 2)
        bindings.configure(setOf(search)); bindings.bind(one, search, first); bindings.bind(two, search, second)
        bindings.release(one)
        assertEquals(View.VISIBLE, first.visibility); assertEquals(View.GONE, second.visibility)
        bindings.bind(one, search, first)
        val overflow = child(); bindings.bind(Any(), search, overflow)
        assertEquals(View.VISIBLE, overflow.visibility)
        bindings.close()
    }
    @Test fun stubsVideoSurfacesAndUnparentedRootsAreNotHidden() {
        val context = RuntimeEnvironment.getApplication()
        val bindings = PageCleanupBindings(CleanViewGate()); val owner = Any()
        bindings.configure(setOf(search))
        for (root in listOf(ViewStub(context), SurfaceView(context), TextureView(context), frame())) {
            frame().also { parent -> if (root !is FrameLayout) parent.addView(root) }
            val original = root.visibility
            bindings.bind(owner, search, root); assertEquals(original, root.visibility)
        }
        bindings.close()
    }
    @Test fun pendingHolderIsAppliedOnAttachAndReleasedOnDetachWithoutStaleRehide() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val parent = FrameLayout(activity); activity.setContentView(parent)
        val view = FrameLayout(activity); val owner = Any(); val bindings = PageCleanupBindings(CleanViewGate())
        bindings.configure(setOf(inbox)); bindings.bind(owner, inbox, view)
        assertEquals(View.VISIBLE, view.visibility)
        parent.addView(view); assertEquals(View.GONE, view.visibility)
        parent.removeView(view); assertEquals(View.VISIBLE, view.visibility)
        parent.addView(view); assertEquals(View.VISIBLE, view.visibility)
        bindings.bind(owner, inbox, view); assertEquals(View.GONE, view.visibility)
        bindings.close()
        assertEquals(View.VISIBLE, view.visibility)
    }
}
