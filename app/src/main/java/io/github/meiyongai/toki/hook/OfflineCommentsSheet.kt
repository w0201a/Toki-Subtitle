package io.github.meiyongai.toki.subtitle.hook

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import io.github.meiyongai.toki.subtitle.R

/** A bounded comment sheet, not a framework alert title + three-button bar. Local reads stay local. */
internal class OfflineCommentsSheet(private val context: Context) {
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private val ink = context.getColor(R.color.toki_ui_ink)
    private val secondary = context.getColor(R.color.toki_ui_secondary)
    private val accent = context.getColor(R.color.toki_ui_accent)
    private fun label(size: Float) = TextView(context).apply { textSize = size; setTextColor(secondary) }
    private fun action(resource: Int) = Button(context).apply {
        setText(resource); textSize = 12f; isAllCaps = false; setTextColor(ink)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat(); setColor(context.getColor(R.color.toki_ui_canvas))
        }
        minHeight = dp(44); minimumHeight = dp(44); minWidth = 0; minimumWidth = 0
        setPadding(dp(10), dp(4), dp(10), dp(4))
    }
    val root = object : LinearLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val target = minOf((context.resources.displayMetrics.heightPixels * .72f).toInt(), dp(640))
            val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) target
                else View.MeasureSpec.getSize(heightMeasureSpec)
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(minOf(target, available), View.MeasureSpec.EXACTLY))
        }
    }.apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(4)) }
    val close = action(R.string.common_close).apply { background = null; text = "×"; textSize = 24f; contentDescription = context.getString(R.string.common_close) }
    val summary = label(12f).apply { tag = "cache-summary" }
    val modes = RadioGroup(context)
    val progress = android.widget.ProgressBar(context).apply {
        tag = "cache-action-progress"; visibility = View.GONE; isIndeterminate = true
    }
    fun feedback(message: String, busy: Boolean = false) {
        networkStatus.text = message
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        root.announceForAccessibility(message)
    }
    val status = label(12f)
    val networkStatus = label(12f).apply {
        visibility = View.GONE; setPadding(0, dp(4), 0, dp(4))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
    }
    val online = action(R.string.toki_stream_online)
    val nextPage = action(R.string.toki_stream_translate)
    val limitButton = action(R.string.offline_comment_limit_this_video)
    val reload = action(R.string.offline_comments_reload)
    val more = action(R.string.offline_comments_more).apply { setTextColor(accent); visibility = View.GONE }
    val limitSummary = label(11f).apply { tag = "cache-limit-summary"; setPadding(0, dp(2), 0, dp(5)) }
    val footer = label(11f).apply { tag = "cache-stream-footer"; setText(R.string.toki_stream_hint); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
    val list = ListView(context).apply {
        tag = "cached-comments-list"; divider = null; dividerHeight = 0
        isVerticalScrollBarEnabled = false; clipToPadding = false
    }
    val details = ScrollView(context).apply { visibility = View.GONE; tag = "cache-details"; isFillViewport = false }
    val detailsToggle = action(R.string.toki_cache_details).apply {
        background = null; setText(R.string.toki_comment_tools); textSize = 12f; contentDescription = context.getString(R.string.toki_cache_details)
    }
    init {
        root.addView(View(context).apply {
            background = GradientDrawable().apply { setColor(secondary); cornerRadius = dp(2).toFloat() }
        }, LinearLayout.LayoutParams(dp(32), dp(3)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(4); bottomMargin = dp(5) })
        val title = label(18f).apply {
            setText(R.string.offline_comments_title); setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(detailsToggle, LinearLayout.LayoutParams(dp(64), dp(44)))
            addView(close, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(6) })
        }
        val detailContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(status)
            addView(label(11f).apply { setText(R.string.toki_offline_read_hint) })
            for (pair in listOf(online to nextPage, reload to limitButton)) {
                addView(LinearLayout(context).apply {
                    addView(pair.first, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                    addView(pair.second, LinearLayout.LayoutParams(0, -2, 1f))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
        }
        details.addView(detailContent)
        fun toggle() { details.visibility = if (details.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        detailsToggle.setOnClickListener { toggle() }
        summary.setOnClickListener { toggle() }
        summary.contentDescription = context.getString(R.string.toki_cache_details)
        root.addView(header)
        root.addView(summary, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        root.addView(limitSummary)
        root.addView(modes, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        root.addView(details, LinearLayout.LayoutParams(-1, dp(180)))
        root.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(progress, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) })
            addView(networkStatus, LinearLayout.LayoutParams(0, -2, 1f))
        })
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
    }
    fun fit(dialog: AlertDialog) {
        dialog.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(minOf(context.resources.displayMetrics.widthPixels, dp(600)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}
