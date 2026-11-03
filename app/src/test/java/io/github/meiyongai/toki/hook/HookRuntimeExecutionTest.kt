package io.github.meiyongai.toki.subtitle.hook

import android.os.Bundle
import io.github.meiyongai.toki.subtitle.provider.ExecutionEvidence
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class HookRuntimeExecutionTest {
    private fun features() = HookRuntime::class.java.getDeclaredField("features").apply { isAccessible = true }.get(null) as Bundle
    private fun evidence(revision: Long) = ExecutionEvidence(ExecutionEvidence.Scope.PLAYER, ExecutionEvidence.Action.APPLY,
        ExecutionEvidence.Outcome.NO_TARGET, revision, true)
    private val saved = Bundle()
    @Before fun reset() {
        for (name in listOf("PlaybackSpeedHook", "SpeedOptions")) {
            features().getBundle(name)?.let { saved.putBundle(name, it.deepCopy()) }; features().remove(name)
        }
    }
    @After fun restore() {
        for (name in listOf("PlaybackSpeedHook", "SpeedOptions")) {
            features().remove(name); saved.getBundle(name)?.let { features().putBundle(name, it) }
        }
    }
    @Test fun identicalSamplesUpdateCountWithoutFillingSemanticHistory() {
        repeat(50) { HookRuntime.execution("PlaybackSpeedHook", evidence(1)) }
        val row = features().getBundle("PlaybackSpeedHook")!!
        assertEquals(50L, row.getLong("executionObservations"))
        assertEquals(1, row.getBundle("executionHistory")!!.size())
        assertEquals(1L, row.getBundle("execution")!!.getLong("revision"))
    }
    @Test fun changingConfigurationsRetainOnlySixNewestDistinctSamples() {
        repeat(10) { HookRuntime.execution("PlaybackSpeedHook", evidence(it.toLong())) }
        val history = features().getBundle("PlaybackSpeedHook")!!.getBundle("executionHistory")!!
        assertEquals(6, history.size())
        assertEquals(9L, history.getBundle("0")!!.getLong("revision"))
        assertEquals(4L, history.getBundle("5")!!.getLong("revision"))
    }
    @Test fun executionEvidenceDoesNotRewriteApplicationRegistrationOrErrors() {
        val row = Bundle().apply { putInt("registered", 4); putString("state", "注册失败，已撤销"); putBoolean("appliedEnabled", false) }
        features().putBundle("PlaybackSpeedHook", row)
        HookRuntime.execution("PlaybackSpeedHook", evidence(2))
        assertEquals(4, row.getInt("registered")); assertFalse(row.getBoolean("appliedEnabled"))
        assertEquals("注册失败，已撤销", row.getString("state"))
        HookRuntime.execution("SpeedOptions", evidence(2))
        assertNull(features().getBundle("SpeedOptions"))
    }
}
