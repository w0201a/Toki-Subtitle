package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class CommentSnapshotStoreTest {
    private fun snapshot(account: String = "one", video: String = "video") = OfflineCommentData.Snapshot(
        HostOfflineComments.accountToken(account), video, 1000,
        listOf(OfflineCommentData.Row("comment", "author", "handle", "uid", "text", null, "", 0, 0)),
        100, true, false, 20)
    @Test fun savedPagesCanBeReadAfterReopenOnlyForTheMatchingAccountAndVideo() {
        val context = RuntimeEnvironment.getApplication()
        val value = snapshot()
        assertEquals(CommentSnapshotStore.Saved.COMMITTED, CommentSnapshotStore.save(context, value))
        assertEquals(value, CommentSnapshotStore.read(context, value.account, value.video))
        assertNull(CommentSnapshotStore.read(context, snapshot("other").account, value.video))
        assertNull(CommentSnapshotStore.read(context, value.account, "other"))
    }
    @Test fun cancellationImmediatelyBeforeCommitPreservesTheExistingSnapshot() {
        val context = RuntimeEnvironment.getApplication()
        val value = snapshot()
        assertEquals(CommentSnapshotStore.Saved.COMMITTED, CommentSnapshotStore.save(context, value))
        assertEquals(CommentSnapshotStore.Saved.CANCELLED, CommentSnapshotStore.save(context, value.copy(capturedAt = 2000)) { false })
        assertEquals(value, CommentSnapshotStore.read(context, value.account, value.video))
    }
    @Test fun fullArchiveDoesNotDeleteOldEntries() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("toki_comment_snapshots_v1", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        for (index in 1..100) edit.putString("%064x".format(index), "existing")
        assertTrue(edit.commit())
        assertEquals(CommentSnapshotStore.Saved.FULL, CommentSnapshotStore.save(context, snapshot()))
        assertEquals(100, prefs.all.size)
        assertEquals("existing", prefs.getString("%064x".format(1), null))
    }
    @Test fun corruptPrivateRecordsThrowInsteadOfLookingLikeNoComments() {
        val context = RuntimeEnvironment.getApplication()
        val value = snapshot()
        val prefs = context.getSharedPreferences("toki_comment_snapshots_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString(CommentSnapshotStore.key(value.account, value.video), "not base64!").commit())
        assertThrows(Exception::class.java) { CommentSnapshotStore.read(context, value.account, value.video) }
    }
}
