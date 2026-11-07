package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ImmersiveWindowBindingsTest {
    private fun host(target: Int = 34): Activity {
        // API 35 with mutable bars is distinct from target-35 enforced edge-to-edge; both are tested.
        RuntimeEnvironment.getApplication().applicationInfo.targetSdkVersion = target
        return Robolectric.buildActivity(Activity::class.java).setup().get().apply {
        window.statusBarColor = Color.GREEN
        window.navigationBarColor = Color.BLUE
        window.isStatusBarContrastEnforced = true
        window.isNavigationBarContrastEnforced = true
        window.attributes = WindowManager.LayoutParams().also {
            it.copyFrom(window.attributes); it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        window.setDecorFitsSystemWindows(true)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
    }
    private fun fits(activity: Activity) = activity.window.javaClass.getMethod("decorFitsSystemWindows").invoke(activity.window) as Boolean

    @Test fun coupledDecorFitsWritesCannotBecomeTheLayoutFlagsRestoreBaseline() {
        val activity = host(); val decor = activity.window.decorView
        val before = decor.systemUiVisibility
        val state = ImmersiveWindowBindings(beforeWrite = { property ->
            if (property == ImmersiveWindowBindings.Property.FITS) decor.systemUiVisibility = decor.systemUiVisibility or ImmersiveWindowBindings.LAYOUT_MASK
        })
        state.observe(activity); state.configure(true); state.configure(true); state.configure(false)
        assertEquals(before, decor.systemUiVisibility); state.close()
    }
    @Test @Config(manifest = Config.NONE, sdk = [28]) fun androidNineUsesOnlyItsSupportedWindowFieldsAndRestoresThem() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.window.statusBarColor = Color.GREEN; activity.window.navigationBarColor = Color.BLUE
        val state = ImmersiveWindowBindings(); state.observe(activity); state.configure(true); state.configure(false)
        assertEquals(Color.GREEN, activity.window.statusBarColor); assertEquals(Color.BLUE, activity.window.navigationBarColor)
        state.close()
    }
    @Test fun windowFailureRecordsTheExactFieldPhaseAndNativeCauseType() {
        val activity = host(); var fail = false; val errors = mutableListOf<Exception>()
        val state = ImmersiveWindowBindings(errors::add) { if (fail && it == ImmersiveWindowBindings.Property.STATUS) throw SecurityException("private") }
        state.observe(activity); state.configure(true); fail = true; state.configure(false)
        val text = (errors.last() as ImmersiveFailure).summary()
        assertTrue(text.contains("恢复窗口")); assertTrue(text.contains("状态栏颜色")); assertTrue(text.contains("SecurityException")); assertFalse(text.contains("private"))
        fail = false; state.close()
    }

    @Test fun target35EnforcedTransparentBarsKeepTheirActualNativeBaseline() {
        val activity = host(35)
        val beforeStatus = activity.window.statusBarColor
        val beforeNavigation = activity.window.navigationBarColor
        val beforeFits = fits(activity)
        assertEquals(Color.TRANSPARENT, beforeStatus)
        val state = ImmersiveWindowBindings(); state.observe(activity); state.configure(true); state.configure(false)
        assertEquals(beforeStatus, activity.window.statusBarColor)
        assertEquals(beforeNavigation, activity.window.navigationBarColor)
        assertEquals(beforeFits, fits(activity)); state.close()
    }
    @Test fun disabledObservationDoesNotChangeAnyNativeWindowValues() {
        val activity = host(); val state = ImmersiveWindowBindings()
        state.observe(activity)
        assertEquals(Color.GREEN, activity.window.statusBarColor)
        assertTrue(fits(activity))
        assertEquals(0, state.result().writes)
        state.close()
    }
    @Test fun toggleRestoresOriginalBarsContrastCutoutFitsAndOnlyOwnedFlagBits() {
        val activity = host(); val window = activity.window
        val originalFlags = window.decorView.systemUiVisibility
        val state = ImmersiveWindowBindings(); state.observe(activity); state.configure(true)
        assertEquals(Color.TRANSPARENT, window.statusBarColor)
        assertEquals(Color.TRANSPARENT, window.navigationBarColor)
        assertFalse(window.isStatusBarContrastEnforced); assertFalse(fits(activity))
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, window.attributes.layoutInDisplayCutoutMode)
        assertEquals(ImmersiveWindowBindings.LAYOUT_MASK, window.decorView.systemUiVisibility and ImmersiveWindowBindings.LAYOUT_MASK)
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        state.configure(false)
        assertEquals(Color.GREEN, window.statusBarColor); assertEquals(Color.BLUE, window.navigationBarColor)
        assertTrue(window.isStatusBarContrastEnforced); assertTrue(window.isNavigationBarContrastEnforced)
        assertTrue(fits(activity))
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER, window.attributes.layoutInDisplayCutoutMode)
        assertEquals(originalFlags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, window.decorView.systemUiVisibility)
        state.close()
    }
    @Test fun anOriginallyEdgeToEdgeWindowIsNotForcedBackToFitsTrue() {
        val activity = host(); activity.window.setDecorFitsSystemWindows(false)
        val state = ImmersiveWindowBindings(); state.observe(activity); state.configure(true); state.configure(false)
        assertFalse(fits(activity)); state.close()
    }
    @Test fun latestNativeSetterIntentIsRestoredRatherThanAnOldColor() {
        val activity = host(); val state = ImmersiveWindowBindings()
        state.observe(activity); state.configure(true)
        activity.window.statusBarColor = state.native(activity.window, ImmersiveWindowBindings.Property.STATUS, Color.RED) as Int
        assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
        state.configure(false)
        assertEquals(Color.RED, activity.window.statusBarColor); state.close()
    }
    @Test fun readModifyWriteOfFlagsDoesNotPromoteTheModulesLayoutMaskToANativeBaseline() {
        val activity = host(); val state = ImmersiveWindowBindings(); val decor = activity.window.decorView
        val original = decor.systemUiVisibility
        state.observe(activity); state.configure(true)
        decor.systemUiVisibility = state.nativeFlags(decor, decor.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        state.configure(false)
        assertEquals(original or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, decor.systemUiVisibility); state.close()
    }
    @Test fun nativeAttributesAreCopiedAndUnrelatedUpdatesDoNotLoseTheOriginalCutout() {
        val activity = host(); val window = activity.window
        val state = ImmersiveWindowBindings(); state.observe(activity); state.configure(true)
        val incoming = WindowManager.LayoutParams().also { it.copyFrom(window.attributes); it.width = 711 }
        window.attributes = state.nativeAttributes(window, incoming)
        state.configure(false)
        assertEquals(711, window.attributes.width)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER, window.attributes.layoutInDisplayCutoutMode)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, incoming.layoutInDisplayCutoutMode)
        state.configure(true)
        val explicit = WindowManager.LayoutParams().also { it.copyFrom(window.attributes); it.layoutInDisplayCutoutMode = 0 }
        val projected = state.nativeAttributes(window, explicit)
        assertNotSame(explicit, projected); assertEquals(0, explicit.layoutInDisplayCutoutMode)
        window.attributes = projected; state.configure(false)
        assertEquals(0, window.attributes.layoutInDisplayCutoutMode); state.close()
    }
    @Test fun backgroundCanDisableAndReenableAndTracksNativeReplacementIncludingNull() {
        val activity = host(); val view = View(activity)
        val first = ColorDrawable(Color.RED); val second = ColorDrawable(Color.BLUE)
        view.background = first
        val state = ImmersiveWindowBindings(); state.background(view); state.configure(true)
        assertNull(view.background)
        view.background = state.nativeBackground(view, second)
        state.configure(false); assertSame(second, view.background)
        state.configure(true); view.background = state.nativeBackground(view, null)
        state.configure(false); assertNull(view.background); state.close()
    }
    @Test fun unknownWindowsAndViewsAreNotChangedByGlobalSetterObservers() {
        val known = host(); val other = host(); val state = ImmersiveWindowBindings()
        state.observe(known); state.configure(true)
        assertEquals(Color.RED, state.native(other.window, ImmersiveWindowBindings.Property.STATUS, Color.RED))
        val attrs = WindowManager.LayoutParams(); assertSame(attrs, state.nativeAttributes(other.window, attrs))
        val view = View(other); val drawable = ColorDrawable(Color.BLUE)
        assertSame(drawable, state.nativeBackground(view, drawable)); assertEquals(15, state.nativeFlags(view, 15))
        assertEquals(Color.GREEN, other.window.statusBarColor); state.close()
    }
    @Test fun closeAndReleaseAreIdempotentAndKeepUnobservedNativeChanges() {
        val activity = host(); val state = ImmersiveWindowBindings(); state.observe(activity); state.configure(true)
        activity.window.statusBarColor = Color.RED // A native write missed by a vendor setter hook.
        state.release(activity); assertEquals(Color.RED, activity.window.statusBarColor)
        assertEquals(Color.BLUE, activity.window.navigationBarColor)
        state.close(); val result = state.result(); state.close(); assertEquals(result, state.result())
        state.configure(true); assertEquals(Color.RED, activity.window.statusBarColor)
    }
    @Test fun oneFailedRestoreDoesNotPreventTheOtherPropertiesAndCanBeRetried() {
        val activity = host(); var fail = false; val errors = mutableListOf<Exception>()
        val state = ImmersiveWindowBindings(errors::add) { if (fail && it == ImmersiveWindowBindings.Property.STATUS) throw IllegalStateException("setter") }
        state.observe(activity); state.configure(true); fail = true; state.configure(false)
        assertEquals(Color.TRANSPARENT, activity.window.statusBarColor)
        assertEquals(Color.BLUE, activity.window.navigationBarColor)
        assertTrue(errors.isNotEmpty()); fail = false; state.configure(false)
        assertEquals(Color.GREEN, activity.window.statusBarColor); state.close()
    }
    @Test @Config(sdk = [28])
    fun android9CapturesAndRestoresOnlyWindowPropertiesSupportedByItsApi() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        activity.window.statusBarColor = Color.GREEN
        activity.window.navigationBarColor = Color.BLUE
        val flags = activity.window.decorView.systemUiVisibility
        val touched = mutableListOf<ImmersiveWindowBindings.Property>()
        val errors = mutableListOf<Exception>()
        val state = ImmersiveWindowBindings(errors::add, touched::add)
        state.observe(activity)
        state.configure(true)
        assertTrue(touched.isNotEmpty())
        assertFalse(touched.any { it in setOf(ImmersiveWindowBindings.Property.STATUS_CONTRAST,
            ImmersiveWindowBindings.Property.NAVIGATION_CONTRAST, ImmersiveWindowBindings.Property.FITS) })
        state.configure(false)
        assertEquals(Color.GREEN, activity.window.statusBarColor)
        assertEquals(Color.BLUE, activity.window.navigationBarColor)
        assertEquals(flags, activity.window.decorView.systemUiVisibility)
        assertTrue(errors.isEmpty())
        state.close(); owner.pause().stop().destroy()
    }

}
