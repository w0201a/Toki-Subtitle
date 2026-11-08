package io.github.meiyongai.toki.subtitle.update

import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean

internal data class UpdateState(
    val nextAutomaticAt: Long = 0,
    val pending: AppRelease? = null,
    val notified: ReleaseVersion? = null,
)
internal interface UpdateStateStore {
    val lock: Any get() = this
    fun read(): UpdateState
    /** Must be synchronous and fail if persistence is not acknowledged. */
    fun write(state: UpdateState)
}
internal fun interface ReleaseSource { fun latest(): AppRelease? }
internal sealed interface UpdateResult {
    data class Available(val release: AppRelease) : UpdateResult
    data object UpToDate : UpdateResult
    data object NotDue : UpdateResult
    data object Busy : UpdateResult
    data object Failed : UpdateResult
}

/** Standalone app only. No alarms, background services, host hooks, package installs, or feature-setting writes. */
internal class UpdateEngine(
    private val installed: ReleaseVersion,
    private val store: UpdateStateStore,
    private val source: ReleaseSource,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val checking = AtomicBoolean()
    private val stateLock = store.lock

    /** First foreground check once, then at most once per calendar-month interval; manual explicitly bypasses it. */
    fun check(manual: Boolean): UpdateResult {
        if (!checking.compareAndSet(false, true)) return UpdateResult.Busy
        try {
            synchronized(stateLock) {
                val state = store.read()
                if (!manual) {
                    state.pending?.let { release ->
                        if (release.version > installed && (state.notified == null || release.version > state.notified))
                            return UpdateResult.Available(release)
                    }
                    if (state.nextAutomaticAt > now()) return UpdateResult.NotDue
                }
                // Reserve before networking, including failures. Restarting the app cannot retry-spam GitHub.
                store.write(state.copy(nextAutomaticAt = nextMonth(now())))
            }
            val release = source.latest()
            synchronized(stateLock) {
                val state = store.read()
                if (release == null || release.version <= installed) return UpdateResult.UpToDate
                store.write(state.copy(pending = release))
                if (!manual && state.notified != null && release.version <= state.notified) return UpdateResult.NotDue
                return UpdateResult.Available(release)
            }
        } catch (_: Exception) {
            return UpdateResult.Failed
        } finally { checking.set(false) }
    }

    /** Call immediately before presentation. Acceptance of a network response alone does not consume the notice. */
    fun claimNotice(release: AppRelease, manual: Boolean): Boolean = synchronized(stateLock) {
        try {
            val state = store.read()
            if (release.version <= installed || (!manual && state.notified != null && release.version <= state.notified)) return false
            val highest = maxOf(release.version, state.notified ?: release.version)
            store.write(state.copy(notified = highest))
            true
        } catch (_: Exception) { false }
    }

    companion object {
        fun nextMonth(time: Long): Long = Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).plusMonths(1).toInstant().toEpochMilli()
    }
}
