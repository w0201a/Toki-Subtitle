package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import io.github.meiyongai.toki.subtitle.R
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineCommentsSheetTest {
    @Test fun compactLightSheetKeepsDetailsOutOfTheReadingArea() = exercise("light")
    @Test @Config(qualifiers = "zh-rCN-w412dp-h915dp-night-mdpi")
    fun darkSheetRetainsRealLocalActionsWithoutTheAlertButtonBar() = exercise("dark")

    private fun exercise(name: String) {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val context = TokiDialogs.builder(owner.get()).context
        val sheet = OfflineCommentsSheet(context)
        listOf(R.string.offline_comment_mode_translated, R.string.offline_comment_mode_original,
            R.string.toki_comment_bilingual).forEach { id ->
            sheet.modes.addView(RadioButton(context).apply { this.id = View.generateViewId(); setText(id) })
        }
        TokiDialogs.segmented(sheet.modes)
        sheet.modes.check(sheet.modes.getChildAt(0).id)
        val adapter = OfflineCommentListAdapter(context, { true }) { }
        val rows = (1..12).map { i -> OfflineCommentData.Row("row$i", "Traveler $i", "traveler$i", "uid$i", "Original comment $i",
            "每一段旅程，都值得被看见。", "zh-Hans", 0, 0L) }
        adapter.submit(OfflineCommentData.Snapshot("account", "video", 1L, rows, 199L, false, false))
        sheet.list.adapter = adapter
        sheet.summary.text = context.getString(R.string.toki_cached_compact, 12, 0)
        sheet.limitSummary.text = context.getString(R.string.toki_limit_inherited, 200) + "\n" + context.getString(R.string.toki_reply_limits, 1000, 50)
        sheet.status.text = context.getString(R.string.toki_count_breakdown, 50, 20, 12, 2, 199)
        val dialog = TokiDialogs.builder(context).setView(sheet.root).create()
        sheet.close.setOnClickListener { dialog.dismiss() }
        dialog.show(); sheet.fit(dialog)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        fun layout() {
            sheet.root.measure(View.MeasureSpec.makeMeasureSpec(388, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.AT_MOST))
            sheet.root.layout(0, 0, sheet.root.measuredWidth, sheet.root.measuredHeight)
        }
        layout()
        assertEquals(View.GONE, sheet.details.visibility)
        assertEquals(View.GONE, sheet.more.visibility)
        assertTrue(sheet.root.height <= 659)
        assertTrue("Reading list retains most of the sheet", sheet.list.height > sheet.root.height / 2)
        assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.visibility != View.VISIBLE)
        val row = adapter.getView(0, null, sheet.list) as LinearLayout
        row.measure(View.MeasureSpec.makeMeasureSpec(388, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST))
        assertTrue("No empty media/replies/action rows", row.measuredHeight < 120)
        assertEquals("每一段旅程，都值得被看见。", row.findViewWithTag<TextView>("comment-body").text.toString())
        capture(sheet.root, "comments-$name")
        val top = rows.first().copy(replyCount = 2)
        val reply1 = rows[1].copy(id = "reply1", parentId = top.id, depth = 1)
        val reply2 = rows[2].copy(id = "reply2", parentId = top.id, depth = 1)
        adapter.submit(OfflineCommentData.Snapshot("account", "video", 1L, listOf(top, reply1, reply2) + rows.drop(1), 199L, false, false))
        adapter.setMode(OfflineCommentData.Mode.BILINGUAL)
        sheet.modes.check(sheet.modes.getChildAt(2).id)
        sheet.summary.text = context.getString(R.string.toki_cached_compact, 12, 2)
        adapter.getView(0, null, sheet.list).findViewWithTag<android.widget.Button>("comment-local-replies").performClick()
        assertEquals(14, adapter.count)
        layout()
        capture(sheet.root, "comments-$name-bilingual-replies")
        var starts = 0
        sheet.online.setOnClickListener {
            starts++
            sheet.online.setText(R.string.toki_action_stop)
            sheet.feedback(context.getString(R.string.toki_stream_loading), true)
        }
        sheet.detailsToggle.performClick(); layout()
        sheet.online.performClick(); layout()
        assertEquals(1, starts); assertEquals(View.VISIBLE, sheet.progress.visibility)
        assertEquals(context.getString(R.string.toki_action_stop), sheet.online.text.toString())
        capture(sheet.root, "comments-$name-action")
        sheet.detailsToggle.performClick(); layout()
        sheet.feedback("正在翻译评论与回复…", true); layout()
        assertEquals(View.VISIBLE, sheet.progress.visibility)
        assertEquals("正在翻译评论与回复…", sheet.networkStatus.text.toString())
        capture(sheet.root, "comments-$name-busy")
        sheet.feedback("已保存：24条评论、8条回复、2项媒体")
        assertEquals(View.GONE, sheet.progress.visibility)
        sheet.detailsToggle.performClick(); layout()
        assertEquals(View.VISIBLE, sheet.details.visibility)
        assertTrue(sheet.list.height > 100)
        var reloads = 0; var limits = 0; var network = 0
        sheet.reload.setOnClickListener { reloads++ }; sheet.limitButton.setOnClickListener { limits++ }
        sheet.online.setOnClickListener { network++ }
        sheet.reload.performClick(); sheet.limitButton.performClick()
        assertEquals(1, reloads); assertEquals(1, limits); assertEquals(0, network)
        capture(sheet.root, "comments-$name-details")
        val image = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(0xff52bfa0.toInt())
        val bytes = java.io.ByteArrayOutputStream().also { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val asset = OfflineCommentData.Media("c".repeat(64), OfflineCommentData.MediaKind.IMAGE, listOf("https://p16.tiktokcdn.com/fixture.png"))
        OfflineCommentMediaStore.store(context, "a".repeat(64), "fixture-video", asset, bytes, { true })
        adapter.submit(OfflineCommentData.Snapshot("a".repeat(64), "fixture-video", 1,
            listOf(top.copy(media = listOf(asset)), reply1, reply2), 3, false, false))
        adapter.setMode(OfflineCommentData.Mode.BILINGUAL)
        adapter.getView(0, null, sheet.list).findViewWithTag<android.widget.Button>("comment-local-replies").performClick()
        sheet.details.visibility = View.GONE; layout()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        sheet.feedback(context.getString(R.string.toki_action_local_loaded, 3))
        capture(sheet.root, "comments-$name-media-replies")
        sheet.close.performClick(); assertFalse(dialog.isShowing)
        owner.pause().stop().destroy()
    }
    private fun capture(view: View, name: String) {
        val directory = File("build/reports/comment-count-media").apply { mkdirs() }
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        image.eraseColor(view.context.getColor(R.color.toki_ui_surface)); view.draw(Canvas(image))
        File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
