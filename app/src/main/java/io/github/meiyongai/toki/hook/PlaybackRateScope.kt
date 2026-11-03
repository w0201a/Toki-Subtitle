package io.github.meiyongai.toki.subtitle.hook

/** Module setters cannot become native baselines; native menu selections must not be overridden mid-call. */
internal class PlaybackRateScope {
    private val generated = ThreadLocal<Int>()
    private val selections = ThreadLocal<Int>()
    val applying: Boolean get() = (generated.get() ?: 0) > 0
    val selecting: Boolean get() = (selections.get() ?: 0) > 0
    fun <T> module(call: () -> T): T = nested(generated, call)
    fun <T> nativeSelection(call: () -> T): T = nested(selections, call)
    private fun <T> nested(depth: ThreadLocal<Int>, call: () -> T): T {
        val previous = depth.get() ?: 0
        depth.set(previous + 1)
        try { return call() } finally {
            if (previous == 0) depth.remove() else depth.set(previous)
        }
    }
}
