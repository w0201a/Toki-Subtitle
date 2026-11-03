package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CommentTranslationStoreTest {
    private fun row(source: String = "original", id: String = "comment") = OfflineCommentData.Row(
        id, "author", "handle", "uid", source, null, "", 0, 0)
    private fun snapshot(account: String = "1", source: String = "original", video: String = "video") =
        OfflineCommentData.Snapshot(HostOfflineComments.accountToken(account), video, 0, listOf(row(source)), 1, false, false)
    private fun save(context: Context, value: OfflineCommentData.Snapshot, target: String, text: String): CommentTranslationStore.Saved? {
        val latch = CountDownLatch(1)
        var saved: CommentTranslationStore.Saved? = null
        CommentTranslationStore.save(context, value, value.rows.single(), target, text) { saved = it; latch.countDown() }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return saved
    }
    @Test fun completeResultSurvivesReopenAndRespectsAccountVideoSourceAndTarget() {
        val context = RuntimeEnvironment.getApplication()
        val value = snapshot()
        assertEquals(CommentTranslationStore.Saved.COMMITTED, save(context, value, "zh-Hans", "已保存译文"))
        assertEquals("已保存译文", CommentTranslationStore.restore(context, snapshot(), null).rows.single().translation)
        assertEquals("已保存译文", CommentTranslationStore.restore(context, snapshot(), "ZH_hans").rows.single().translation)
        assertNull(CommentTranslationStore.restore(context, snapshot("2"), "zh-hans").rows.single().translation)
        assertNull(CommentTranslationStore.restore(context, snapshot(source = "edited source"), "zh-hans").rows.single().translation)
        assertNull(CommentTranslationStore.restore(context, snapshot(video = "other"), "zh-hans").rows.single().translation)
        assertNull(CommentTranslationStore.restore(context, snapshot(), "fr").rows.single().translation)
    }
    @Test fun fullStorageDoesNotEvictOrDeleteExistingResults() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("toki_comment_translations_v1", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        for (index in 1..2000) edit.putString("%064x".format(index), "saved")
        assertTrue(edit.commit())
        assertEquals(CommentTranslationStore.Saved.FULL, save(context, snapshot("capacity"), "fr", "nouveau"))
        assertEquals(2000, prefs.all.size)
        assertEquals("saved", prefs.getString("%064x".format(1), null))
    }
    @Test fun keysCannotReuseOtherAccountsChangedTextOrDifferentTargets() {
        val account = HostOfflineComments.accountToken("key-test")
        val first = CommentTranslationKey.key(account, "v", "c", "source", "zh-Hans")
        assertEquals(first, CommentTranslationKey.key(account, "v", "c", "source", "ZH_hans"))
        assertNotEquals(first, CommentTranslationKey.key(account, "v", "c", "changed", "zh-Hans"))
        assertNotEquals(first, CommentTranslationKey.key(account, "v", "c", "source", "fr"))
        assertNotEquals(first, CommentTranslationKey.key(account, "v2", "c", "source", "zh-Hans"))
        assertNotEquals(first, CommentTranslationKey.key(HostOfflineComments.accountToken("other"), "v", "c", "source", "zh-Hans"))
    }
}
