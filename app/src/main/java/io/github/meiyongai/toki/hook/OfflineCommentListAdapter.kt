package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.meiyongai.toki.subtitle.R

/** Local rows only. Recycling replaces all row callbacks; translation modes never rewrite native data. */
internal class OfflineCommentListAdapter(private val context: Context, private val valid: () -> Boolean,
    private val changed: () -> Unit) : BaseAdapter() {
    var snapshot: OfflineCommentData.Snapshot? = null
        private set
    private var pages = OfflineCommentPages(emptyList())
    private val modes = mutableMapOf<String, OfflineCommentData.Mode>()
    private var generation = 0L
    val hasNext: Boolean get() = pages.hasNext
    fun submit(value: OfflineCommentData.Snapshot?) {
        generation++; snapshot = value; modes.clear()
        pages = OfflineCommentPages(value?.rows.orEmpty())
        pages.append(); notifyDataSetChanged(); changed()
    }
    fun append() { if (valid() && pages.hasNext) { pages.append(); notifyDataSetChanged(); changed() } }
    override fun getCount(): Int = pages.count
    override fun getItem(position: Int): OfflineCommentData.Row = pages[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val cell = (convertView?.tag as? Cell) ?: Cell(context)
        val row = getItem(position)
        val token = generation
        val dp = context.resources.displayMetrics.density
        cell.root.setPadding(((12 + row.depth * 14) * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        val name = row.author.ifBlank { context.getString(R.string.offline_comments_unknown_author) }
        cell.author.text = name + if (row.handle.isBlank()) "" else "  @" + row.handle
        fun selectedMode() = modes[row.id] ?: OfflineCommentData.Mode.ORIGINAL
        fun render() {
            val mode = selectedMode()
            val content = row.text(mode)
            val shown = content.ifBlank { context.getString(R.string.offline_comments_no_text) }
            if (cell.body.text.toString() != shown) cell.body.text = shown
            cell.status.text = when {
                row.sourceTruncated -> context.getString(R.string.offline_comments_text_truncated)
                row.translation == null -> context.getString(R.string.offline_comments_translation_missing)
                row.language.isBlank() -> context.getString(R.string.offline_comments_translation_cached)
                else -> context.getString(R.string.offline_comments_translation_language, row.language)
            }
            cell.replies.text = if (row.replyCount > 0) context.getString(R.string.offline_comments_reply_count, row.replyCount) else ""
            for ((button, value, label) in cell.buttons) {
                button.text = (if (value == mode) "• " else "") + context.getString(label)
                button.isEnabled = value == OfflineCommentData.Mode.ORIGINAL || row.translation != null
                button.setOnClickListener {
                    if (token == generation && valid() && snapshot?.rows?.any { it.id == row.id && it.source == row.source } == true) {
                        modes[row.id] = value; render()
                    }
                }
            }
        }
        cell.status.setOnClickListener {
            if (token == generation && valid() && row.translation == null)
                Toast.makeText(context, context.getString(R.string.offline_comments_translation_missing), Toast.LENGTH_LONG).show()
        }
        render()
        return cell.root
    }

    private class Cell(context: Context) {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = this@Cell }
        val author = TextView(context).apply { textSize = 13f }
        val body = TextView(context).apply { textSize = 16f; setTextIsSelectable(true) }
        val status = TextView(context).apply { textSize = 11f }
        val replies = TextView(context).apply { textSize = 11f }
        private val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = listOf(
            Triple(Button(context), OfflineCommentData.Mode.ORIGINAL, R.string.manual_comment_original),
            Triple(Button(context), OfflineCommentData.Mode.TRANSLATED, R.string.manual_comment_translated),
            Triple(Button(context), OfflineCommentData.Mode.BILINGUAL, R.string.manual_comment_bilingual),
        )
        init {
            root.addView(author); root.addView(body); root.addView(status); root.addView(replies)
            buttons.forEach { (button, _, _) ->
                button.textSize = 11f; button.minWidth = 0; button.minimumWidth = 0; button.setPadding(0, 0, 0, 0)
                controls.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(controls)
        }
    }
}
