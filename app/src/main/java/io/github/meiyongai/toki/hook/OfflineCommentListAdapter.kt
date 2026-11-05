package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.HorizontalScrollView
import android.widget.Toast
import io.github.meiyongai.toki.subtitle.R

/** Local rows only. Recycling replaces all row callbacks; translation modes never rewrite native data. */
internal class OfflineCommentListAdapter(private val context: Context, private val valid: () -> Boolean,
    private val translate: ((OfflineCommentData.Snapshot, OfflineCommentData.Row) -> Unit)? = null,
    private val changed: () -> Unit) : BaseAdapter() {
    var loadReplies: ((OfflineCommentData.Snapshot, OfflineCommentData.Row) -> Unit)? = null
    var snapshot: OfflineCommentData.Snapshot? = null
        private set
    private var pages = OfflineCommentPages(emptyList())
    private val modes = mutableMapOf<String, OfflineCommentData.Mode>()
    private var generation = 0L
    private var defaultMode = OfflineCommentData.Mode.TRANSLATED
    private val expanded = mutableSetOf<String>()
    private var byId = emptyMap<String, OfflineCommentData.Row>()
    private var replyCounts = emptyMap<String, Int>()
    var mediaLoader: ClassLoader = context.classLoader
    fun setMode(mode: OfflineCommentData.Mode) { defaultMode = mode; modes.clear(); notifyDataSetChanged() }
    fun mode(): OfflineCommentData.Mode = defaultMode
    private fun root(row: OfflineCommentData.Row): String? {
        val values = byId
        var value = row; var depth = 0
        while (value.parentId.isNotBlank() && depth++ < 4) value = values[value.parentId] ?: return null
        return value.id
    }
    private fun rebuild() {
        pages = OfflineCommentPages(snapshot?.rows.orEmpty().filter { it.parentId.isBlank() || root(it)?.let(expanded::contains) != false })
        pages.append()
    }

    private data class Online(val source: String, val target: String, val state: ManualCommentTranslation.State,
        var saved: CommentTranslationStore.Saved? = null)
    private val online = mutableMapOf<String, Online>()
    fun update(row: OfflineCommentData.Row, target: String, state: ManualCommentTranslation.State) {
        if (snapshot?.rows?.none { it.id == row.id && it.source == row.source } != false) return
        online[row.id] = Online(row.source, target, state)
        notifyDataSetChanged()
    }
    fun saved(row: OfflineCommentData.Row, target: String, translated: String, result: CommentTranslationStore.Saved) {
        online[row.id]?.takeIf { it.source == row.source && it.target == target && it.state.translated == translated }?.let { it.saved = result; notifyDataSetChanged() }
    }
    fun cancelPending() {
        online.values.filter { it.state.busy }.forEach { value ->
            online.entries.firstOrNull { it.value === value }?.let { (id, old) ->
                online[id] = old.copy(state = old.state.copy(busy = false, failed = maxOf(1, old.state.failed)))
            }
        }
        notifyDataSetChanged()
    }
    val hasNext: Boolean get() = pages.hasNext
    fun submit(value: OfflineCommentData.Snapshot?) {
        generation++; snapshot = value; modes.clear(); online.clear(); expanded.clear()
        byId = value?.rows.orEmpty().associateBy { it.id }
        replyCounts = value?.rows.orEmpty().filter { it.parentId.isNotBlank() }.mapNotNull { root(it) }.groupingBy { it }.eachCount()
        rebuild(); notifyDataSetChanged(); changed()
    }
    fun append() { if (valid() && pages.hasNext) { pages.append(); notifyDataSetChanged(); changed() } }
    override fun getCount(): Int = pages.count
    override fun getItem(position: Int): OfflineCommentData.Row {
        val row = pages[position]
        val value = online[row.id]?.takeIf { it.source == row.source }
        return if (value?.state?.translated != null) row.copy(translation = value.state.translated, language = value.target) else row
    }
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val cell = (convertView?.tag as? Cell) ?: Cell(context)
        val row = getItem(position)
        val token = generation
        val dp = context.resources.displayMetrics.density
        cell.root.setPadding(((12 + row.depth * 14) * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        val name = row.author.ifBlank { context.getString(R.string.offline_comments_unknown_author) }
        cell.author.text = name + (if (row.handle.isBlank()) "" else "  @" + row.handle) +
            if (row.parentId.isBlank()) "" else "  " + context.getString(R.string.comment_page_reply_to, byId[row.parentId]?.author?.takeIf { it.isNotBlank() } ?: context.getString(R.string.offline_comments_unknown_author))
        fun selectedMode() = modes[row.id] ?: defaultMode
        fun render() {
            val mode = selectedMode()
            val content = row.text(mode)
            val shown = content.ifBlank { context.getString(R.string.offline_comments_no_text) }
            if (cell.body.text.toString() != shown) cell.body.text = shown
            cell.body.visibility = if (content.isBlank() && row.media.isNotEmpty()) View.GONE else View.VISIBLE
            val live = online[row.id]?.takeIf { it.source == row.source }
            cell.status.text = when {
                live?.state?.busy == true -> context.getString(R.string.manual_comment_progress, live.state.completed, live.state.total)
                live != null && live.state.failed > 0 -> context.getString(R.string.cached_comment_partial_translation, live.state.failed)
                live?.saved == CommentTranslationStore.Saved.COMMITTED -> context.getString(R.string.cached_comment_translation_saved, live.target)
                live?.saved == CommentTranslationStore.Saved.FULL -> context.getString(R.string.cached_comment_translation_full)
                live?.saved == CommentTranslationStore.Saved.UNAVAILABLE -> context.getString(R.string.cached_comment_translation_save_failed)
                live != null && !live.state.busy && live.state.failed == 0 && live.state.translated == null ->
                    context.getString(R.string.cached_comment_translation_unchanged)
                row.sourceTruncated -> context.getString(R.string.offline_comments_text_truncated)
                row.translation == null -> context.getString(R.string.offline_comments_translation_missing)
                row.language.isBlank() -> context.getString(R.string.offline_comments_translation_cached)
                else -> context.getString(R.string.offline_comments_translation_language, row.language)
            }
            cell.online.text = context.getString(if (live != null && live.state.failed > 0)
                R.string.cached_comment_retry_action else R.string.cached_comment_translate_action)
            cell.online.isEnabled = translate != null && row.source.isNotBlank() && !row.sourceTruncated && live?.state?.busy != true
            cell.online.setOnClickListener {
                val snapshot = this.snapshot
                if (token == generation && valid() && snapshot != null) {
                    val original = snapshot.rows.firstOrNull { it.id == row.id && it.source == row.source }
                    if (original != null) translate?.invoke(snapshot, original)
                }
            }
            val localReplies = replyCounts[row.id] ?: 0
            cell.loadReplies.visibility = if (row.parentId.isBlank() && row.depth == 0 && (row.replyCount > 0 || localReplies > 0)) View.VISIBLE else View.GONE
            cell.loadReplies.text = context.getString(if (row.id in expanded) R.string.offline_comment_replies_collapse
                else if (localReplies > 0) R.string.offline_comment_replies_expand else R.string.offline_comment_replies_missing, localReplies)
            cell.loadReplies.isEnabled = localReplies > 0
            cell.loadReplies.setOnClickListener {
                if (token == generation && valid()) {
                    if (!expanded.add(row.id)) expanded.remove(row.id)
                    rebuild(); notifyDataSetChanged(); changed()
                }
            }
            val replyCursor = snapshot?.replyCursors?.get(row.id)
            val remote = try { TranslationNetworkPolicy.capture().permitsNetwork() } catch (_: Exception) { false }
            cell.onlineReplies.visibility = if (row.parentId.isBlank() && row.replyCount > localReplies && loadReplies != null && remote) View.VISIBLE else View.GONE
            cell.onlineReplies.setText(if (replyCursor?.hasMore == true) R.string.comment_reply_online_next else R.string.comment_reply_online_first)
            cell.onlineReplies.setOnClickListener {
                val value = snapshot
                if (token == generation && valid() && value != null) loadReplies?.invoke(value, row)
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
        snapshot?.let { value -> OfflineCommentMediaViews.bind(cell.media, context, value, row, mediaLoader) {
            token == generation && valid() && snapshot?.rows?.any { it.id == row.id && it.source == row.source } == true
        } }
        render()
        return cell.root
    }

    private class Cell(context: Context) {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = this@Cell }
        val author = TextView(context).apply { textSize = 13f }
        val body = TextView(context).apply { textSize = 16f; setTextIsSelectable(true) }
        val status = TextView(context).apply { textSize = 11f }
        val media = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        private val mediaScroll = HorizontalScrollView(context).apply { addView(media); isHorizontalScrollBarEnabled = false }
        val replies = TextView(context).apply { textSize = 11f }
        val online = Button(context).apply { textSize = 12f }
        val loadReplies = Button(context).apply { textSize = 12f }
        val onlineReplies = Button(context).apply { textSize = 12f }
        private val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = listOf(
            Triple(Button(context), OfflineCommentData.Mode.ORIGINAL, R.string.manual_comment_original),
            Triple(Button(context), OfflineCommentData.Mode.TRANSLATED, R.string.manual_comment_translated),
            Triple(Button(context), OfflineCommentData.Mode.BILINGUAL, R.string.manual_comment_bilingual),
        )
        init {
            root.addView(author); root.addView(body); root.addView(mediaScroll); root.addView(status); root.addView(replies)
            buttons.forEach { (button, _, _) ->
                button.textSize = 11f; button.minWidth = 0; button.minimumWidth = 0; button.setPadding(0, 0, 0, 0)
                controls.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(controls); root.addView(online); root.addView(loadReplies); root.addView(onlineReplies)
        }
    }
}
