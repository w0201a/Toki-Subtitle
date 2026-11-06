package io.github.meiyongai.toki.subtitle.util

/** A retired Binder may die after its replacement has already connected. Identity, not equals, owns disconnect. */
internal class FrameworkServiceConnection {
    private var current: Any? = null
    @Synchronized fun bind(service: Any, connected: () -> Unit) {
        current = service
        connected()
    }
    @Synchronized fun died(service: Any, disconnected: () -> Unit) {
        if (current !== service) return
        current = null
        disconnected()
    }
}
