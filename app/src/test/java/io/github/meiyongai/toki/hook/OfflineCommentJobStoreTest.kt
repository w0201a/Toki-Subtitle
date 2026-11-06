package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class OfflineCommentJobStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences("jobs-test", Context.MODE_PRIVATE)
    private fun row(account: String = "a", video: String = "video") = JSONObject()
        .put("account", account).put("video", video).put("epoch", 0L).put("cursor", 0L).put("roots", JSONArray())
        .put("sourceRevision", 1L).put("firstPage", true).put("sourceReady", false).put("translationReady", false)
    @Test fun sourceAndTranslationUpdatesMergeWithoutResettingEachOtherOrTheCursor() {
        val store = OfflineCommentJobStore(prefs()); store.put("key", row())
        store.change("key", 0L) { it.put("cursor", 20L).put("sourceRevision", 2L) }
        store.change("key", 0L) { it.put("translationAttempts", 2) }
        store.register("key", row()) { it.put("firstPage", true) }
        assertEquals(20L, store.get("key")!!.getLong("cursor")); assertEquals(2, store.get("key")!!.getInt("translationAttempts"))
        assertEquals(20L, OfflineCommentJobStore(prefs()).get("key")!!.getLong("cursor"))
    }
    @Test fun translationCannotCompleteARevisionWithNewOriginalPages() {
        val store = OfflineCommentJobStore(prefs()); store.put("key", row().put("sourceReady", true))
        store.change("key", 0L) { it.put("sourceRevision", 2L).put("translationReady", false) }
        assertFalse(store.finishTranslation("key", 0L, 1L)); assertFalse(store.get("key")!!.getBoolean("translationReady"))
        assertTrue(store.finishTranslation("key", 0L, 2L)); assertEquals("complete", store.get("key")!!.getString("state"))
    }
    @Test fun translatedFirstPageIsStillPendingUntilAllOriginalsAndRepliesAreReady() {
        val store = OfflineCommentJobStore(prefs()); store.put("key", row())
        assertTrue(store.finishTranslation("key", 0L, 1L))
        assertEquals("pending", store.get("key")!!.getString("state"))
        assertTrue(store.get("key")!!.getBoolean("translationReady")); assertFalse(store.get("key")!!.getBoolean("sourceReady"))
    }
    @Test fun epochChangeRejectsStaleSourceTranslationAndRegistrationWritersWithoutClearingAnything() {
        val store = OfflineCommentJobStore(prefs()); store.put("key", row())
        prefs().edit().putLong("epoch:a", 1L).commit()
        assertFalse(store.change("key", 0L) { it.put("cursor", 999L) })
        assertFalse(store.finishTranslation("key", 0L, 1L))
        assertFalse(store.register("key", row().put("epoch", 1L)) { it.put("cursor", 888L) })
        assertEquals(0L, store.get("key")!!.getLong("cursor"))
    }
    @Test fun retryAndEnumerationAreAccountIsolatedAndKeepCompletedData() {
        val store = OfflineCommentJobStore(prefs())
        store.put("one", row().put("sourceRetryAt", 999L).put("cursor", 20L))
        store.put("other", row("b").put("sourceRetryAt", 999L))
        store.retry("a")
        assertEquals(0L, store.get("one")!!.getLong("sourceRetryAt")); assertEquals(20L, store.get("one")!!.getLong("cursor"))
        assertEquals(999L, store.get("other")!!.getLong("sourceRetryAt")); assertEquals(listOf("one"), store.rows("a").map { it.first })
    }
    @Test fun legacyMigrationCannotResetAnAdvancedRevisionAndAdmitAStaleCompletion() {
        val store = OfflineCommentJobStore(prefs()); store.put("key", row().put("sourceReady", true))
        store.change("key", 0L) { it.put("sourceRevision", 3L) }
        assertTrue(store.register("key", row()) { it.put("schema", 2).put("sourceRevision", 1L) })
        assertEquals(3L, store.get("key")!!.getLong("sourceRevision"))
        assertFalse(store.finishTranslation("key", 0L, 1L))
        assertTrue(store.finishTranslation("key", 0L, 3L))
    }

    @Test fun completionRequiresMediaAndStaleMediaCannotFinishANewSourceRevision() {
        val store = OfflineCommentJobStore(prefs())
        store.put("rich", row().put("sourceReady", true).put("mediaReady", false))
        assertTrue(store.finishTranslation("rich", 0L, 1L))
        assertEquals("pending", store.get("rich")!!.getString("state"))
        store.change("rich", 0L) { it.put("sourceRevision", 2L) }
        assertFalse(store.finishMedia("rich", 0L, 1L))
        assertFalse(store.get("rich")!!.getBoolean("mediaReady"))
        assertTrue(store.finishMedia("rich", 0L, 2L))
        assertEquals("complete", store.get("rich")!!.getString("state"))
        assertTrue(store.get("rich")!!.getBoolean("translationReady"))
    }
    @Test fun mediaLaneHasItsOwnRetryClockAndDoesNotWaitForTranslation() {
        val store = OfflineCommentJobStore(prefs())
        store.put("media", row().put("mediaReady", false).put("translationRetryAt", 9000L).put("mediaRetryAt", 0L))
        assertEquals(listOf("media"), OfflineCommentWorkPlan.batch(store.plans("a", OfflineCommentWorkPlan.Lane.MEDIA),
            OfflineCommentWorkPlan.Lane.MEDIA, 1L).map { it.key })
        assertTrue(OfflineCommentWorkPlan.batch(store.plans("a", OfflineCommentWorkPlan.Lane.TRANSLATION),
            OfflineCommentWorkPlan.Lane.TRANSLATION, 1L).isEmpty())
        assertTrue(store.finishMedia("media", 0L, 1L))
        assertEquals("pending", store.get("media")!!.getString("state"))
        assertFalse(store.get("media")!!.getBoolean("translationReady"))
    }
    @Test fun oldTwentyRowCompleteJobIsReopenedWithoutDroppingAccountOrExistingArchives() {
        val row = JSONObject().put("account", "a".repeat(64)).put("video", "saved-video")
            .put("schema", 3).put("cacheLimit", 200).put("firstPage", true).put("sourceReady", true)
            .put("translationReady", true).put("mediaReady", true).put("sourceRevision", 8)
            .put("state", "complete").put("epoch", 3)
        assertTrue(OfflineCommentJobStore.repairSource(row, 200, true))
        assertEquals(7, row.getInt("schema")); assertEquals(9L, row.getLong("sourceRevision"))
        assertTrue(row.getBoolean("firstPage")); assertTrue(row.getBoolean("refreshSource"))
        assertFalse(row.getBoolean("sourceReady")); assertFalse(row.getBoolean("translationReady")); assertFalse(row.getBoolean("mediaReady"))
        assertEquals("saved-video", row.getString("video")); assertEquals(3L, row.getLong("epoch"))
        assertFalse(OfflineCommentJobStore.repairSource(row, 200, true))
    }

    @Test fun replyLimitChangeReopensSourceButKeepsItsAccountAndEpoch() {
        val row = JSONObject().put("account", "a".repeat(64)).put("video", "quota-change").put("epoch", 7)
        assertTrue(OfflineCommentJobStore.repairSource(row, 200, true, OfflineCommentBudget.Quotas(200, 1000, 50)))
        assertFalse(OfflineCommentJobStore.repairSource(row, 200, true, OfflineCommentBudget.Quotas(200, 1000, 50)))
        assertTrue(OfflineCommentJobStore.repairSource(row, 200, true, OfflineCommentBudget.Quotas(200, 200, 10)))
        assertEquals(200, row.getInt("replyLimit")); assertEquals(10, row.getInt("threadLimit")); assertEquals(7, row.getInt("epoch"))
    }

    @Test fun fullyTranslatedPartialServerCoverageNeverBecomesACompleteArchive() {
        val prefs = org.robolectric.RuntimeEnvironment.getApplication().getSharedPreferences("partial-source", android.content.Context.MODE_PRIVATE)
        val store = OfflineCommentJobStore(prefs)
        store.put("partial", org.json.JSONObject().put("account", "a".repeat(64)).put("video", "partial").put("epoch", 0L)
            .put("sourceRevision", 1L).put("sourceReady", true).put("sourceIncomplete", true).put("mediaReady", true))
        assertTrue(store.finishTranslation("partial", 0L, 1L))
        assertEquals("partial", store.get("partial")!!.getString("state"))
        assertTrue(store.finishMedia("partial", 0L, 1L))
        assertEquals("partial", store.get("partial")!!.getString("state"))
    }

}
