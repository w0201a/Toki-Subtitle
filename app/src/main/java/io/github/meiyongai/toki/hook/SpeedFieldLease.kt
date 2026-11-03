package io.github.meiyongai.toki.subtitle.hook

import java.lang.reflect.Field

/** Conditional field ownership. Failed fields retain their evidence for a later restore attempt. */
internal class SpeedFieldLease(
    private val fields: Map<String, Field>,
    private val read: (Field) -> Any? = { it.get(null) },
    private val write: (Field, Any?) -> Unit = { field, value -> field.set(null, value) }
) {
    private val baseline = mutableMapOf<String, Any?>()
    private val written = mutableMapOf<String, Any?>()

    @Synchronized fun apply(speed: Float, persist: Boolean, observeExternalChanges: Boolean = true) {
        require(speed.isFinite() && speed in 0.1f..3f)
        eachField { name, field ->
            val current = read(field)
            if (name !in baseline || observeExternalChanges && name in written && current != written[name]) baseline[name] = current
            val value: Any = if (name == "enabled") persist else speed
            write(field, value)
            written[name] = value
        }
    }

    /** Method success does not prove every field changed. Retire only observable native changes. */
    @Synchronized fun observeNativeState() {
        if (baseline.isEmpty()) return
        eachField { name, field ->
            if (name in baseline) {
                val current = read(field)
                if (name !in written || current != written[name]) {
                    baseline[name] = current
                    written.remove(name)
                }
            }
        }
    }

    @Synchronized fun restore() {
        eachField { name, field ->
            if (name in written && read(field) == written[name]) write(field, baseline.getValue(name))
            // Preserve externally changed fields. Failed fields never reach this release.
            written.remove(name)
            baseline.remove(name)
        }
    }

    private fun eachField(action: (String, Field) -> Unit) {
        var failure: Exception? = null
        for ((name, field) in fields) try { action(name, field) } catch (error: Exception) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
}
