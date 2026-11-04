package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigSchema
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import io.github.meiyongai.toki.subtitle.provider.ManualHostAdaptation
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test

class HostManualAdaptationPlanTest {
    @Test fun manualRequestSurvivesSessionProjectionWithAllFeaturesDisabled() {
        val values = ConfigSchema.booleanDefaults.keys.associateWith { false } +
            ManualHostAdaptation.values(null, ManualHostAdaptation.packages[0], "manual")
        val request = ConfigSnapshot(values)
        val plan = HostFeaturePlan(request)
        assertFalse(plan.requiresScan)
        assertTrue(plan.features.isEmpty())
        assertTrue(ManualHostAdaptation.pending(plan.apply(request).string(ManualHostAdaptation.KEY),
            ManualHostAdaptation.packages[0], ""))
    }
    @Test fun freshFullPlanChecksEveryKnownRuleWithoutRetainingOldCoverage() {
        val rules = checkNotNull(javaClass.getResourceAsStream("/toki-host-rules.tsv")).bufferedReader().use { it.readText() }
        val targets = HostSymbol.entries.toSet()
        val selected = HostSymbols.selectRules(rules, targets)
        val names = selected.lineSequence().filter { it.isNotBlank() }.map { it.substringBefore('	') }.toSet()
        assertEquals(targets.map { it.name }.toSet(), names)
        val plan = HostScanPlan(Properties(), rules, "actual-host-identity", HostSymbols.INDEX_FORMAT, targets)
        assertEquals(targets, plan.pending)
        assertTrue(plan.retained.isEmpty())
    }
}
