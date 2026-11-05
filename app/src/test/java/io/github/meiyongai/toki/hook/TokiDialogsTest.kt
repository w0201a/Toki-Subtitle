package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.app.AlertDialog
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.ui.TokiPalette
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TokiDialogsTest {
    @Test fun themedBottomDialogKeepsTheHostingActivityThemeAndOwnsItsAction() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = owner.get()
        fun accent() = TypedValue().also { activity.theme.resolveAttribute(android.R.attr.colorAccent, it, true) }.data
        val before = accent(); var invoked = 0
        val dialog = TokiDialogs.builder(activity).setTitle("Save").setMessage("Local only")
            .setPositiveButton("Save") { _, _ -> invoked++ }.show()
        assertEquals(Gravity.BOTTOM, dialog.window!!.attributes.gravity)
        assertEquals(before, accent())
        assertEquals(TokiPalette.ACCENT, dialog.context.getColor(R.color.toki_ui_accent))
        assertFalse(dialog.getButton(AlertDialog.BUTTON_POSITIVE).isAllCaps)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, invoked)
        dialog.dismiss(); owner.pause().stop().destroy()
    }
    @Test @Config(qualifiers = "night") fun darkDialogAndComposeShareColorsAndReadableButtonInk() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val dialog = TokiDialogs.builder(owner.get()).setPositiveButton("Done", null).show()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(TokiPalette.SURFACE_DARK, dialog.context.getColor(R.color.toki_ui_surface))
        assertEquals(TokiPalette.ACCENT_DARK, dialog.context.getColor(R.color.toki_ui_accent))
        assertEquals(dialog.context.getColor(R.color.toki_ui_on_accent), dialog.getButton(AlertDialog.BUTTON_POSITIVE).currentTextColor)
        dialog.dismiss(); owner.pause().stop().destroy()
    }
    @Test fun compactSegmentsKeepExclusiveSelectionAndClickCallbacks() {
        val owner = Robolectric.buildActivity(Activity::class.java).setup()
        val group = RadioGroup(owner.get())
        val buttons = listOf("Translation", "Original", "Both").map { label -> RadioButton(owner.get()).apply {
            id = View.generateViewId(); text = label; group.addView(this)
        } }
        var changes = 0; group.setOnCheckedChangeListener { _, _ -> changes++ }
        TokiDialogs.segmented(group)
        group.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.AT_MOST))
        group.layout(0,0,group.measuredWidth,group.measuredHeight)
        buttons[1].performClick(); buttons[2].performClick()
        assertEquals(2, changes); assertEquals(buttons[2].id, group.checkedRadioButtonId)
        assertFalse(buttons[1].isChecked); assertTrue(buttons[2].isChecked)
        assertTrue(buttons.all { it.buttonDrawable == null && it.measuredWidth > 0 && it.measuredHeight >= 40 })
        owner.pause().stop().destroy()
    }
}
