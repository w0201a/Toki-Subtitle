package io.github.meiyongai.toki.subtitle.hook

import java.lang.ref.WeakReference

/** Selected playback observations, not a wall-clock guess. Small progress jitter must not reset every frame. */
internal class CaptionPlaybackClock(private val rewindToleranceMs: Long = 250) {
    init { require(rewindToleranceMs >= 0) }
    private var owner = WeakReference<Any>(null)
    var video = ""
        private set
    var revision = 0L
        private set
    private var position: Long? = null

    fun select(id: String, holder: Any? = null): Boolean {
        if (video == id && (holder == null || owner.get() === holder)) return false
        video = id; owner = WeakReference<Any>(holder); position = null; revision++
        return true
    }
    fun progress(id: String, positionMs: Long): Boolean {
        if (id != video || id.isBlank() || positionMs < 0) return false
        val before = position
        position = positionMs
        if (before != null && before - positionMs > rewindToleranceMs) { revision++; return true }
        return false
    }
}
