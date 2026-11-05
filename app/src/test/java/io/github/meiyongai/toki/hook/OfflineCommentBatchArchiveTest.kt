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
class OfflineCommentBatchArchiveTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val account = "a".repeat(64)
    private fun comment(id: String, translated: String? = null, parent: String = "") = OfflineCommentData.Row(
        id, "author", "handle", "uid", "original-$id", translated, if (translated == null) "" else "zh-hans", if (parent.isEmpty()) 0 else 1, 0L, parentId = parent)
    private fun page(video: String, rows: List<OfflineCommentData.Row>, total: Long = rows.size.toLong()) =
        OfflineCommentData.Snapshot(account, video, 10L, rows, total, false, false, null)
    @Test fun sixtyDownloadedVideosRemainReadableAfterBatchedPreparationAndStoreReopen() {
        val preferences = app.getSharedPreferences("batch-integration", Context.MODE_PRIVATE)
        val jobs = OfflineCommentJobStore(preferences); val ingress = OfflineCommentIngress<String>()
        repeat(60) { ingress.offer("video-$it", "video-$it") }
        assertTrue(ingress.take(false).isEmpty())
        while (ingress.size() > 0) for (video in ingress.take(true)) {
            val key = CommentSnapshotStore.key(account, video)
            jobs.put(key, JSONObject().put("account", account).put("video", video).put("epoch", 0L).put("sourceRevision", 1L)
                .put("roots", JSONArray()).put("firstPage", false).put("sourceReady", false).put("translationReady", false))
        }
        var calls = 0
        while (jobs.rows(account).any { !it.second.optBoolean("sourceReady") }) {
            val batch = OfflineCommentWorkPlan.batch(jobs.plans(account, OfflineCommentWorkPlan.Lane.SOURCE), OfflineCommentWorkPlan.Lane.SOURCE, 100L)
            assertTrue(batch.size in 1..5)
            for (item in batch) {
                val video = jobs.get(item.key)!!.getString("video"); calls++
                OfflineCommentArchive.save(app, page(video, listOf(comment(video))), 0L)
                jobs.change(item.key, 0L) { it.put("firstPage", true).put("sourceReady", true).put("sourceLastRun", 100L) }
            }
        }
        assertEquals(60, calls)
        assertEquals(0, jobs.rows(account).count { it.second.optBoolean("translationReady") })
        while (jobs.rows(account).any { !it.second.optBoolean("translationReady") }) {
            val batch = OfflineCommentWorkPlan.batch(jobs.plans(account, OfflineCommentWorkPlan.Lane.TRANSLATION), OfflineCommentWorkPlan.Lane.TRANSLATION, 100L)
            for (item in batch) {
                val job = jobs.get(item.key)!!; val video = job.getString("video")
                val work = OfflineCommentArchive.nextTranslation(app, account, video, "zh-hans")!!
                assertTrue(OfflineCommentArchive.commitTranslation(app, account, video, work.row, "zh-hans", "译文-$video") { true })
                assertTrue(OfflineCommentArchive.translationsReady(app, account, video, "zh-hans"))
                assertTrue(jobs.finishTranslation(item.key, 0L, 1L))
            }
        }
        val reopened = OfflineCommentJobStore(preferences)
        assertEquals(60, reopened.rows(account).count { it.second.optString("state") == "complete" })
        repeat(60) {
            val rows = OfflineCommentArchive.window(app, account, "video-$it")!!.snapshot.rows
            assertEquals("original-video-$it", rows.single().source); assertEquals("译文-video-$it", rows.single().translation)
        }
    }
    @Test fun staleTranslationCannotCreateAGuessedRootOrReplaceAChangedOriginal() {
        val video = "reply-page"; val original = comment("reply", parent = "parent")
        OfflineCommentArchive.save(app, page(video, listOf(original)), 20L, "parent")
        assertFalse(OfflineCommentArchive.commitTranslation(app, account, video, original.copy(source = "stale"), "zh-hans", "wrong") { true })
        assertNull(OfflineCommentArchive.page(app, account, video, 0L))
        assertTrue(OfflineCommentArchive.commitTranslation(app, account, video, original, "zh-hans", "reply translation") { true })
        assertEquals("reply translation", OfflineCommentArchive.page(app, account, video, 20L, "parent")!!.rows.single().translation)
    }
    @Test fun translationFailureOrCancellationRetainsOriginalAndNeverMarksAnEmptyArchiveReady() {
        val video = "retained"; val original = comment("one")
        assertFalse(OfflineCommentArchive.translationsReady(app, account, video, "zh-hans"))
        OfflineCommentArchive.save(app, page(video, listOf(original)), 0L)
        assertFalse(OfflineCommentArchive.commitTranslation(app, account, video, original, "zh-hans", "translated") { false })
        assertEquals(original.source, OfflineCommentArchive.window(app, account, video)!!.snapshot.rows.single().source)
        assertFalse(OfflineCommentArchive.translationsReady(app, account, video, "zh-hans"))
    }
    @Test fun genuineZeroCommentResponseDiffersFromMissingArchiveAndLanguageSwitchNeedsTranslation() {
        OfflineCommentArchive.save(app, page("empty", emptyList(), 0L), 0L)
        assertTrue(OfflineCommentArchive.coverage(app, account, "empty"))
        assertTrue(OfflineCommentArchive.translationsReady(app, account, "empty", "zh-hans"))
        OfflineCommentArchive.save(app, page("language", listOf(comment("one", "已翻译"))), 0L)
        assertTrue(OfflineCommentArchive.translationsReady(app, account, "language", "zh-hans"))
        assertNotNull(OfflineCommentArchive.nextTranslation(app, account, "language", "en"))
    }
}
