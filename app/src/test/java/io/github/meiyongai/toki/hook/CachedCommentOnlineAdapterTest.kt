package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import io.github.meiyongai.toki.subtitle.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CachedCommentOnlineAdapterTest {
    @Test fun resultsAndCommitNoticesBelongToTheExactRecycledRowSourceTargetAndOutput() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val parent = ListView(activity)
        val first = OfflineCommentData.Row("first", "Author", "user", "u", "original", null, "", 0, 0)
        val second = first.copy(id = "second", source = "second source")
        val requested = mutableListOf<String>()
        val adapter = OfflineCommentListAdapter(activity, { true }, { _, row -> requested += row.id }) { }
        adapter.submit(OfflineCommentData.Snapshot("account", "video", 0, listOf(first, second), 2, false, false))
        val cell = adapter.getView(0, null, parent) as LinearLayout
        (cell.getChildAt(5) as Button).performClick()
        assertEquals(listOf("first"), requested)
        val state = ManualCommentTranslation.State("original", "译文", 1, 1, 0, false)
        adapter.update(first, "zh-hans", state)
        adapter.getView(0, cell, parent)
        val controls = cell.getChildAt(4) as LinearLayout
        (controls.getChildAt(1) as Button).performClick()
        assertEquals("译文", (cell.getChildAt(1) as TextView).text.toString())
        adapter.saved(first, "fr", "译文", CommentTranslationStore.Saved.COMMITTED)
        adapter.saved(first, "zh-hans", "另一个旧结果", CommentTranslationStore.Saved.COMMITTED)
        adapter.getView(0, cell, parent)
        assertNotEquals(activity.getString(R.string.cached_comment_translation_saved, "zh-hans"),
            (cell.getChildAt(2) as TextView).text.toString())
        adapter.saved(first, "zh-hans", "译文", CommentTranslationStore.Saved.COMMITTED)
        adapter.getView(0, cell, parent)
        assertEquals(activity.getString(R.string.cached_comment_translation_saved, "zh-hans"),
            (cell.getChildAt(2) as TextView).text.toString())
        adapter.getView(1, cell, parent)
        (cell.getChildAt(5) as Button).performClick()
        assertEquals(listOf("first", "second"), requested)
        assertEquals("second source", (cell.getChildAt(1) as TextView).text.toString())
        adapter.submit(null)
        assertEquals(0, adapter.count)
        controller.pause().stop().destroy()
    }
}
