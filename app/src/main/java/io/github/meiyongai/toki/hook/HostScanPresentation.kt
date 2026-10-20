package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import io.github.meiyongai.toki.subtitle.R

internal object HostScanPresentation {
    fun summary(context: Context, state: HostScanStatus): String = when (state.phase) {
        HostScanPhase.IDLE -> context.getString(R.string.host_scan_no_pending)
        HostScanPhase.READY -> context.getString(R.string.host_scan_title_complete)
        HostScanPhase.PARTIAL -> context.getString(R.string.host_scan_title_partial)
        HostScanPhase.FAILED -> context.getString(R.string.host_scan_title_failed)
        HostScanPhase.SAVING -> context.getString(R.string.host_scan_title_saving)
        HostScanPhase.SCANNING -> context.getString(R.string.host_scan_title_running) +
            if (state.total > 0) " ${state.completed * 100L / state.total}% (${state.completed}/${state.total})" else "…"
        HostScanPhase.EXTENSIONS -> context.getString(when (state.extensionStage) {
            "native" -> R.string.host_scan_stage_native
            "relationships", "comment-gate" -> R.string.host_scan_stage_relationships
            "members" -> R.string.host_scan_stage_members
            else -> R.string.host_scan_stage_structure
        }) + if (state.extensionTotal > 0) " ${state.extensionCompleted}/${state.extensionTotal}" else "…"
    }
}
