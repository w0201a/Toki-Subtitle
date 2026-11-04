package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ImmersiveRefreshPlanTest {
    private class Host {
        val p = Any(); val c = Any()
        val root = View(RuntimeEnvironment.getApplication()); val video = View(RuntimeEnvironment.getApplication())
        val targets = mutableListOf(ImmersiveLayoutPreparation.Target(p, root, true), ImmersiveLayoutPreparation.Target(c, video, false))
        val visible = mutableSetOf(root, video)
        val events = mutableListOf<String>()
        var valid = true; var fail = false
        val errors = mutableListOf<Exception>()
        val plan = ImmersiveRefreshPlan({ targets.toList() }, { it in visible }, { valid },
            { enabled -> events += "windows:$enabled" }, { events += "release" }, { panel ->
                if (fail) throw IllegalStateException("native layout")
                events += "panel"; planApplied(panel)
            }, { events += "cell" }, errors::add)
        private fun planApplied(owner: Any) { plan.panelApplied(owner, plan.active) }
    }
    @Test fun windowChangePrecedesPanelRecalculationAndCurrentCellInvalidation() {
        val host = Host(); val result = host.plan.refresh(true)
        assertEquals(listOf("windows:true", "panel", "cell"), host.events)
        assertEquals(2, result.commands); assertEquals(0, result.failed)
        host.events.clear(); host.plan.refresh(true)
        assertEquals(listOf("windows:true"), host.events)
    }
    @Test fun hiddenDisableRestorationSurvivesUntilTheSamePanelBecomesVisibleAgain() {
        val host = Host(); host.plan.refresh(true)
        host.visible.clear(); host.events.clear()
        val hidden = host.plan.refresh(false)
        assertEquals(listOf("windows:false", "release"), host.events); assertEquals(1, hidden.deferred)
        host.visible += host.root; host.visible += host.video; host.events.clear()
        host.plan.invalidate(); val shown = host.plan.refresh(false)
        assertEquals(listOf("windows:false", "release", "panel", "cell"), host.events)
        assertEquals(0, shown.deferred)
        host.events.clear(); host.plan.refresh(false); assertEquals(listOf("windows:false"), host.events)
    }
    @Test fun failedPanelDoesNotBlockTheCellAndIsRetriedWithoutChangingTheSwitch() {
        val host = Host(); host.fail = true
        val failed = host.plan.refresh(true)
        assertEquals(1, failed.failed); assertTrue(host.events.contains("cell"))
        host.fail = false; host.events.clear()
        assertEquals(2, host.plan.refresh(true).commands)
        assertEquals(listOf("windows:true", "panel", "cell"), host.events)
    }
    @Test fun disabledNeverProjectedPanelsAreNotRebuiltAndStaleTargetsAreSkipped() {
        val host = Host(); assertEquals(0, host.plan.refresh(false).commands)
        host.valid = false; host.events.clear(); val stale = host.plan.refresh(true)
        assertEquals(0, stale.commands); assertEquals(listOf("windows:true"), host.events)
        assertTrue(stale.deferred > 0)
    }
    @Test fun newVisibleTargetsAreRefreshedEvenWhenTheEnabledValueDidNotChange() {
        val host = Host(); host.plan.refresh(true); host.events.clear()
        host.plan.invalidate(); assertEquals(2, host.plan.refresh(true).commands)
        assertEquals(listOf("windows:true", "panel", "cell"), host.events)
    }
    @Test fun unknownCurrentPanelIsReportedInsteadOfClaimingAnAppliedLayout() {
        val host = Host(); host.targets.clear(); val result = host.plan.refresh(true)
        assertEquals(0, result.panels); assertEquals(0, result.commands); assertTrue(result.deferred > 0)
    }
}
