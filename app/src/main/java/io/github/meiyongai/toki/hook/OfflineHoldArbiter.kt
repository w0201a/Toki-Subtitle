package io.github.meiyongai.toki.subtitle.hook

import java.lang.ref.WeakReference

/** A native speed gesture that actually started owns its press. Offline menus cannot steal that same sequence. */
internal class OfflineHoldArbiter {
    private class Entry(owner: Any, video: Any, val revision: Long) {
        val owner = WeakReference(owner); val video = WeakReference(video); var active = false
    }
    private val rows = mutableListOf<Entry>()
    fun candidate(owner: Any, video: Any, revision: Long) {
        finish(owner); rows.removeAll { it.owner.get() == null || it.video.get() == null }
        if (rows.size < 32) rows += Entry(owner, video, revision)
    }
    fun began(owner: Any, active: Boolean) { rows.firstOrNull { it.owner.get() === owner }?.active = active }
    fun owns(video: Any, revision: Long): Boolean = rows.any { it.active && it.video.get() === video && it.revision == revision && it.owner.get() != null }
    fun finish(owner: Any) { rows.removeAll { it.owner.get() == null || it.owner.get() === owner } }
    fun clear() = rows.clear()
}
