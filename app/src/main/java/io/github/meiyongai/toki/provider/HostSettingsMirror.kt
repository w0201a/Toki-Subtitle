package io.github.meiyongai.toki.subtitle.provider

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A persistent single-flight outbox. Pending keys and id are committed with the host edit itself. */
internal class HostSettingsMirrorPump(
    private val preferences: SharedPreferences, private val host: String,
    private val snapshot: () -> ConfigSnapshot?, private val deliver: (String) -> HostSettingsAck,
    private val schedule: (Long, () -> Unit) -> Unit, private val report: (HostSettingsSyncStatus) -> Unit,
) {
    private var scheduled = false
    private var attempts = 0
    private var stopped = false
    private var draining = false
    private fun pending() = preferences.getStringSet(HostSettingsWire.PENDING, emptySet()).orEmpty().toSet()
    @Synchronized fun kick() { if (stopped) return; attempts = 0; publish(); queue(120) }
    @Synchronized fun stop() { stopped = true }
    private fun publish(error: String? = null) {
        val ackHost = preferences.getLong("__host_mirror_ack_host_v1", -1L)
        val localRevision = preferences.getLong(ConfigStore.REVISION, 0L)
        val count = pending().size
        report(HostSettingsSyncStatus(count,
            preferences.getLong(HostSettingsWire.ACK, -1L).takeIf { it >= 0 },
            error ?: if (count == 0 && ackHost < localRevision && localRevision > 0) "HistoricalLocalValuesNotConfirmed" else null))
    }
    @Synchronized private fun queue(delay: Long) {
        if (stopped || scheduled || pending().isEmpty()) return
        scheduled = true
        schedule(delay) {
            synchronized(this) {
                scheduled = false
                if (stopped || draining) return@schedule
                draining = true
            }
            try { flush() } finally { synchronized(this) { draining = false } }
        }
    }
    private fun flush() {
        try {
            val saved = synchronized(this) { if (stopped) return; preferences.all }
            @Suppress("UNCHECKED_CAST")
            val keys = (saved[HostSettingsWire.PENDING] as? Set<String>).orEmpty()
            if (keys.isEmpty()) { synchronized(this) { publish() }; return }
            val id = checkNotNull(saved[HostSettingsWire.REQUEST] as? String)
            val replace = saved[HostSettingsWire.REPLACE] == true

            checkNotNull(snapshot())
            // If a newer local commit raced the snapshot, discard this unsent old generation.
            synchronized(this) {
                if (preferences.getString(HostSettingsWire.REQUEST, null) != id) { publish(); queue(120); return }
            }
            val values = HostSettingsWire.values(checkNotNull(saved[HostSettingsWire.PAYLOAD] as? String))
            val ack = deliver(HostSettingsWire.encode(HostSettingsPatch(host, id, values, replace, saved[HostSettingsWire.RECONCILE] == true)))
            check(ack.id == id && ack.revision >= 0) { "Unconfirmed configuration delivery" }
            synchronized(this) {
                if (stopped) return
                // A new local edit changes the id in the same transaction, so an old ACK cannot erase it.
                synchronized(preferences) {
                    if (preferences.getString(HostSettingsWire.REQUEST, null) == id) {
                        check(preferences.edit().remove(HostSettingsWire.PENDING).remove(HostSettingsWire.REQUEST)
                            .remove(HostSettingsWire.REPLACE).remove(HostSettingsWire.RECONCILE).remove(HostSettingsWire.PAYLOAD).putLong(HostSettingsWire.ACK, ack.revision)
                            .putLong("__host_mirror_ack_host_v1", (saved[ConfigStore.REVISION] as? Long) ?: 0L).commit())
                    }
                }
                attempts = 0; publish(); queue(120)
            }
        } catch (failure: Exception) {
            synchronized(this) {
                if (stopped) return
                publish(failure.javaClass.simpleName) // Never log payloads, URLs, keys or credentials.
                attempts++
                if (attempts <= 3) queue(listOf(500L, 1500L, 5000L)[attempts - 1])
            }
        }
    }
}

/** Identical host-to-manager delivery for rooted frameworks and no-root injectors. */
internal object HostSettingsMirror {
    private val current = MutableStateFlow(HostSettingsSyncStatus())
    val status = current.asStateFlow()
    private val worker by lazy { Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Toki-settings-sync").apply { isDaemon = true } } }
    private var pump: HostSettingsMirrorPump? = null
    @Synchronized fun attach(context: Context) {
        if (pump != null) return
        val app = context.applicationContext
        require(app.packageName in ManualHostAdaptation.packages)
        pump = HostSettingsMirrorPump(app.getSharedPreferences("toki_host_settings", Context.MODE_PRIVATE), app.packageName,
            ConfigClient::requestedSnapshotOrNull,
            deliver = { payload ->
                val result = checkNotNull(app.contentResolver.call(Uri.parse("content://io.github.meiyongai.toki.subtitle.position"),
                    HostSettingsWire.METHOD, null, Bundle().apply { putString("patch", payload) }))
                check(result.getInt("protocol") == 1 && result.getBoolean("saved"))
                HostSettingsAck(checkNotNull(result.getString("id")), result.getLong("revision", -1))
            }, schedule = { delay, task -> worker.schedule({ task() }, delay, TimeUnit.MILLISECONDS) }, report = {
                val previous = current.value
                current.value = it
                if (previous != it) Log.i("TokiSettingsSync", "pending=${it.pending} revision=${it.revision} error=${it.error ?: "none"}")
            })
        ConfigClient.queueUnsyncedHostValues()
        pump?.kick()
    }
    @Synchronized fun kick() { pump?.kick() }
    @Synchronized fun detach() { pump?.stop(); pump = null; current.value = HostSettingsSyncStatus() }
}
