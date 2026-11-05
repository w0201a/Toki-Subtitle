package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineCommentListAdapterTest {
    private fun row(id: String, translation: String?) = OfflineCommentData.Row(id, "Author", "user", "uid",
        "source " + id, translation, "zh-Hans", 0, 0)
    @Test fun recycledRowsUseTheNewIdentityAndTranslationChoices() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val parent = ListView(activity)
        var valid = true
        val adapter = OfflineCommentListAdapter(activity, { valid }) { }
        adapter.submit(OfflineCommentData.Snapshot("account", "video", 0,
            listOf(row("first", "第一条译文"), row("second", null)), 2, false, false))
        val first = adapter.getView(0, null, parent) as LinearLayout
        val body = first.getChildAt(1) as TextView
        fun modeControls(root: LinearLayout): LinearLayout = (0 until root.childCount).map { root.getChildAt(it) }
            .filterIsInstance<LinearLayout>().single { group -> group.childCount == 3 && (0..2).all { group.getChildAt(it) is Button } }
        val controls = modeControls(first)
        (controls.getChildAt(1) as Button).performClick()
        assertEquals("第一条译文", body.text.toString())
        val recycled = adapter.getView(1, first, parent) as LinearLayout
        assertSame(first, recycled)
        assertEquals("source second", (recycled.getChildAt(1) as TextView).text.toString())
        val secondControls = modeControls(recycled)
        assertFalse((secondControls.getChildAt(1) as Button).isEnabled)
        assertFalse((secondControls.getChildAt(2) as Button).isEnabled)
        adapter.getView(0, recycled, parent)
        assertEquals("第一条译文", body.text.toString())
        valid = false
        (controls.getChildAt(0) as Button).performClick()
        assertEquals("第一条译文", body.text.toString())
        adapter.submit(null)
        assertEquals(0, adapter.count)
        controller.pause().stop().destroy()
    }
    @Test fun globalModesAndCachedRepliesWorkWithoutRequestCallbacks() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val parent = ListView(activity)
        val top = row("top", "根评论译文").copy(replyCount = 1L)
        val reply = row("reply", "回复译文").copy(depth = 1, parentId = "top")
        val adapter = OfflineCommentListAdapter(activity, { true }) { }
        adapter.submit(OfflineCommentData.Snapshot("account", "video", 1L, listOf(top, reply), 2L, false, false))
        fun body(position: Int) = ((adapter.getView(position, null, parent) as LinearLayout).getChildAt(1) as TextView).text.toString()
        assertEquals(1, adapter.count)
        assertEquals("根评论译文", body(0))
        adapter.setMode(OfflineCommentData.Mode.ORIGINAL)
        assertEquals("source top", body(0))
        adapter.setMode(OfflineCommentData.Mode.BILINGUAL)
        assertEquals("source top\n\n根评论译文", body(0))
        val cell = adapter.getView(0, null, parent) as LinearLayout
        val expand = (0 until cell.childCount).map { cell.getChildAt(it) }.filterIsInstance<Button>()
            .single { it.visibility == android.view.View.VISIBLE && it.isEnabled && it.text.toString().contains("1") }
        expand.performClick()
        assertEquals(2, adapter.count)
        assertEquals("source reply\n\n回复译文", body(1))
        adapter.update(reply, "zh-Hans", ManualCommentTranslation.State(reply.source, "新回复译文", 1, 1, 0, false))
        assertEquals("source reply\n\n新回复译文", body(1))
        controller.pause().stop().destroy()
    }
    @Test fun compactMoreMenuKeepsRowModeChangesAvailableWithoutThreeVisibleButtonsPerComment() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get(); val parent = ListView(activity)
        val adapter = OfflineCommentListAdapter(activity, { true }) { }
        adapter.submit(OfflineCommentData.Snapshot("account", "video", 1L, listOf(row("one", "译文")), 1L, false, false))
        val cell = adapter.getView(0, null, parent) as LinearLayout
        val actions = cell.findViewWithTag<Button>("comment-actions")
        assertEquals(android.view.View.VISIBLE, actions.visibility)
        assertEquals("译文", (cell.getChildAt(1) as TextView).text.toString())
        actions.performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(3, dialog.listView.adapter.count)
        dialog.listView.performItemClick(dialog.listView.adapter.getView(0,null,dialog.listView), 0, 0L)
        assertEquals("source one", (cell.getChildAt(1) as TextView).text.toString())
        dialog.dismiss(); owner.pause().stop().destroy()
    }
}
