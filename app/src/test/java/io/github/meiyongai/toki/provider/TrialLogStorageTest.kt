package io.github.meiyongai.toki.subtitle.provider

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[35])
class TrialLogStorageTest {
    @Test fun budgetsHandleOversizedLegacyLogsAndReservationsWithoutOverflow() {
        val max=TrialLogStorage.MAX_TOTAL_BYTES
        assertTrue(TrialLogStorage.canReserve(0,0,max))
        assertFalse(TrialLogStorage.canReserve(max,0,1))
        assertFalse(TrialLogStorage.canReserve(Long.MAX_VALUE,0,1))
        assertFalse(TrialLogStorage.canReserve(max-10,9,2))
        assertTrue(TrialLogStorage.canReserve(max-10,9,1))
    }
    @Test fun parallelReservationsCannotSpendTheSameFreeStorageTwice() {
        val context=RuntimeEnvironment.getApplication()
        val remaining=TrialLogStorage.MAX_TOTAL_BYTES-TrialLogStorage.usage(context).bytes
        val first=checkNotNull(TrialLogStorage.reserve(context,remaining))
        assertNull(TrialLogStorage.reserve(context,1));first.close();first.close()
        val next=checkNotNull(TrialLogStorage.reserve(context,1));next.close()
    }
    @Test fun showingAClearPlanDoesNotDeleteOrIncludeLaterFiles() {
        val context=RuntimeEnvironment.getApplication()
        val folder=File(context.filesDir,"trial-logs/test-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        val before=File(folder,"old.txt").apply{writeText("preserved")}
        val plan=TrialLogStorage.plan(context)
        val after=File(folder,"later.txt").apply{writeText("also preserved")}
        assertTrue(before.canonicalFile in plan.files.map{it.canonicalFile});assertFalse(after.canonicalFile in plan.files.map{it.canonicalFile})
        assertEquals("preserved",before.readText());assertEquals("also preserved",after.readText())
        assertEquals(File(context.filesDir,"trial-logs").canonicalFile,plan.root)
        // Destructive confirmed clearing is deliberately not executed by this test.
    }
}
