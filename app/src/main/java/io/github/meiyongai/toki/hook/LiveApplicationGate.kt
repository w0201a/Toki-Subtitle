package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot

/** Registration, session admission and latest configuration are distinct; revocation is permanent. */
internal class LiveApplicationGate(
    private val scope: HookRuntime.RegistrationScope,
    private val latest: () -> ConfigSnapshot?,
    private val failed: (Exception) -> Unit = {},
) : AutoCloseable {
    @Volatile private var admitted = false
    @Volatile private var closed = false
    @Synchronized fun admit(): Boolean {
        if (closed || !scope.active || !scope.committed) return false
        admitted = true
        return true
    }
    fun ready(): Boolean = !closed && admitted && scope.active && scope.committed
    fun configuration(): ConfigSnapshot? {
        if (!ready()) return null
        val snapshot = try { latest() } catch (error: Exception) { failed(error); null }
        return if (ready()) snapshot else null
    }
    override fun close() { closed = true; admitted = false }
}
