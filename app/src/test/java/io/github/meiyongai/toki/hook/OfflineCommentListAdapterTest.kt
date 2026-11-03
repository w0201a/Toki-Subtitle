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
        val controls = first.getChildAt(4) as LinearLayout
        (controls.getChildAt(1) as Button).performClick()
        assertEquals("第一条译文", body.text.toString())
        val recycled = adapter.getView(1, first, parent) as LinearLayout
        assertSame(first, recycled)
        assertEquals("source second", (recycled.getChildAt(1) as TextView).text.toString())
        val secondControls = recycled.getChildAt(4) as LinearLayout
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
}
