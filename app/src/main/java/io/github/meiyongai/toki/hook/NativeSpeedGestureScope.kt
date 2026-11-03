package io.github.meiyongai.toki.subtitle.hook

/** Synchronous thread-local gate override: unrelated service calls and threads always retain native decisions. */
internal class NativeSpeedGestureScope {
    private val depth = ThreadLocal<Int>()
    val active: Boolean get() = (depth.get() ?: 0) > 0
    fun <T> enter(call: () -> T): T {
        val previous = depth.get() ?: 0
        depth.set(previous + 1)
        try { return call() } finally {
            if (previous == 0) depth.remove() else depth.set(previous)
        }
    }
}

/** Minimal ownership state, not a replacement speed/lock algorithm. Native TikTok still decides gesture geometry. */
internal class HoldSpeedSessions(private val capacity: Int = 16) {
    class Session(owner: Any, view: Any) {
        val owner = java.lang.ref.WeakReference(owner)
        val view = java.lang.ref.WeakReference(view)
        var cancelled = false
        var cleanup: AutoCloseable? = null
    }
    private val sessions = java.util.WeakHashMap<Any, Session>()
    init { require(capacity > 0) }
    fun begin(delegate: Any, owner: Any, view: Any): Session? {
        if (delegate !in sessions && sessions.size >= capacity) return null
        remove(delegate)
        return Session(owner, view).also { sessions[delegate] = it }
    }
    operator fun get(delegate: Any): Session? = sessions[delegate]
    fun forOwner(owner: Any): Session? = sessions.values.firstOrNull { it.owner.get() === owner }
    fun remove(delegate: Any) { sessions.remove(delegate)?.cleanup?.close() }
    fun removeOwner(owner: Any) { sessions.entries.filter { it.value.owner.get() === owner }.map { it.key }.forEach(::remove) }
    fun entries(): List<Pair<Any, Session>> = sessions.entries.map { it.key to it.value }
    fun clear() { sessions.keys.toList().forEach(::remove) }
}

/** Disabled experiment groups can report zero pixels; use a local 72dp fallback without changing their cached value. */
internal object HoldSpeedThreshold {
    fun pixels(native: Float, density: Float, touchSlop: Float): Float {
        require(density.isFinite() && density > 0 && touchSlop.isFinite() && touchSlop >= 0)
        return if (native.isFinite() && native > touchSlop) native else maxOf(72f * density, touchSlop * 3f)
    }
}
