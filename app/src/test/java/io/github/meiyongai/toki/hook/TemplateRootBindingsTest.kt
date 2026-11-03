package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewStub
import android.widget.FrameLayout
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class TemplateRootBindingsTest {
    private fun frame() = FrameLayout(RuntimeEnvironment.getApplication())
    private fun child() = frame().also { frame().addView(it) }
    private fun value(root: View, target: Boolean = true, identity: Any = Any(), context: Any = Any()) =
        TemplateRootBindings.Snapshot(root, identity, context, target)
    @Test fun observingWhileOffAllowsCurrentPageToggleAndRestoresLatestNativeVisibility() {
        val gate = CleanViewGate(); val models = IdentityHashMap<Any, TemplateRootBindings.Snapshot>()
        val bindings = TemplateRootBindings(gate, { models[it] })
        val owner = Any(); val view = child(); val source = value(view); models[owner] = source
        bindings.observe(owner, source); assertEquals(View.VISIBLE, view.visibility)
        bindings.configure(true); assertEquals(View.GONE, view.visibility)
        view.visibility = gate.visibility(view, View.INVISIBLE)
        bindings.configure(false); assertEquals(View.INVISIBLE, view.visibility)
        bindings.configure(true); assertEquals(View.GONE, view.visibility)
        bindings.close(); assertEquals(View.INVISIBLE, view.visibility)
    }
    @Test fun unrelatedBusinessAndReplacedIdentityOrVideoContextCannotInheritAnOldHideRule() {
        val models = IdentityHashMap<Any, TemplateRootBindings.Snapshot>(); val bindings = TemplateRootBindings(CleanViewGate(), { models[it] })
        val owner = Any(); val view = child(); val initial = value(view); models[owner] = initial
        bindings.configure(true); bindings.observe(owner, initial); assertEquals(View.GONE, view.visibility)
        models[owner] = initial.copy(target = false)
        bindings.configure(true); assertEquals(View.VISIBLE, view.visibility)
        models[owner] = initial; bindings.observe(owner, initial)
        models[owner] = initial.copy(context = Any())
        bindings.configure(true); assertEquals(View.VISIBLE, view.visibility)
        bindings.close()
    }
    @Test fun aFreshNativeBindingCanReplaceTheRootAndReuseItWithoutRetainingPreviousOwners() {
        val models = IdentityHashMap<Any, TemplateRootBindings.Snapshot>(); val bindings = TemplateRootBindings(CleanViewGate(), { models[it] })
        val one = Any(); val two = Any(); val first = child(); val second = child()
        bindings.configure(true)
        val a = value(first); models[one] = a; bindings.observe(one, a)
        val b = value(second); models[one] = b; bindings.observe(one, b)
        assertEquals(View.VISIBLE, first.visibility); assertEquals(View.GONE, second.visibility)
        val reused = value(second); models[two] = reused; bindings.observe(two, reused)
        bindings.release(one); assertEquals(View.GONE, second.visibility)
        bindings.release(two); assertEquals(View.VISIBLE, second.visibility)
        bindings.close()
    }
    @Test fun equalOwnersStayIndependentAndCapacityDoesNotHideUntrackedRoots() {
        class Owner { override fun equals(other: Any?) = other is Owner; override fun hashCode() = 1 }
        val models = IdentityHashMap<Any, TemplateRootBindings.Snapshot>(); val bindings = TemplateRootBindings(CleanViewGate(), { models[it] }, capacity = 2)
        val first = Owner(); val second = Owner(); val a = value(child()); val b = value(child())
        models[first] = a; models[second] = b; bindings.configure(true); bindings.observe(first, a); bindings.observe(second, b)
        bindings.release(first); assertEquals(View.VISIBLE, a.root.visibility); assertEquals(View.GONE, b.root.visibility)
        bindings.observe(first, a)
        val overflow = value(child()); bindings.observe(Any(), overflow); assertEquals(View.VISIBLE, overflow.root.visibility)
        bindings.close()
    }
    @Test fun pendingAttachmentAndReattachmentRevalidateActualAnchorIdentity() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get(); val parent = FrameLayout(activity)
        activity.setContentView(parent)
        val root = FrameLayout(activity); val owner = Any(); val initial = value(root)
        val models = IdentityHashMap<Any, TemplateRootBindings.Snapshot>().apply { put(owner, initial) }
        val bindings = TemplateRootBindings(CleanViewGate(), { models[it] }); bindings.configure(true); bindings.observe(owner, initial)
        assertEquals(View.VISIBLE, root.visibility)
        parent.addView(root); assertEquals(View.GONE, root.visibility)
        parent.removeView(root); assertEquals(View.VISIBLE, root.visibility)
        models[owner] = initial.copy(identity = Any())
        parent.addView(root); assertEquals(View.VISIBLE, root.visibility)
        bindings.observe(owner, models[owner]); assertEquals(View.GONE, root.visibility)
        bindings.close(); assertEquals(View.VISIBLE, root.visibility)
    }
    @Test fun otherVisibilityOwnersSurviveTemplateDisableAndViewSurfacesAreNeverTargets() {
        val gate = CleanViewGate(); val models = IdentityHashMap<Any, TemplateRootBindings.Snapshot>()
        val bindings = TemplateRootBindings(gate, { models[it] }); val owner = Any(); val root = child(); val source = value(root)
        val other = CleanViewGate.Owner(true); gate.bind(other, root)
        models[owner] = source; bindings.observe(owner, source); bindings.configure(true)
        bindings.close(); assertEquals(View.INVISIBLE, root.visibility)
        other.clean = false; gate.refresh(); assertEquals(View.VISIBLE, root.visibility)
        val context = RuntimeEnvironment.getApplication()
        val open = TemplateRootBindings(CleanViewGate(), { models[it] }); open.configure(true)
        for (view in listOf(ViewStub(context), SurfaceView(context), TextureView(context))) {
            frame().addView(view); val original = view.visibility; open.observe(Any(), value(view)); assertEquals(original, view.visibility)
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val window = activity.window.decorView
        val visibility = window.visibility
        val windowOwner = Any(); val windowValue = value(window); models[windowOwner] = windowValue
        open.observe(windowOwner, windowValue); assertEquals(visibility, window.visibility)
        open.close()
    }
    @Test fun unreadableCurrentDataReleasesOnlyItsRootAndDoesNotStopOtherBindings() {
        val broken = Any(); val healthy = Any(); val models = IdentityHashMap<Any, TemplateRootBindings.Snapshot>()
        var fail = false; var reports = 0
        val bindings = TemplateRootBindings(CleanViewGate(), { if (it === broken && fail) error("unavailable") else models[it] }, { reports++ })
        val a = value(child()); val b = value(child()); models[broken] = a; models[healthy] = b
        bindings.observe(broken, a); bindings.observe(healthy, b); fail = true; bindings.configure(true)
        assertEquals(View.VISIBLE, a.root.visibility); assertEquals(View.GONE, b.root.visibility); assertEquals(1, reports)
        bindings.close(); bindings.configure(true); assertEquals(View.VISIBLE, b.root.visibility)
    }
}
