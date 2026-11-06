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
        val body = first.findViewWithTag<TextView>("comment-body")
        fun modeControls(root: LinearLayout): LinearLayout = root.findViewWithTag("comment-mode-controls")
        val controls = modeControls(first)
        (controls.getChildAt(1) as Button).performClick()
        assertEquals("第一条译文", body.text.toString())
        val recycled = adapter.getView(1, first, parent) as LinearLayout
        assertSame(first, recycled)
        assertEquals("source second", recycled.findViewWithTag<TextView>("comment-body").text.toString())
        val secondControls = modeControls(recycled)
        assertTrue((secondControls.getChildAt(1) as Button).isEnabled)
        assertTrue((secondControls.getChildAt(2) as Button).isEnabled)
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
        fun body(position: Int) = adapter.getView(position, null, parent).findViewWithTag<TextView>("comment-body").text.toString()
        assertEquals(1, adapter.count)
        assertEquals("根评论译文", body(0))
        adapter.setMode(OfflineCommentData.Mode.ORIGINAL)
        assertEquals("source top", body(0))
        adapter.setMode(OfflineCommentData.Mode.BILINGUAL)
        assertEquals("根评论译文\nsource top", body(0))
        val cell = adapter.getView(0, null, parent) as LinearLayout
        val expand = cell.findViewWithTag<Button>("comment-local-replies")
        expand.performClick()
        assertEquals(2, adapter.count)
        assertEquals("回复译文\nsource reply", body(1))
        adapter.update(reply, "zh-Hans", ManualCommentTranslation.State(reply.source, "新回复译文", 1, 1, 0, false))
        assertEquals("新回复译文\nsource reply", body(1))
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
        assertEquals("译文", cell.findViewWithTag<TextView>("comment-body").text.toString())
        actions.performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(3, dialog.listView.adapter.count)
        dialog.listView.performItemClick(dialog.listView.adapter.getView(0,null,dialog.listView), 0, 0L)
        assertEquals("source one", cell.findViewWithTag<TextView>("comment-body").text.toString())
        dialog.dismiss(); owner.pause().stop().destroy()
    }
    @Test fun repliesAreExplicitlyLabeledAndRemainExpandedWhenOnlineRowsAreAppended() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val context = TokiDialogs.builder(owner.get()).context
        val list = ListView(context)
        val adapter = OfflineCommentListAdapter(context, { true }) { }
        val top = row("top", "主评论").copy(handle = "parent", replyCount = 1)
        val child = row("child", "回复内容").copy(parentId = "top", depth = 1, handle = "child")
        val snapshot = OfflineCommentData.Snapshot("account", "video", 1, listOf(top, child), 2, false, false)
        adapter.submit(snapshot)
        val parent = adapter.getView(0, null, list) as LinearLayout
        val control = parent.findViewWithTag<Button>("comment-local-replies")
        assertTrue(control.text.toString().contains("1"))
        control.performClick()
        assertEquals(2, adapter.count)
        val reply = adapter.getView(1, null, list) as LinearLayout
        val relation = reply.findViewWithTag<TextView>("comment-reply-relation")
        assertEquals(android.view.View.VISIBLE, relation.visibility)
        assertTrue(relation.text.toString().contains("@parent"))
        assertTrue(reply.paddingStart > parent.paddingStart)
        assertNotNull(reply.background)
        adapter.submit(snapshot.copy(rows = snapshot.rows + row("new", "新增")), retain = true)
        assertEquals(3, adapter.count)
        val recycled = adapter.getView(0, reply, list) as LinearLayout
        assertEquals(android.view.View.GONE, recycled.findViewWithTag<TextView>("comment-reply-relation").visibility)
        assertNull(recycled.background)
        owner.pause().stop().destroy()
    }
    @Test fun unTranslatedRowKeepsAllModesAndAVisibleTranslationAction() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get(); val list = ListView(activity)
        var requests = 0
        val adapter = OfflineCommentListAdapter(activity, { true }, { _, _ -> requests++ }) { }
        adapter.submit(OfflineCommentData.Snapshot("account", "video", 1, listOf(row("new", null)), 1, false, false))
        val root = adapter.getView(0, null, list)
        assertEquals(android.view.View.VISIBLE, root.findViewWithTag<Button>("comment-translate").visibility)
        root.findViewWithTag<Button>("comment-actions").performClick()
        val dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        assertEquals(4, dialog.listView.adapter.count)
        dialog.listView.performItemClick(dialog.listView.adapter.getView(2, null, dialog.listView), 2, 2)
        assertEquals(1, requests)
        assertEquals("source new", root.findViewWithTag<TextView>("comment-body").text.toString())
        owner.pause().stop().destroy()
    }
    @Test fun continuousLocalScrollPassesTwentyFiveHundredWithoutReplacingEarlierRows() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val adapter = OfflineCommentListAdapter(owner.get(), { true }) { }
        val rows = (0 until 2500).map { row("c$it", "译文$it") }
        adapter.submit(OfflineCommentData.Snapshot("account", "video", 1, rows, 2500, false, false))
        while (adapter.hasNext) adapter.append()
        assertEquals(2500, adapter.count)
        assertEquals("c0", adapter.getItem(0).id); assertEquals("c2499", adapter.getItem(2499).id)
        owner.pause().stop().destroy()
    }

}
