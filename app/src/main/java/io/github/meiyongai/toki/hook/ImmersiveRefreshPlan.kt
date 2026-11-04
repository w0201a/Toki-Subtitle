package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import java.util.WeakHashMap

/** Coordinates the real runtime callbacks; offscreen disable work survives until the same panel returns. */
internal class ImmersiveRefreshPlan(
    private val targets: () -> List<ImmersiveLayoutPreparation.Target>,
    private val visible: (View) -> Boolean,
    private val current: (ImmersiveLayoutPreparation.Target) -> Boolean,
    private val windows: (Boolean) -> Unit,
    private val releaseCaches: () -> Unit,
    private val panel: (Any) -> Unit,
    private val cell: (Any) -> Unit,
    private val report: (Exception) -> Unit = {},
) {
    data class Result(val commands: Int, val panels: Int, val deferred: Int, val failed: Int)
    private val applied = WeakHashMap<Any, Boolean>()
    var active = false
        private set
    private var dirty = false
    fun invalidate() { dirty = true }
    fun panelApplied(owner: Any, ownedProjection: Boolean) { applied[owner] = ownedProjection }
    fun refresh(wanted: Boolean): Result {
        var commands = 0; var failures = 0; var deferred = 0
        fun attempt(action: () -> Unit) { try { action() } catch (error: Exception) { failures++; report(error) } }
        val changed = active != wanted
        active = wanted
        attempt { windows(wanted) }
        val rows = targets()
        val panels = rows.count { it.panel && visible(it.root) && current(it) }
        val pending = rows.any { it.panel && applied[it.owner] != wanted && (wanted || applied[it.owner] == true) }
        if (changed || dirty && wanted || pending) {
            dirty = false
            if (!wanted) attempt(releaseCaches)
            for (target in rows.filter { it.panel }) {
                if (!current(target) || !visible(target.root)) {
                    if (applied[target.owner] != wanted && (wanted || applied[target.owner] == true)) deferred++
                    continue
                }
                attempt { panel(target.owner); target.root.requestLayout(); commands++ }
            }
            for (target in rows.filter { !it.panel }) {
                if (!current(target) || !visible(target.root)) continue
                attempt { cell(target.owner); target.root.requestLayout(); commands++ }
            }
        }
        if (wanted && panels == 0) deferred = maxOf(1, deferred)
        return Result(commands, panels, deferred, failures)
    }
}
