package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
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
    private var rowMenu: android.app.AlertDialog? = null
    fun dismissMenus() { rowMenu?.dismiss(); rowMenu = null }
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
        val wasShown = pages.count.coerceAtLeast(40)
        pages = OfflineCommentPages(CommentPageMerge.ordered(snapshot?.rows.orEmpty()).filter {
            it.parentId.isBlank() || root(it)?.let(expanded::contains) != false
        })
        while (pages.hasNext && pages.count < wasShown) pages.append()
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
    fun submit(value: OfflineCommentData.Snapshot?, retain: Boolean = false) {
        val same = retain && value != null && snapshot?.account == value.account && snapshot?.video == value.video
        dismissMenus(); generation++; snapshot = value
        if (!same) { modes.clear(); online.clear(); expanded.clear(); pages = OfflineCommentPages(emptyList()) }
        if (value == null) pages = OfflineCommentPages(emptyList())
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
        val reply = row.parentId.isNotBlank()
        cell.replyStyle(reply)
        cell.root.setPaddingRelative(((12 + if (reply) 38 + (row.depth - 1).coerceAtLeast(0) * 10 else 0) * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        cell.avatar.layoutParams = cell.avatar.layoutParams.apply {
            width = ((if (row.depth > 0) 24 else 32) * dp).toInt(); height = width
        }
        cell.avatar.text = row.author.ifBlank { row.handle }.let { value ->
            if (value.isBlank()) "?" else String(Character.toChars(value.codePointAt(0))).uppercase(java.util.Locale.ROOT)
        }
        val name = row.author.ifBlank { context.getString(R.string.offline_comments_unknown_author) }
        cell.author.text = name + if (row.handle.isBlank()) "" else "  @" + row.handle
        cell.relation.visibility = if (reply) View.VISIBLE else View.GONE
        val parentRow = byId[row.parentId]
        val parentName = parentRow?.handle?.takeIf { it.isNotBlank() }?.let { "@$it" }
            ?: parentRow?.author?.takeIf { it.isNotBlank() } ?: context.getString(R.string.offline_comments_unknown_author)
        cell.relation.text = context.getString(R.string.toki_reply_to, parentName)
        fun selectedMode() = modes[row.id] ?: defaultMode
        fun render() {
            val mode = selectedMode()
            val content = row.text(mode)
            val pair = mode == OfflineCommentData.Mode.BILINGUAL && !row.translation.isNullOrBlank() && row.source != row.translation
            val shown = if (pair) row.translation + "\n" + row.source
                else content.ifBlank { context.getString(R.string.offline_comments_no_text) }
            val styled = android.text.SpannableString(shown)
            if (pair) {
                val start = (checkNotNull(row.translation).length + 1).coerceAtMost(shown.length)
                val end = shown.length
                styled.setSpan(android.text.style.ForegroundColorSpan(context.getColor(R.color.toki_ui_secondary)),
                    start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                styled.setSpan(android.text.style.RelativeSizeSpan(.9f), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            cell.body.text = styled
            cell.body.visibility = if (content.isBlank() && row.media.isNotEmpty()) View.GONE else View.VISIBLE
            val live = online[row.id]?.takeIf { it.source == row.source }
            cell.status.visibility = if (row.translation != null && live == null) View.GONE else View.VISIBLE
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
            cell.online.visibility = if (translate != null && row.source.isNotBlank() && row.translation.isNullOrBlank()) View.VISIBLE else View.GONE
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
                else if (localReplies > 0) R.string.toki_view_cached_replies else R.string.offline_comment_replies_missing, localReplies)
            cell.loadReplies.isEnabled = localReplies > 0
            cell.loadReplies.setOnClickListener {
                if (token == generation && valid()) {
                    if (!expanded.add(row.id)) expanded.remove(row.id)
                    rebuild(); notifyDataSetChanged(); changed()
                    cell.root.announceForAccessibility(context.getString(if (row.id in expanded) R.string.toki_action_expanded else R.string.toki_action_collapsed))
                }
            }
            val replyCursor = snapshot?.replyCursors?.get(row.id)
            val remote = try { TranslationNetworkPolicy.capture().permitsNetwork() } catch (_: Exception) { false }
            cell.onlineReplies.visibility = if (row.parentId.isBlank() && (row.replyCount > localReplies || replyCursor?.hasMore == true) && loadReplies != null && remote) View.VISIBLE else View.GONE
            cell.onlineReplies.setText(if (replyCursor?.hasMore == true) R.string.comment_reply_online_next else R.string.comment_reply_online_first)
            cell.onlineReplies.setOnClickListener {
                val value = snapshot
                if (token == generation && valid() && value != null && row.parentId.isBlank() &&
                    cell.onlineReplies.visibility == View.VISIBLE && TranslationNetworkPolicy.capture().permitsNetwork()) {
                    loadReplies?.invoke(value, row)
                }
            }
            cell.replies.visibility = if (row.parentId.isBlank() && row.replyCount > localReplies) View.VISIBLE else View.GONE
            cell.mediaScroll.visibility = if (row.media.isNotEmpty()) View.VISIBLE else View.GONE
            cell.replies.text = if (row.replyCount > localReplies) context.getString(R.string.toki_reply_coverage, localReplies, row.replyCount) else ""
            for ((button, value, label) in cell.buttons) {
                button.text = (if (value == mode) "• " else "") + context.getString(label)
                button.isEnabled = true
                button.setOnClickListener {
                    if (token == generation && valid() && snapshot?.rows?.any { it.id == row.id && it.source == row.source } == true) {
                        modes[row.id] = value; render()
                        if (value != OfflineCommentData.Mode.ORIGINAL && row.translation.isNullOrBlank()) cell.online.performClick()
                    }
                }
            }
        }
        cell.more.setOnClickListener {
            if (token != generation || !valid()) return@setOnClickListener
            val choices = cell.buttons
            val labels = choices.map { (_, value, label) -> (if (value == selectedMode()) "• " else "") + context.getString(label) }
            dismissMenus()
            val withTranslation = if (cell.online.isEnabled) labels + cell.online.text.toString() else labels
            rowMenu = TokiDialogs.builder(context).setTitle(R.string.toki_comment_actions).setItems(withTranslation.toTypedArray()) { _, which ->
                if (token == generation && valid() && snapshot?.rows?.any { it.id == row.id && it.source == row.source } == true) {
                    if (which < choices.size) {
                        val mode = choices[which].second
                        modes[row.id] = mode; render()
                        if (mode != OfflineCommentData.Mode.ORIGINAL && row.translation.isNullOrBlank()) cell.online.performClick()
                    }
                    else cell.online.performClick()
                }
            }.show()
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

    private class Cell(private val context: Context) {
        private val density = context.resources.displayMetrics.density
        private fun dp(value: Int) = (value * density).toInt()
        private val ink = context.getColor(R.color.toki_ui_ink)
        private val secondary = context.getColor(R.color.toki_ui_secondary)
        val root = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; tag = this@Cell }
        val avatar = TextView(context).apply {
            textSize = 12f; gravity = Gravity.CENTER; setTextColor(secondary)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(context.getColor(R.color.toki_ui_canvas)) }
        }
        val author = TextView(context).apply {
            textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(secondary); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val more = Button(context).apply {
            text = "⋯"; textSize = 18f; tag = "comment-actions"; contentDescription = context.getString(R.string.toki_comment_actions)
            minWidth = 0; minimumWidth = 0; minHeight = dp(40); minimumHeight = dp(40)
            setPadding(0, 0, 0, 0); background = null; setTextColor(secondary)
        }
        private val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(author, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(more, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val relation = TextView(context).apply {
            tag = "comment-reply-relation"; textSize = 11f; setTextColor(context.getColor(R.color.toki_ui_accent))
            setPadding(0, 0, 0, dp(5)); maxLines = 2
        }
        fun replyStyle(reply: Boolean) {
            root.background = if (!reply) null else object : android.graphics.drawable.Drawable() {
                private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = secondary; alpha = 80; strokeWidth = dp(2).toFloat() }
                override fun draw(canvas: android.graphics.Canvas) {
                    val x = if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right - dp(28) else dp(28)
                    canvas.drawLine(x.toFloat(), 0f, x.toFloat(), bounds.bottom.toFloat(), paint)
                }
                override fun setAlpha(alpha: Int) { paint.alpha = alpha }
                override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter }
                @Deprecated("Deprecated in Android") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
            avatar.textSize = if (reply) 10f else 12f
        }
        val body = TextView(context).apply {
            tag = "comment-body"; textSize = 14f; setTextColor(ink); setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1.05f)
        }
        val status = TextView(context).apply {
            tag = "comment-status"; textSize = 10f; setTextColor(secondary); setPadding(0, dp(4), 0, 0)
        }
        val media = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val mediaScroll = HorizontalScrollView(context).apply {
            addView(media); isHorizontalScrollBarEnabled = false; setPadding(0, dp(6), 0, 0)
        }
        val replies = TextView(context).apply { textSize = 10f; setTextColor(secondary); setPadding(0, dp(4), 0, 0) }
        private fun textAction(tagName: String) = Button(context).apply {
            tag = tagName; textSize = 12f; isAllCaps = false; setTextColor(secondary); background = null
            gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 0)
            minHeight = dp(40); minimumHeight = dp(40); minWidth = 0; minimumWidth = 0; includeFontPadding = false
        }
        val online = textAction("comment-translate")
        val loadReplies = textAction("comment-local-replies").apply { setTextColor(context.getColor(R.color.toki_ui_accent)); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        val onlineReplies = textAction("comment-online-replies")
        private val controls = LinearLayout(context).apply {
            tag = "comment-mode-controls"; orientation = LinearLayout.HORIZONTAL; visibility = View.GONE
        }
        val buttons = listOf(
            Triple(Button(context), OfflineCommentData.Mode.ORIGINAL, R.string.manual_comment_original),
            Triple(Button(context), OfflineCommentData.Mode.TRANSLATED, R.string.manual_comment_translated),
            Triple(Button(context), OfflineCommentData.Mode.BILINGUAL, R.string.manual_comment_bilingual),
        )
        init {
            val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            root.addView(avatar, LinearLayout.LayoutParams(dp(32), dp(32)).apply { topMargin = dp(5) })
            root.addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
            content.addView(header); content.addView(relation); content.addView(body); content.addView(mediaScroll); content.addView(status); content.addView(replies)
            buttons.forEach { (button, _, _) -> controls.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
            content.addView(controls); content.addView(online); content.addView(loadReplies); content.addView(onlineReplies)
        }
    }
}
