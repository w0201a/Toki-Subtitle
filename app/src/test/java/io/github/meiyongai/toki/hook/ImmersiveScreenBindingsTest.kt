package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class ImmersiveScreenBindingsTest {
    interface Result
    data class Screen(val topSpaceHeight: Int, val bottomSpaceHeight: Int, val screenHeight: Int,
        val screenWidth: Int, val statusBarHeight: Int, val appNavHeight: Int, val virtualBarHeight: Int?) : Result
    open class Base {
        @JvmField var LLJJIJIIJIL: Result? = null
        val events = mutableListOf<Result>()
        fun LIZLLL(value: Result) { events += value }
    }
    open class Typed : Base()
    class Handler : Typed() { companion object { @JvmField var LLJJJJJIL: Handler? = null } }
    class Panel(val current: Handler) {
        fun sq(): Handler = current
        fun adaptation() { }
        fun Aq(value: Screen) { }
    }
    enum class Reason { CONTAINER_ADAPTION, VIDEO_BIND }
    open class Cell {
        var seen: Reason? = null
        fun Wp(reason: Reason, width: Int?, height: Int?) { assertNull(width); assertNull(height); seen = reason }
    }
    class Feed : Cell()
    private fun contract() = ImmersiveScreenBindings.Contract(Panel::class.java, Feed::class.java, Screen::class.java)
    private fun original(top: Int = 90) = Screen(top, 120, 2400, 1080, 90, 120, null)
    @Test fun projectionPreservesTheImmutableNativeInputAndNotifiesMatchingCaches() {
        val primary = Handler(); val mirror = Handler(); Handler.LLJJJJJIL = mirror
        val before = original(); primary.LLJJIJIIJIL = before; mirror.LLJJIJIIJIL = before
        val state = ImmersiveScreenBindings(contract())
        val output = state.project(Panel(primary), before, true) as Screen
        assertEquals(90, before.topSpaceHeight); assertEquals(0, output.topSpaceHeight); assertEquals(0, output.bottomSpaceHeight)
        assertEquals(before.copy(topSpaceHeight = 0, bottomSpaceHeight = 0), output)
        assertSame(output, primary.LLJJIJIIJIL); assertSame(output, mirror.LLJJIJIIJIL)
        assertSame(output, primary.events.single()); state.restoreCaches()
        assertSame(before, primary.LLJJIJIIJIL); assertSame(before, mirror.LLJJIJIIJIL)
    }
    @Test fun disableReleasesOnlyOwnedProjectionAndRepeatedApplyDoesNotLoop() {
        val primary = Handler(); Handler.LLJJJJJIL = primary
        val before = original(); primary.LLJJIJIIJIL = before
        val state = ImmersiveScreenBindings(contract()); val panel = Panel(primary)
        val output = state.project(panel, before, true)
        assertSame(output, state.project(panel, output, true)); assertEquals(1, primary.events.size)
        assertSame(before, state.project(panel, output, false)); assertSame(before, primary.LLJJIJIIJIL)
        val count = primary.events.size; state.restoreCaches(); assertEquals(count, primary.events.size)
    }
    @Test fun disabledOriginalPathDoesNotMutateOrPublishAnything() {
        val primary = Handler(); Handler.LLJJJJJIL = null
        val before = original(); primary.LLJJIJIIJIL = before
        val state = ImmersiveScreenBindings(contract())
        assertSame(before, state.project(Panel(primary), before, false))
        assertTrue(primary.events.isEmpty()); assertSame(before, primary.LLJJIJIIJIL)
    }
    @Test fun nativeReplacementOrClearedCacheIsNeverOverwrittenByRestoration() {
        val primary = Handler(); val mirror = Handler(); Handler.LLJJJJJIL = mirror
        val before = original(); primary.LLJJIJIIJIL = before; mirror.LLJJIJIIJIL = before
        val state = ImmersiveScreenBindings(contract()); state.project(Panel(primary), before, true)
        val newer = original(125); primary.LLJJIJIIJIL = newer; mirror.LLJJIJIIJIL = null
        state.restoreCaches(); assertSame(newer, primary.LLJJIJIIJIL); assertNull(mirror.LLJJIJIIJIL)
    }
    @Test fun aNewNativeScreenResultBecomesTheNewRestoreBaseline() {
        val primary = Handler(); Handler.LLJJJJJIL = primary
        val first = original(); primary.LLJJIJIIJIL = first
        val state = ImmersiveScreenBindings(contract()); val panel = Panel(primary)
        state.project(panel, first, true)
        val newer = original(125); primary.LLJJIJIIJIL = newer
        state.project(panel, newer, true); state.restoreCaches()
        assertSame(newer, primary.LLJJIJIIJIL)
    }
    @Test fun stalePanelInputCannotReplaceANewerNativeCache() {
        val primary = Handler(); Handler.LLJJJJJIL = null
        val before = original(); val newer = original(125); primary.LLJJIJIIJIL = newer
        val errors = mutableListOf<Exception>(); val state = ImmersiveScreenBindings(contract(), errors::add)
        assertSame(before, state.project(Panel(primary), before, true))
        assertSame(newer, primary.LLJJIJIIJIL); assertTrue(errors.isNotEmpty())
    }
    @Test fun nativeCellRecalculationUsesTheNamedReasonAndDoesNotInventDimensions() {
        val feed = Feed(); contract().invalidate(feed); assertEquals(Reason.CONTAINER_ADAPTION, feed.seen)
    }
    @Test fun distinctNativeCopiesAreNotConfusedWithAnEqualModuleProjection() {
        val primary = Handler(); Handler.LLJJJJJIL = null
        val before = original(); primary.LLJJIJIIJIL = before
        val state = ImmersiveScreenBindings(contract()); val output = state.project(Panel(primary), before, true) as Screen
        val equalNative = output.copy(); primary.LLJJIJIIJIL = equalNative
        state.restoreCaches(); assertSame(equalNative, primary.LLJJIJIIJIL)
    }
}
