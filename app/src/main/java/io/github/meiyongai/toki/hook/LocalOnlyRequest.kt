package io.github.meiyongai.toki.subtitle.hook

/** An operation observed to lose remote eligibility can never regain it. No transport lives here. */
internal class LocalOnlyRequest(private val remoteAllowed: () -> Boolean) {
    @Volatile private var blocked = !sample()
    private fun sample(): Boolean = try { remoteAllowed() } catch (_: Exception) { false }

    @Synchronized fun permitsNetwork(): Boolean {
        if (!blocked && !sample()) blocked = true
        return !blocked
    }
}
