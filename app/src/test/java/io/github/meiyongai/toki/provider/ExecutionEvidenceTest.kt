package io.github.meiyongai.toki.subtitle.provider

import android.os.Bundle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ExecutionEvidenceTest {
    private fun evidence(revision: Long? = 7) = ExecutionEvidence(ExecutionEvidence.Scope.PLAYER, ExecutionEvidence.Action.APPLY,
        ExecutionEvidence.Outcome.COMMANDS_RETURNED, revision, true, mapOf(ExecutionEvidence.Metric.COMMANDS to 2L))
    @Test fun codecRoundTripAndNumericOrderingAreStable() {
        val source = evidence()
        val restored = ExecutionEvidence.decode(source.encode())!!
        assertEquals(source.signature(), restored.signature())
        assertEquals(source.summary(), restored.summary())
    }
    @Test fun missingUnknownOrMalformedPayloadNeverBecomesSuccess() {
        assertNull(ExecutionEvidence.decode(null))
        assertNull(ExecutionEvidence.decode(Bundle()))
        for (mutate in listOf<(Bundle) -> Unit>(
            { it.putInt("format", 99) }, { it.putString("scope", "unknown") },
            { it.putString("outcome", "unknown") }, { it.putString("revision", "7") },
            { it.putLong("revision", -1) }, { it.putString("enabled", "true") },
            { it.putBundle("metrics", Bundle().apply { putLong("unknown", 1) }) },
            { it.putBundle("metrics", Bundle().apply { putLong("COMMANDS", -1) }) }
        )) assertNull(ExecutionEvidence.decode(evidence().encode().apply(mutate)))
    }
    @Test fun commandsReturnedAreNotDescribedAsMeasuredPlaybackOrFullAcceptance() {
        val text = ExecutionEvidence.describe(evidence().encode(), 7)
        assertTrue(text.contains("实际效果仍待确认")); assertTrue(text.contains("不测量真实播放速率"))
        assertFalse(text.contains("全部完成"))
    }
    @Test fun oldMissingAndUnknownCurrentConfigurationAreDistinguished() {
        assertTrue(ExecutionEvidence.describe(evidence().encode(), 8).contains("旧配置执行证据"))
        assertTrue(ExecutionEvidence.describe(evidence(null).encode(), 8).contains("没有有效配置版本"))
        assertTrue(ExecutionEvidence.describe(evidence().encode(), null).contains("无法确认"))
    }
    @Test fun failuresAndDeferredWorkOverrideOptimisticOutcomeLabels() {
        for (metric in listOf(ExecutionEvidence.Metric.FAILED, ExecutionEvidence.Metric.RACED, ExecutionEvidence.Metric.DEFERRED, ExecutionEvidence.Metric.FIELD_FAILURES)) {
            val row = ExecutionEvidence(ExecutionEvidence.Scope.PLAYER, ExecutionEvidence.Action.APPLY,
                ExecutionEvidence.Outcome.COMMANDS_RETURNED, 1, true, mapOf(metric to 1L))
            assertTrue(row.summary().contains("尚未完全确认"))
        }
    }
    @Test fun crossFeatureEvidenceIsNotAcceptedForAnotherFeature() {
        assertTrue(ExecutionEvidence.describe(evidence().encode(), 7, "SpeedOptions").contains("不匹配"))
        assertFalse(ExecutionEvidence.describe(evidence().encode(), 7, "PlaybackSpeedHook").contains("不匹配"))
    }
    @Test fun callerMutableMetricsCannotChangeCapturedEvidence() {
        val values = mutableMapOf(ExecutionEvidence.Metric.KNOWN to 1L)
        val row = ExecutionEvidence(ExecutionEvidence.Scope.PLAYER, ExecutionEvidence.Action.APPLY, ExecutionEvidence.Outcome.NO_TARGET, 1, true, values)
        values[ExecutionEvidence.Metric.KNOWN] = 99L
        assertEquals(1L, row.metrics[ExecutionEvidence.Metric.KNOWN])
        assertThrows(IllegalArgumentException::class.java) { evidence(-1) }
    }
    @Test fun menuTemplateAndAudioLimitationsRemainVisible() {
        assertTrue(ExecutionEvidence.Scope.MENU.boundary.contains("重新打开"))
        assertTrue(ExecutionEvidence.Scope.TEMPLATE.boundary.contains("不证明完整截图"))
        assertTrue(ExecutionEvidence.Scope.AUDIO.boundary.contains("不重建宿主缓存"))
    }
}
