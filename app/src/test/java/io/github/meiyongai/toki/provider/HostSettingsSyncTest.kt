package io.github.meiyongai.toki.subtitle.provider

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class HostSettingsSyncTest {
    private val host = ManualHostAdaptation.packages[0]
    private fun prefs() = RuntimeEnvironment.getApplication().getSharedPreferences(UUID.randomUUID().toString(), Context.MODE_PRIVATE)
    private fun manager() = ConfigStore({}, { throw AssertionError(it) }).also { it.attachLocal(prefs()) }
    private fun hostStore(local: SharedPreferences, root: Boolean = false): ConfigStore {
        val result = ConfigStore({}, {})
        if (root) result.attachHost({ prefs().apply { edit().putInt(ConfigStore.PROTOCOL, 1).putLong(ConfigStore.REVISION, 0).commit() } }, {})
        else assertFalse(result.attachHost({ throw UnsupportedOperationException("no root service") }, {}))
        result.attachHostOverrides(local)
        return result
    }
    private fun pump(local: SharedPreferences, source: ConfigStore, tasks: MutableList<() -> Unit>, deliver: (String) -> HostSettingsAck,
                     statuses: MutableList<HostSettingsSyncStatus> = mutableListOf()) = HostSettingsMirrorPump(local, host,
        source::snapshotOrNull, deliver, { _, task -> tasks.add(task) }, statuses::add)
    private fun run(tasks: MutableList<() -> Unit>) { var count = 0; while (tasks.isNotEmpty()) { check(++count < 30); tasks.removeAt(0)() } }

    @Test fun codecRoundTripsBooleansStringsAndRemovalsWithoutInternalFields() {
        val patch = HostSettingsPatch(host, "request-1", mapOf("feed_remove_ads" to true, "subtitle_size" to "26", "target_region" to null))
        assertEquals(patch, HostSettingsWire.decode(HostSettingsWire.encode(patch)))
        assertThrows(IllegalArgumentException::class.java) { HostSettingsWire.encode(patch.copy(values = mapOf("__toki_revision" to "1"))) }
        assertThrows(IllegalArgumentException::class.java) { HostSettingsWire.encode(patch.copy(host = "other.app")) }
        assertThrows(IllegalArgumentException::class.java) { HostSettingsWire.encode(patch.copy(id = "bad id")) }
        assertThrows(IllegalArgumentException::class.java) { HostSettingsWire.encode(patch.copy(values = mapOf("feed_remove_ads" to "true"))) }
    }
    @Test fun malformedOrOversizedPayloadNeverBecomesAConfiguration() {
        for (text in listOf("[]", "{}", "{} trailing", "a".repeat(ConfigArchive.MAX_BYTES + 1)))
            assertThrows(Exception::class.java) { HostSettingsWire.decode(text) }
        val json = HostSettingsWire.encode(HostSettingsPatch(host, "id", mapOf("feed_remove_ads" to true)))
        assertThrows(IllegalArgumentException::class.java) { HostSettingsWire.decode(json.replace("\"protocol\":1", "\"protocol\":2")) }
    }
    @Test fun rootAndNoRootBothDeliverOnlyChangedKeysAndNotifyTheManager() {
        for (root in listOf(false, true)) {
            val local = prefs(); val source = hostStore(local, root); val target = manager()
            target.update(mapOf("target_region" to "JP"))
            val tasks = mutableListOf<() -> Unit>(); val status = mutableListOf<HostSettingsSyncStatus>()
            source.update(mapOf("feed_remove_ads" to true, "subtitle_size" to "28"))
            val pump = pump(local, source, tasks, { raw -> val patch = HostSettingsWire.decode(raw); HostSettingsAck(patch.id, target.applyHostPatch(patch)) }, status)
            pump.kick(); assertEquals(2, status.last().pending); run(tasks)
            assertTrue(target.snapshot().boolean("feed_remove_ads")); assertEquals("28", target.snapshot().string("subtitle_size"))
            assertEquals("JP", target.snapshot().string("target_region")); assertEquals(0, status.last().pending)
            assertNotNull(status.last().revision); assertNull(status.last().error)
            assertTrue(local.getStringSet(HostSettingsWire.PENDING, emptySet()).isNullOrEmpty())
        }
    }
    @Test fun pendingPayloadAndUserEditAreOneCommitAndSurviveProcessRestart() {
        val local = prefs(); val source = hostStore(local)
        source.update(mapOf("feed_remove_ads" to true))
        assertNotNull(local.getString(HostSettingsWire.REQUEST, null))
        assertEquals(true, HostSettingsWire.values(local.getString(HostSettingsWire.PAYLOAD, null)!!)["feed_remove_ads"])
        val restarted = hostStore(local); val tasks = mutableListOf<() -> Unit>(); val target = manager()
        pump(local, restarted, tasks, { raw -> val p = HostSettingsWire.decode(raw); HostSettingsAck(p.id, target.applyHostPatch(p)) }).kick()
        run(tasks); assertTrue(target.snapshot().boolean("feed_remove_ads"))
    }
    @Test fun serviceFailureRetainsOutboxAndAReopenCanRetryIt() {
        val local = prefs(); val source = hostStore(local); source.update(mapOf("subtitle_size" to "30"))
        val tasks = mutableListOf<() -> Unit>(); val status = mutableListOf<HostSettingsSyncStatus>(); var calls = 0
        val failed = pump(local, source, tasks, { calls++; throw IllegalStateException("provider absent") }, status)
        failed.kick(); run(tasks)
        assertEquals(4, calls); assertEquals(1, status.last().pending); assertNotNull(status.last().error)
        assertNotNull(local.getString(HostSettingsWire.REQUEST, null)); failed.stop()
        val target = manager()
        pump(local, source, tasks, { raw -> val p = HostSettingsWire.decode(raw); HostSettingsAck(p.id, target.applyHostPatch(p)) }).kick()
        run(tasks); assertEquals("30", target.snapshot().string("subtitle_size"))
    }
    @Test fun anOldAckCannotDiscardANewEditAndBothValuesEventuallyArrive() {
        val local = prefs(); val source = hostStore(local); val target = manager()
        source.update(mapOf("subtitle_size" to "24"))
        val tasks = mutableListOf<() -> Unit>(); var first = true
        val pump = pump(local, source, tasks, { raw ->
            val p = HostSettingsWire.decode(raw); val revision = target.applyHostPatch(p)
            if (first) { first = false; source.update(mapOf("subtitle_size" to "32", "feed_remove_ads" to true)) }
            HostSettingsAck(p.id, revision)
        })
        pump.kick(); run(tasks)
        assertEquals("32", target.snapshot().string("subtitle_size")); assertTrue(target.snapshot().boolean("feed_remove_ads"))
        assertTrue(local.getStringSet(HostSettingsWire.PENDING, emptySet()).isNullOrEmpty())
    }
    @Test fun ackLostRetryDoesNotOverwriteANewerManagerEditAndKeepsSamePayload() {
        val local = prefs(); val source = hostStore(local); val target = manager(); val tasks = mutableListOf<() -> Unit>()
        source.update(mapOf("subtitle_size" to "24")); var first = true; val sent = mutableListOf<String>()
        pump(local, source, tasks, { raw ->
            sent.add(raw); val p = HostSettingsWire.decode(raw); val revision = target.applyHostPatch(p)
            if (first) { first = false; target.update(mapOf("subtitle_size" to "35")); throw IllegalStateException("ack lost") }
            HostSettingsAck(p.id, revision)
        }).kick(); run(tasks)
        assertEquals("35", target.snapshot().string("subtitle_size")); assertEquals(sent[0], sent[1])
        assertTrue(local.getStringSet(HostSettingsWire.PENDING, emptySet()).isNullOrEmpty())
    }
    @Test fun removalAndFullImportAreMirroredNotSilentlyMergedAsOldValues() {
        val local = prefs(); val source = hostStore(local); val target = manager()
        target.update(mapOf("target_region" to "JP", "subtitle_size" to "20"))
        source.update(mapOf("target_region" to null, "subtitle_size" to "29"), replace = true)
        val tasks = mutableListOf<() -> Unit>()
        pump(local, source, tasks, { raw -> val p = HostSettingsWire.decode(raw); assertTrue(p.replace); HostSettingsAck(p.id, target.applyHostPatch(p)) }).kick()
        run(tasks); assertNull(target.snapshot().string("target_region")); assertEquals("29", target.snapshot().string("subtitle_size"))
        assertTrue(target.snapshot().importRevision > 0)
    }
    @Test fun duplicateReceiptSurvivesNormalEditsAndCannotCarryChangedValues() {
        val target = manager(); val patch = HostSettingsPatch(host, "id", mapOf("subtitle_size" to "24"))
        val revision = target.applyHostPatch(patch)
        target.update(mapOf("subtitle_size" to "34"))
        assertEquals(revision, target.applyHostPatch(patch)); assertEquals("34", target.snapshot().string("subtitle_size"))
        assertThrows(IllegalArgumentException::class.java) { target.applyHostPatch(patch.copy(values = mapOf("subtitle_size" to "25"))) }
        assertFalse(target.snapshot().configuration().containsKey(HostSettingsWire.RECEIPTS))
    }
    @Test fun stoppedSessionCannotPublishOrFlushQueuedWork() {
        val local = prefs(); val source = hostStore(local); source.update(mapOf("feed_remove_ads" to true)); val tasks = mutableListOf<() -> Unit>()
        val pump = pump(local, source, tasks, { fail("Disposed session must not call manager"); HostSettingsAck("", 0) })
        pump.kick(); pump.stop(); run(tasks)
        assertNotNull(local.getString(HostSettingsWire.REQUEST, null))
    }
    @Test fun hundredsOfRapidEditsCoalesceIntoOneLatestDelivery() {
        val local = prefs(); val source = hostStore(local); val target = manager()
        val tasks = mutableListOf<() -> Unit>(); var sends = 0
        val pump = pump(local, source, tasks, { raw -> sends++; val p = HostSettingsWire.decode(raw); HostSettingsAck(p.id, target.applyHostPatch(p)) })
        for (i in 0 until 100) { source.update(mapOf("subtitle_size" to (20 + i % 20).toString())); pump.kick() }
        assertEquals(1, tasks.size); run(tasks)
        assertEquals(1, sends); assertEquals("39", target.snapshot().string("subtitle_size"))
    }
    @Test fun failedOrForgedAckCannotClearDurableUserChanges() {
        val local = prefs(); val source = hostStore(local); val tasks = mutableListOf<() -> Unit>()
        source.update(mapOf("feed_remove_ads" to true))
        pump(local, source, tasks, { HostSettingsAck("wrong-id", 10) }).kick(); run(tasks)
        assertNotNull(local.getString(HostSettingsWire.REQUEST, null))
        assertTrue(source.snapshot().boolean("feed_remove_ads"))
    }
    @Test fun durableReceiptsAreBoundedAndSurviveManagerRecreation() {
        val preferences = prefs()
        val first = ConfigStore({}, { throw AssertionError(it) }).also { it.attachLocal(preferences) }
        var last: HostSettingsPatch? = null
        for (host in ManualHostAdaptation.packages) for (i in 0 until 40) {
            val patch = HostSettingsPatch(host, "request-$i", mapOf("subtitle_size" to "24"))
            first.applyHostPatch(patch); last = patch
        }
        assertTrue(org.json.JSONArray(preferences.getString(HostSettingsWire.RECEIPTS, null)).length() <= 64)
        val restarted = ConfigStore({}, { throw AssertionError(it) }).also { it.attachLocal(preferences) }
        restarted.update(mapOf("subtitle_size" to "40"))
        restarted.applyHostPatch(requireNotNull(last)); assertEquals("40", restarted.snapshot().string("subtitle_size"))
    }
    @Test fun managerListenerReceivesHostEditOnceAndReceiptIsNotExported() {
        var notifications = 0
        val target = ConfigStore({ notifications++ }, { throw AssertionError(it) })
        target.attachLocal(prefs())
        val before = notifications
        val patch = HostSettingsPatch(host, "notify", mapOf("subtitle_size" to "25"))
        target.applyHostPatch(patch); assertEquals(before + 1, notifications)
        target.applyHostPatch(patch); assertEquals(before + 1, notifications)
        assertEquals(setOf("subtitle_size"), target.snapshot().configuration().keys)
    }
    @Test fun rootlessMirrorPreservesOtherHostsManualAdaptationRequest() {
        val target = manager(); val other = ManualHostAdaptation.packages[1]
        target.update(ManualHostAdaptation.values(null, other, "trill-request"))
        target.applyHostPatch(HostSettingsPatch(host, "global-mirror", ManualHostAdaptation.values(null, host, "global-request")))
        val raw = target.snapshot().string(ManualHostAdaptation.KEY)
        assertEquals("trill-request", ManualHostAdaptation.token(raw, other, ""))
        assertEquals("global-request", ManualHostAdaptation.token(raw, host, ""))
        target.applyHostPatch(HostSettingsPatch(host, "import", mapOf("subtitle_size" to "26"), true))
        assertEquals(raw, target.snapshot().string(ManualHostAdaptation.KEY))
    }
    @Test fun configurationAndAckWritesShareTheSamePreferenceMonitor() {
        val base = prefs()
        lateinit var local: SharedPreferences
        local = object : SharedPreferences by base {
            override fun edit(): SharedPreferences.Editor {
                assertTrue("Local edits and ACK compare/clear must be mutually exclusive", Thread.holdsLock(local))
                return base.edit()
            }
        }
        val source = hostStore(local); val target = manager(); val tasks = mutableListOf<() -> Unit>()
        source.update(mapOf("subtitle_size" to "31"))
        pump(local, source, tasks, { raw -> val p = HostSettingsWire.decode(raw); HostSettingsAck(p.id, target.applyHostPatch(p)) }).kick()
        run(tasks)
        assertEquals("31", target.snapshot().string("subtitle_size"))
        assertTrue(local.getStringSet(HostSettingsWire.PENDING, emptySet()).isNullOrEmpty())
    }
}
