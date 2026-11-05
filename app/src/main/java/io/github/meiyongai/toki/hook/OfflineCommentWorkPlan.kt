package io.github.meiyongai.toki.subtitle.hook

/** Five videos per scheduling turn. Source and translation have independent clocks and failure budgets. */
internal object OfflineCommentWorkPlan {
    const val BATCH_SIZE = 5
    enum class Lane { SOURCE, TRANSLATION }
    data class Job(val key: String, val firstPage: Boolean, val sourceReady: Boolean, val translationReady: Boolean,
        val retryAt: Long, val lastRun: Long)
    fun batch(jobs: List<Job>, lane: Lane, now: Long): List<Job> = jobs.asSequence()
        .filter { if (lane == Lane.SOURCE) !it.sourceReady else it.firstPage && !it.translationReady }
        .filter { it.retryAt <= now }
        .sortedWith(compareBy<Job> { if (lane == Lane.SOURCE && !it.firstPage) 0 else 1 }.thenBy { it.lastRun }.thenBy { it.key })
        .take(BATCH_SIZE).toList()
    fun nextDelay(jobs: List<Job>, lane: Lane, now: Long): Long? = jobs.asSequence()
        .filter { if (lane == Lane.SOURCE) !it.sourceReady else it.firstPage && !it.translationReady }
        .map { (it.retryAt - now).coerceAtLeast(250L) }.minOrNull()
    fun retryDelay(attempt: Int): Long = when {
        attempt <= 1 -> 5_000L
        attempt == 2 -> 15_000L
        attempt == 3 -> 60_000L
        else -> 300_000L
    }
}
