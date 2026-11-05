package io.github.meiyongai.toki.subtitle.hook

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import io.github.meiyongai.toki.subtitle.R
import io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences

/** One canonical row count; page entry is a 20-row request-page shortcut. */
internal object OfflineCommentLimitDialog {
    fun show(context: Context, current: Int, inherited: Boolean = false, save: (Int?) -> Unit) {
        val density = context.resources.displayMetrics.density; val padding = (16 * density).toInt()
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, 0) }
        val units = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val count = RadioButton(context).apply { id = android.view.View.generateViewId(); setText(R.string.offline_comment_limit_rows) }
        val pages = RadioButton(context).apply { id = android.view.View.generateViewId(); setText(R.string.offline_comment_limit_pages) }
        units.addView(count); units.addView(pages); TokiDialogs.segmented(units); units.check(count.id)
        val input = EditText(context).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText(current.toString()) }
        body.addView(TextView(context).apply { setText(R.string.offline_comment_limit_hint) }); body.addView(units); body.addView(input)
        var pageMode = false
        units.setOnCheckedChangeListener { _, checked ->
            val value = input.text.toString().toIntOrNull()
            val nextPage = checked == pages.id
            if (nextPage != pageMode && value != null) {
                input.setText(if (nextPage) ((value.coerceIn(1,2500) + 19) / 20).toString() else (value.coerceIn(1,125) * 20).toString())
            }
            pageMode = nextPage
        }
        val builder = TokiDialogs.builder(context).setTitle(R.string.offline_comment_limit_title).setView(body)
            .setPositiveButton(R.string.common_done, null).setNegativeButton(android.R.string.cancel, null)
        if (inherited) builder.setNeutralButton(R.string.offline_comment_limit_inherit) { _, _ -> save(null) }
        val dialog = builder.create(); dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toIntOrNull()
            if (value == null || pageMode && value !in 1..125 || !pageMode && value !in 1..2500) {
                input.error = context.getString(R.string.offline_comment_limit_error); return@setOnClickListener
            }
            try { save(if (pageMode) OfflineCommentPreferences.fromPages(value) else value); dialog.dismiss() }
            catch (_: Exception) { input.error = context.getString(R.string.manual_comment_save_error) }
        }
    }
}
