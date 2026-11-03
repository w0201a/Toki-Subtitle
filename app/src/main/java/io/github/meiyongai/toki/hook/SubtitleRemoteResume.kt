package io.github.meiyongai.toki.subtitle.hook

/** Bounded connectivity sampling, not a retry timer: only a denied-to-allowed transition retries a missing result. */
internal class SubtitleRemoteResume {
    private var available: Boolean? = null
    private var nextCheckAt = Long.MIN_VALUE
    fun observe(nowMs: Long, remoteAvailable: () -> Boolean): Boolean {
        if (nowMs < nextCheckAt) return false
        nextCheckAt = nowMs + 1000
        val next = remoteAvailable()
        val resumed = available == false && next
        available = next
        return resumed
    }
}
