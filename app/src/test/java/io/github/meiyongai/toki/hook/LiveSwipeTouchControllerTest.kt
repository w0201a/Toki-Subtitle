package io.github.meiyongai.toki.subtitle.hook

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LiveSwipeTouchControllerTest {
    class Pager : FrameLayout(RuntimeEnvironment.getApplication()) {
        var touchCancels = 0; var interceptCancels = 0
        var nativeMoves = 0; var nativeUps = 0
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_CANCEL -> touchCancels++
                MotionEvent.ACTION_MOVE -> nativeMoves++
                MotionEvent.ACTION_UP -> nativeUps++
            }
            return true
        }
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) interceptCancels++
            return false
        }
    }
    private fun event(action: Int, x: Float = 0f, y: Float = 0f, down: Long = 100L) =
        MotionEvent.obtain(down, down + 10L + action, action, x, y, 0)
    private inline fun useEvent(action: Int, x: Float = 0f, y: Float = 0f, down: Long = 100L, block: (MotionEvent) -> Unit) {
        val value = event(action, x, y, down); try { block(value) } finally { value.recycle() }
    }
    private fun route(controller: LiveSwipeTouchController, pager: Pager, event: MotionEvent,
        enabled: Boolean = true, entry: LiveSwipeTouchController.Entry = LiveSwipeTouchController.Entry.TOUCH): Boolean =
        controller.route(pager, event, enabled, entry) {
            if (entry == LiveSwipeTouchController.Entry.INTERCEPT) pager.onInterceptTouchEvent(event) else pager.onTouchEvent(event)
        }
    @Test fun bothDirectionsCancelActualPagerAndBlockItsTouchListenerThroughRelease() {
        for (dy in listOf(-200f, 200f)) {
            val pager = Pager(); var blocks = 0; val controller = LiveSwipeTouchController({ blocks++ })
            useEvent(MotionEvent.ACTION_DOWN) { event -> LiveSwipeTouchController.Entry.entries.forEach { route(controller, pager, event, entry = it) } }
            useEvent(MotionEvent.ACTION_MOVE, y = dy) { event ->
                assertFalse(route(controller, pager, event, entry = LiveSwipeTouchController.Entry.INTERCEPT))
                assertTrue(route(controller, pager, event, entry = LiveSwipeTouchController.Entry.LISTENER))
                assertTrue(route(controller, pager, event))
            }
            useEvent(MotionEvent.ACTION_UP, y = dy) { event -> LiveSwipeTouchController.Entry.entries.forEach {
                assertEquals(it != LiveSwipeTouchController.Entry.INTERCEPT, route(controller, pager, event, entry = it))
            } }
            assertEquals(1, blocks); assertEquals(1, pager.touchCancels); assertEquals(1, pager.interceptCancels)
            assertEquals(0, pager.nativeMoves); assertEquals(0, pager.nativeUps)
        }
    }
    @Test fun blockedGestureCannotEscapeByReturningToOriginOrMovingHorizontally() {
        val pager = Pager(); val controller = LiveSwipeTouchController()
        useEvent(MotionEvent.ACTION_DOWN) { route(controller, pager, it) }
        useEvent(MotionEvent.ACTION_MOVE, y = 200f) { route(controller, pager, it) }
        useEvent(MotionEvent.ACTION_MOVE, x = 500f, y = 1f) { assertTrue(route(controller, pager, it)) }
        useEvent(MotionEvent.ACTION_UP, x = 500f, y = 1f) { assertTrue(route(controller, pager, it)) }
        assertEquals(0, pager.nativeMoves); assertEquals(0, pager.nativeUps); assertEquals(1, pager.touchCancels)
    }
    @Test fun tapsSmallMovementsAndHorizontalGesturesKeepNativeHandling() {
        for ((dx, dy) in listOf(0f to 0f, 1f to 1f, 200f to 2f)) {
            val pager = Pager(); val controller = LiveSwipeTouchController()
            useEvent(MotionEvent.ACTION_DOWN) { route(controller, pager, it) }
            useEvent(MotionEvent.ACTION_MOVE, dx, dy) { route(controller, pager, it) }
            useEvent(MotionEvent.ACTION_UP, dx, dy) { route(controller, pager, it) }
            assertEquals(1, pager.nativeMoves); assertEquals(1, pager.nativeUps); assertEquals(0, pager.touchCancels)
        }
    }
    @Test fun turningOffDuringBlockedGestureDrainsItAndRestoresTheNextGesture() {
        val pager = Pager(); val controller = LiveSwipeTouchController()
        useEvent(MotionEvent.ACTION_DOWN) { route(controller, pager, it) }
        useEvent(MotionEvent.ACTION_MOVE, y = 200f) { route(controller, pager, it) }
        useEvent(MotionEvent.ACTION_MOVE, y = 300f) { route(controller, pager, it, enabled = false) }
        useEvent(MotionEvent.ACTION_UP, y = 300f) { route(controller, pager, it, enabled = false) }
        assertEquals(0, pager.nativeMoves); assertEquals(0, pager.nativeUps)
        useEvent(MotionEvent.ACTION_DOWN, down = 300L) { route(controller, pager, it, enabled = false) }
        useEvent(MotionEvent.ACTION_MOVE, y = 300f, down = 300L) { route(controller, pager, it, enabled = false) }
        useEvent(MotionEvent.ACTION_UP, y = 300f, down = 300L) { route(controller, pager, it, enabled = false) }
        assertEquals(1, pager.nativeMoves); assertEquals(1, pager.nativeUps)
    }
    @Test fun turningOnMidGestureDoesNotStealAnUnarmedSequence() {
        val pager = Pager(); val controller = LiveSwipeTouchController()
        useEvent(MotionEvent.ACTION_DOWN) { route(controller, pager, it, enabled = false) }
        useEvent(MotionEvent.ACTION_MOVE, y = 300f) { route(controller, pager, it) }
        useEvent(MotionEvent.ACTION_UP, y = 300f) { route(controller, pager, it) }
        assertEquals(1, pager.nativeMoves); assertEquals(1, pager.nativeUps); assertEquals(0, pager.touchCancels)
    }
    @Test fun disablingBeforeThresholdDisarmsTheRestOfThatSequence() {
        val pager = Pager(); val controller = LiveSwipeTouchController()
        useEvent(MotionEvent.ACTION_DOWN) { route(controller, pager, it) }
        useEvent(MotionEvent.ACTION_MOVE, y = 1f) { route(controller, pager, it, enabled = false) }
        useEvent(MotionEvent.ACTION_MOVE, y = 300f) { route(controller, pager, it) }
        assertEquals(2, pager.nativeMoves); assertEquals(0, pager.touchCancels)
    }
    @Test fun differentPagerInstancesAndNewDownTimesCannotShareState() {
        val a = Pager(); val b = Pager(); val controller = LiveSwipeTouchController()
        useEvent(MotionEvent.ACTION_DOWN) { route(controller, a, it); route(controller, b, it) }
        useEvent(MotionEvent.ACTION_MOVE, y = 300f) { route(controller, a, it) }
        useEvent(MotionEvent.ACTION_UP) { route(controller, b, it) }
        assertEquals(1, b.nativeUps); assertEquals(0, b.touchCancels)
        useEvent(MotionEvent.ACTION_MOVE, y = 300f, down = 900L) { route(controller, a, it) }
        assertEquals(1, a.nativeMoves)
    }
    @Test fun cancelIsDrainedByEveryEntryAndFollowingTapWorks() {
        val pager = Pager(); val controller = LiveSwipeTouchController()
        useEvent(MotionEvent.ACTION_DOWN) { route(controller, pager, it) }
        useEvent(MotionEvent.ACTION_MOVE, y = 300f) { route(controller, pager, it) }
        useEvent(MotionEvent.ACTION_CANCEL) { event -> LiveSwipeTouchController.Entry.entries.forEach { route(controller, pager, event, entry = it) } }
        assertEquals(1, pager.touchCancels); assertEquals(1, pager.interceptCancels)
        useEvent(MotionEvent.ACTION_DOWN, down = 300L) { route(controller, pager, it) }
        useEvent(MotionEvent.ACTION_UP, down = 300L) { route(controller, pager, it) }
        assertEquals(1, pager.nativeUps)
    }
    private fun multi(action: Int, ids: IntArray, ys: FloatArray): MotionEvent {
        val properties = Array(ids.size) { index -> MotionEvent.PointerProperties().apply { id = ids[index]; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coordinates = Array(ids.size) { index -> MotionEvent.PointerCoords().apply { x = 0f; y = ys[index]; pressure = 1f; size = 1f } }
        return MotionEvent.obtain(100L, 120L, action, ids.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
    }
    @Test fun liftingActivePointerRebasesWithoutAFalseSwipeAndRetainsRemainingMovement() {
        val pager = Pager(); val controller = LiveSwipeTouchController()
        useEvent(MotionEvent.ACTION_DOWN) { route(controller, pager, it) }
        val lift = multi(MotionEvent.ACTION_POINTER_UP, intArrayOf(0, 7), floatArrayOf(0f, 500f))
        try { route(controller, pager, lift) } finally { lift.recycle() }
        val still = multi(MotionEvent.ACTION_MOVE, intArrayOf(7), floatArrayOf(500f))
        try { route(controller, pager, still) } finally { still.recycle() }
        assertEquals(0, pager.touchCancels)
        val drag = multi(MotionEvent.ACTION_MOVE, intArrayOf(7), floatArrayOf(800f))
        try { route(controller, pager, drag) } finally { drag.recycle() }
        assertEquals(1, pager.touchCancels)
    }
    @Test fun newlyPressedPointerFollowsTheNativePagerWithoutAJumpOrABypass() {
        val pager = Pager(); val controller = LiveSwipeTouchController()
        useEvent(MotionEvent.ACTION_DOWN) { route(controller, pager, it) }
        val press = multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            intArrayOf(0, 7), floatArrayOf(0f, 500f))
        try { LiveSwipeTouchController.Entry.entries.forEach { route(controller, pager, press, entry = it) } }
        finally { press.recycle() }
        val still = multi(MotionEvent.ACTION_MOVE, intArrayOf(0, 7), floatArrayOf(0f, 500f))
        try { route(controller, pager, still) } finally { still.recycle() }
        assertEquals(0, pager.touchCancels)
        val drag = multi(MotionEvent.ACTION_MOVE, intArrayOf(0, 7), floatArrayOf(0f, 800f))
        try { route(controller, pager, drag) } finally { drag.recycle() }
        assertEquals(1, pager.touchCancels)
    }
    @Test fun originalPointerCannotBypassParentInterceptionAfterASecondFingerIsPressed() {
        val pager = Pager(); val controller = LiveSwipeTouchController()
        useEvent(MotionEvent.ACTION_DOWN) { route(controller, pager, it, entry = LiveSwipeTouchController.Entry.INTERCEPT) }
        val press = multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            intArrayOf(0, 7), floatArrayOf(0f, 500f))
        try { route(controller, pager, press, entry = LiveSwipeTouchController.Entry.INTERCEPT) } finally { press.recycle() }
        val drag = multi(MotionEvent.ACTION_MOVE, intArrayOf(0, 7), floatArrayOf(300f, 500f))
        try { assertFalse(route(controller, pager, drag, entry = LiveSwipeTouchController.Entry.INTERCEPT)) } finally { drag.recycle() }
        assertEquals(1, pager.touchCancels); assertEquals(1, pager.interceptCancels)
    }
    @Test fun nativeCancellationFailureStillDrainsTheBlockedStream() {
        val errors = mutableListOf<Exception>(); var nativeCalls = 0
        val pager = object : FrameLayout(RuntimeEnvironment.getApplication()) {
            override fun onTouchEvent(event: MotionEvent): Boolean { throw IllegalStateException("native cancel") }
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false
        }
        val controller = LiveSwipeTouchController(onCancelFailure = errors::add)
        useEvent(MotionEvent.ACTION_DOWN) { controller.route(pager, it, true, LiveSwipeTouchController.Entry.TOUCH) { nativeCalls++; true } }
        useEvent(MotionEvent.ACTION_MOVE, y = 300f) { assertTrue(controller.route(pager, it, true, LiveSwipeTouchController.Entry.TOUCH) { nativeCalls++; true }) }
        useEvent(MotionEvent.ACTION_UP) { controller.route(pager, it, true, LiveSwipeTouchController.Entry.LISTENER) { nativeCalls++; true } }
        assertEquals(1, errors.size); assertEquals(1, nativeCalls)
    }
    class Fragment(@JvmField val pager: Pager)
    class Listener(@JvmField val fragment: Fragment) : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean = false
    }
    @Test fun listenerContractResolvesItsActualPagerAndRejectsOtherInstances() {
        val pager = Pager(); val listener = Listener(Fragment(pager))
        val contract = LiveSwipeLockContract.listener(Listener::class.java, Listener::class.java.getDeclaredField("fragment"),
            Fragment::class.java.getDeclaredField("pager"))
        assertEquals(Pager::class.java, contract.pager)
        assertEquals(Pager::class.java, contract.intercept.declaringClass)
        assertEquals(Pager::class.java, contract.touch.declaringClass)
        assertTrue(contract.ownsListener(listener, pager)); assertFalse(contract.ownsListener(listener, Pager()))
        assertFalse(contract.ownsListener(null, pager))
    }
}
