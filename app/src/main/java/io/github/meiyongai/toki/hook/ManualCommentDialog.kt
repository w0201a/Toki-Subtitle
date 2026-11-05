package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import io.github.meiyongai.toki.subtitle.R

/** A read-only, per-request viewer; never changes native comment models or writes offline cache. */
internal object ManualCommentDialog {
    fun show(activity: Activity, source: String, transport: (String, (String?) -> Unit) -> AutoCloseable,
        nativeMenu: () -> Unit, dismissed: (AlertDialog) -> Unit): AlertDialog {
        val context = HostSettingsEntry.localizedContext(activity)
        val main = Handler(Looper.getMainLooper())
        val density = context.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding / 2, padding, 0) }
        val hint = TextView(context).apply { text = context.getString(R.string.manual_comment_privacy_hint); textSize = 12f }
        val status = TextView(context).apply { textSize = 13f; setPadding(0, padding / 2, 0, padding / 2) }
        val body = TextView(context).apply { text = source; textSize = 16f; setTextIsSelectable(true) }
        val modes = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        val modeIds = linkedMapOf<Int, ManualCommentTranslation.Mode>()
        for ((mode, title) in listOf(ManualCommentTranslation.Mode.ORIGINAL to R.string.manual_comment_original,
            ManualCommentTranslation.Mode.TRANSLATED to R.string.manual_comment_translated,
            ManualCommentTranslation.Mode.BILINGUAL to R.string.manual_comment_bilingual)) {
            val button = RadioButton(context).apply { id = View.generateViewId(); text = context.getString(title) }
            modeIds[button.id] = mode
            modes.addView(button)
        }
        TokiDialogs.segmented(modes)
        var selected = ManualCommentTranslation.Mode.TRANSLATED
        modes.check(modeIds.entries.single { it.value == selected }.key)
        val scroll = ScrollView(context).apply { addView(body) }
        container.addView(hint)
        container.addView(modes)
        container.addView(status)
        val height = minOf((300 * density).toInt(), (context.resources.displayMetrics.heightPixels * .38f).toInt())
        container.addView(scroll, LinearLayout.LayoutParams(-1, height.coerceAtLeast((80 * density).toInt())))
        var latest = ManualCommentTranslation.State(source, null, 0, 0, 0, true)
        var shown: AlertDialog? = null
        lateinit var dialog: AlertDialog
        fun render(state: ManualCommentTranslation.State) {
            latest = state
            val displayed = state.text(selected)
            if (body.text.toString() != displayed) body.text = displayed
            status.text = when {
                state.busy -> context.getString(R.string.manual_comment_progress, state.completed, state.total)
                state.failed > 0 -> context.getString(R.string.manual_comment_failed_segments, state.failed)
                else -> context.getString(R.string.manual_comment_complete)
            }
            shown?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !state.busy && state.failed > 0
        }
        val session = ManualCommentTranslation(transport,
            post = { runnable, delay -> main.postDelayed(runnable, delay); Unit },
            remove = { main.removeCallbacks(it) },
            changed = { if (shown?.isShowing == true) render(it) },
            report = { HookRuntime.event("ManualCommentTranslateHook", it) })
        modes.setOnCheckedChangeListener { _, id ->
            modeIds[id]?.let { selected = it; render(latest) }
        }
        dialog = TokiDialogs.builder(context)
            .setTitle(context.getString(R.string.manual_comment_dialog_title))
            .setView(container)
            .setPositiveButton(context.getString(R.string.manual_comment_retry), null)
            .setNegativeButton(context.getString(R.string.common_close), null)
            .setNeutralButton(context.getString(R.string.manual_comment_native_actions)) { _, _ -> dialog.dismiss(); nativeMenu() }
            .create()
        shown = dialog
        dialog.setOnDismissListener { session.close(); shown = null; dismissed(dialog) }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { session.retryFailed() }
        session.start(source)
        return dialog
    }
}
