package io.github.meiyongai.toki.subtitle.hook

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class BackgroundWriteGuardTest {
    @Test fun aDelegatingSetterDoesNotReplaceTheNativeBackgroundWithOurNullProjection() {
        val view = View(RuntimeEnvironment.getApplication()); val original = ColorDrawable(Color.RED)
        val newer = ColorDrawable(Color.BLUE); view.background = original
        val state = ImmersiveWindowBindings(); state.background(view); state.configure(true)
        val guard = BackgroundWriteGuard(); var transformations = 0
        guard.apply(view, newer, { transformations++; state.nativeBackground(view, it) }) { projected ->
            guard.apply(view, projected, { transformations++; state.nativeBackground(view, it) }) { view.background = it }
        }
        assertEquals(1, transformations); assertNull(view.background)
        state.configure(false); assertSame(newer, view.background); state.close()
    }
    @Test fun directLegacySetterAndExplicitNativeNullBothRemainValidNativeIntent() {
        val view = View(RuntimeEnvironment.getApplication()); val original = ColorDrawable(Color.RED)
        val newer = ColorDrawable(Color.BLUE); view.background = original
        val state = ImmersiveWindowBindings(); state.background(view); state.configure(true)
        val guard = BackgroundWriteGuard()
        guard.apply(view, newer, { state.nativeBackground(view, it) }) { view.setBackgroundDrawable(it) }
        state.configure(false); assertSame(newer, view.background)
        state.configure(true)
        guard.apply(view, null, { state.nativeBackground(view, it) }) { view.setBackgroundDrawable(it) }
        state.configure(false); assertNull(view.background); state.close()
    }
    @Test fun nestedCallsForADifferentViewAreNotSuppressedByAnotherViewsWrite() {
        val first = View(RuntimeEnvironment.getApplication()); val second = View(RuntimeEnvironment.getApplication())
        val guard = BackgroundWriteGuard(); val seen = mutableListOf<View>(); val image = ColorDrawable(Color.BLUE)
        guard.apply(first, image, { seen += first; it }) {
            guard.apply(second, image, { seen += second; it }) { second.background = it }
        }
        assertEquals(listOf(first, second), seen); assertSame(image, second.background)
    }
    @Test fun exceptionsFromTransformOrDownstreamNeverLeaveTheGuardStuck() {
        val view = View(RuntimeEnvironment.getApplication()); val guard = BackgroundWriteGuard()
        assertThrows(IllegalStateException::class.java) { guard.apply(view, null, { throw IllegalStateException("transform") }) { null } }
        assertThrows(IllegalStateException::class.java) { guard.apply(view, null, { it }) { throw IllegalStateException("native") } }
        var transformed = false
        guard.apply(view, null, { transformed = true; it }) { null }
        assertTrue(transformed)
    }
}
