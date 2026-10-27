package io.github.meiyongai.toki.subtitle.hook

import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/** Opt-in single-view diagnostics; no caption text, network data, or per-frame log is retained. */
internal object CaptionDebugOverlay {
    @Volatile var enabled = false
    private var host = WeakReference<FrameLayout>(null)
    private var label = WeakReference<TextView>(null)
    private var lastUpdateAt = 0L
    private var lastText = ""
    private var lastVideoId = ""
    private var videoKey = ""

    fun update(viewForDebug: View?, videoId: String, source: String, state: String,
        captionChars: Int, translator: NativeSubtitleTranslator?) {
        if (!enabled) return
        val now = SystemClock.uptimeMillis()
        if (videoId == lastVideoId && now - lastUpdateAt < 1000) return
        lastVideoId = videoId
        lastUpdateAt = now
        val key = videoId.hashCode().toUInt().toString(16)
        val sourceView = viewForDebug ?: return
        val page = findPage(sourceView) ?: return
        if (page !== host.get()) mount(page)
        videoKey = key
        val summary = translator?.diagnosticSummary().orEmpty()
        val next = "TOKI SUBTITLE DEBUG\nvideo=…$key track=${translator?.trackSummary(videoId).orEmpty()}\n" +
            "source=$source state=$state chars=$captionChars\n$summary"
        val text = label.get() ?: return
        if (next != lastText) {
            lastText = next
            text.text = next
        }
        text.visibility = View.VISIBLE
        text.measure(View.MeasureSpec.makeMeasureSpec((page.width * .72f).toInt().coerceAtLeast(1), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((page.height * .35f).toInt().coerceAtLeast(1), View.MeasureSpec.AT_MOST))
        text.layout(12, 12, 12 + text.measuredWidth, 12 + text.measuredHeight)
    }

    private fun findPage(view: View): FrameLayout? {
        val metrics = view.resources.displayMetrics
        var candidate: FrameLayout? = null
        var current = view.parent as? View
        while (current != null && !current.javaClass.name.endsWith("DecorView")) {
            if (current is FrameLayout && current.width >= metrics.widthPixels * .8f &&
                current.height >= metrics.heightPixels * .5f && current.isShown) candidate = current
            current = current.parent as? View
        }
        return candidate
    }

    private fun mount(page: FrameLayout) {
        unmount()
        val view = TextView(page.context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC101820.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 10f
            setPadding(8, 6, 8, 6)
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        host = WeakReference(page)
        label = WeakReference(view)
        page.overlay.add(view)
    }

    fun hide() {
        enabled = false
        unmount()
    }

    fun clear() = unmount()

    private fun unmount() {
        label.get()?.let { view -> host.get()?.overlay?.remove(view) }
        host.clear()
        label.clear()
        host = WeakReference(null)
        label = WeakReference(null)
        lastText = ""
        lastVideoId = ""
        videoKey = ""
        lastUpdateAt = 0L
    }
}
