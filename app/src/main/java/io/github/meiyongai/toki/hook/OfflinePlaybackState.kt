package io.github.meiyongai.toki.subtitle.hook

import java.lang.ref.WeakReference

/** Selected playback only: preparing an adjacent holder must not reclassify the current operation. */
internal class OfflinePlaybackState {
    private var owner = WeakReference<Any>(null)
    private var video = ""
    @Volatile var offline = false
        private set
    @Volatile var revision = 0L
        private set

    @Synchronized fun select(holder: Any, id: String, offline: Boolean) {
        if (owner.get() !== holder || video != id || this.offline != offline) revision++
        owner = WeakReference(holder); video = id; this.offline = offline
    }
    @Synchronized fun owns(holder: Any?, id: String): Boolean =
        id.isNotBlank() && owner.get() === holder && video == id
    @Synchronized fun retire(holder: Any) {
        if (owner.get() !== holder) return
        owner.clear(); video = ""; revision++ // Preserve known content classification; it does not choose the networking policy.
    }
    @Synchronized fun clear(holder: Any? = null) {
        if (holder != null && owner.get() !== holder) return
        owner.clear(); video = ""; offline = false; revision++
    }
}

/** No Activity, view or comment body is retained globally. */
internal object OfflinePlaybackContext { val state = OfflinePlaybackState() }
