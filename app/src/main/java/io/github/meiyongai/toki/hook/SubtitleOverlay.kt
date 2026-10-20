package io.github.meiyongai.toki.subtitle.hook

import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.TextView
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.provider.ConfigSnapshot
import java.util.WeakHashMap

/** 页面内的字幕显示层；不申请悬浮窗权限，不改变宿主字幕布局和字体字段。 */
internal class SubtitleOverlay(private val source: View,
    private val translator: NativeSubtitleTranslator? = null,
    private val configuration: () -> ConfigSnapshot? = { if (ConfigClient.isReady) ConfigClient.snapshot() else null }
) : AutoCloseable,
    View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    companion object {
        private val sources = WeakHashMap<View, Unit>()
        var sourceRevision = 0L
            private set
        fun isSource(view: View): Boolean = view in sources
    }
    private var layer: FrameLayout? = null
    private var layerHost: ViewGroup? = null
    private var label: TextView? = null
    private var state = "HIDDEN"
    private var caption = ""
    private var videoId = ""
    private var translated: String? = null
    private var requested = false
    private var generation = 0L
    private var observing = false
    private var retryAt = 0L
    private var lastDiagnostic = ""
    private val hidden = WeakHashMap<View, Float>()
    private val sourceRect = Rect()
    private val sourceLocation = IntArray(2)
    private val overlayLocation = IntArray(2)
    private var fontName: String? = null
    private var fontStyle = -1
    private var font: Typeface? = null
    private var draggedPosition: Pair<Float, Float>? = null
    private var savedRevision = -1L
    private var lastBaseY: Float? = null
    var frameRevision = 0L
        private set
    init {
        sources[source] = Unit
        sourceRevision++
        source.addOnAttachStateChangeListener(this)
        if (source.isAttachedToWindow) onViewAttachedToWindow(source)
    }

    fun update(displayState: String, text: String, video: String = "") {
        frameRevision++
        if (caption != text || videoId != video) {
            generation++
            translated = null
            requested = false
        }
        state = displayState
        caption = text
        videoId = video
        retryAt = 0L
    }

    override fun onViewAttachedToWindow(v: View) {
        if (!observing) {
            source.viewTreeObserver.addOnPreDrawListener(this)
            observing = true
        }
    }

    override fun onViewDetachedFromWindow(v: View) {
        if (source.viewTreeObserver.isAlive) source.viewTreeObserver.removeOnPreDrawListener(this)
        observing = false
        generation++
        requested = false
        translated = null
        unmount()
        // 同一渲染器返回页面时，宿主可能去重而不再发相同帧；脱离仅释放视图，不清空最新帧。
    }

    override fun onPreDraw(): Boolean {
        if (android.os.SystemClock.uptimeMillis() < retryAt) return true
        try { render() } catch (error: Exception) {
            unmount()
            retryAt = android.os.SystemClock.uptimeMillis() + 500
            diagnostic("已恢复原生显示，等待布局重试 error=${error.javaClass.simpleName}")
        }
        return true
    }

    // SUB-POSITION：直接在页面顶层布局，字幕父容器的宽高和裁剪不参与位置计算。
    private fun render() {
        val settings = configuration()
        if (settings != null && draggedPosition != null && settings.revision != savedRevision) draggedPosition = null
        if (settings == null || !presentationEnabled(settings)) {
            resetTranslation()
            unmount()
            return
        }
        val styled = settings.boolean("subtitle_style_enabled")
        if (!styled) draggedPosition = null
        val page = visiblePage()
        val sourceVisible = page != null
        if (!frameVisible() || caption.isBlank() || !sourceVisible) {
            resetTranslation()
            layer?.visibility = View.INVISIBLE
            restoreSource()
            diagnostic("state=$state chars=${caption.length} source=$sourceVisible overlay=false")
            return
        }
        // 选择页面中足够大的 FrameLayout，让后打开的评论/菜单仍按宿主层级盖住字幕。
        // 找不到页面时保留原生显示；不把浮层提升到整个 Activity 的 DecorView。
        val host = page ?: run { unmount(); return }
        if (host.width <= 0 || host.height <= 0) return
        if (layerHost !== host) {
            unmount()
            val text = TextView(source.context).apply {
                gravity = Gravity.CENTER
                setPadding(8, 4, 8, 4)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            label = text
            layer = FrameLayout(source.context).apply {
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                // 专用绘制层不改变播放器父容器 childCount、索引、布局或触摸分发。
                host.overlay.add(this)
            }
            layerHost = host
        }
        val overlay = checkNotNull(layer)
        val text = checkNotNull(label)
        overlay.visibility = View.VISIBLE
        if (overlay.width != host.width || overlay.height != host.height) {
            overlay.measure(View.MeasureSpec.makeMeasureSpec(host.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(host.height, View.MeasureSpec.EXACTLY))
            overlay.layout(0, 0, host.width, host.height)
        }
        val translating = translator?.isEnabled(videoId) == true
        if (translating) {
            val selectedVideo = videoId
            translator?.readAhead(videoId, caption) {
                videoId == selectedVideo && visiblePage() != null &&
                    configuration()?.let(::presentationEnabled) == true
            }
        }
        if (!translating) {
            translated = null
            requested = false
        } else if (!requested) {
            requested = true
            val token = generation
            translator?.request(caption, current = {
                generation == token && frameVisible() && visiblePage() != null &&
                    configuration()?.let(::presentationEnabled) == true && translator.isEnabled(videoId)
            }, deliver = {
                translated = it
                source.postInvalidateOnAnimation()
            })
        }
        val displayed = if (translating) translated ?: caption else caption
        if (text.text.toString() != displayed) text.text = displayed
        if (styled) {
            val size = settings.string("subtitle_size", "20")!!.toFloat()
            val weight = when (settings.string("subtitle_weight", "normal")) {
                "bold" -> Typeface.BOLD
                "italic" -> Typeface.ITALIC
                "bold_italic" -> Typeface.BOLD_ITALIC
                else -> Typeface.NORMAL
            }
            val pixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, text.resources.displayMetrics)
            if (kotlin.math.abs(text.textSize - pixels) > .5f) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, pixels)
            val name = settings.string("subtitle_font", "sans-serif")
            if (name != fontName || weight != fontStyle) {
                fontName = name; fontStyle = weight
                font = Typeface.create(name, weight)
            }
            if (text.typeface != font) text.typeface = font
            val color = Color.parseColor(settings.string("subtitle_color", "#FFFFFFFF"))
            if (text.currentTextColor != color) text.setTextColor(color)
            val background = if (settings.string("subtitle_background_mode", "show") == "hide") Color.TRANSPARENT
                else Color.parseColor(settings.string("subtitle_background", "#80000000"))
            if ((text.background as? android.graphics.drawable.ColorDrawable)?.color != background) text.setBackgroundColor(background)
        } else {
            val native = nativeTextView()
            val pixels = native?.textSize ?: TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 20f, text.resources.displayMetrics)
            if (kotlin.math.abs(text.textSize - pixels) > .5f) text.setTextSize(TypedValue.COMPLEX_UNIT_PX, pixels)
            text.typeface = native?.typeface ?: Typeface.DEFAULT
            val color = native?.currentTextColor ?: Color.WHITE
            if (text.currentTextColor != color) text.setTextColor(color)
            val background = ((native?.background ?: source.background) as? android.graphics.drawable.ColorDrawable)?.color ?: Color.TRANSPARENT
            if ((text.background as? android.graphics.drawable.ColorDrawable)?.color != background) text.setBackgroundColor(background)
        }
        val margin = (12 * text.resources.displayMetrics.density).toInt()
        text.measure(View.MeasureSpec.makeMeasureSpec((overlay.width - margin * 2).coerceAtLeast(1), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec((overlay.height - margin * 2).coerceAtLeast(1), View.MeasureSpec.AT_MOST))
        source.getLocationOnScreen(sourceLocation)
        overlay.getLocationOnScreen(overlayLocation)
        val baseY = baseline(text.measuredHeight)
        val position = SubtitlePlacement.place(overlay.width, overlay.height, text.measuredWidth, text.measuredHeight,
            baseY, if (styled) draggedPosition?.first ?: settings.string("subtitle_x", "0")!!.toFloat() else 0f,
            if (styled) draggedPosition?.second ?: settings.string("subtitle_y", "0")!!.toFloat() else 0f, margin)
        text.layout(position.first, position.second, position.first + text.measuredWidth, position.second + text.measuredHeight)
        // 保留原视图测量和宿主状态；仅当独立层已可显示时隐藏重复绘制，关闭/离开即恢复。
        // 隐藏整个原字幕渲染容器，包含原背景；isShown 与布局保持不变。
        hidden.getOrPut(source) { source.alpha }
        source.alpha = 0f
        diagnostic("state=$state chars=${caption.length} source=true overlay=true sizePx=${text.textSize}" +
            " bounds=${position.first},${position.second},${text.measuredWidth},${text.measuredHeight} page=${overlay.width}x${overlay.height}")
    }

    private fun presentationEnabled(settings: ConfigSnapshot): Boolean = settings.boolean("subtitle_style_enabled") ||
        (settings.boolean("video_translate_enabled") && translator?.isEnabled(videoId) == true)

    /** Creator captions can be visibly rendered while the auto-caption state is HIDDEN.
     * Require matching native text with a visible ancestor chain; never show stale hidden frames. */
    private fun frameVisible(): Boolean {
        if (state == "EXPANDED") return true
        if (state != "HIDDEN" || caption.isBlank()) return false
        val native = nativeTextView() ?: return false
        if (!native.isShown || !native.getGlobalVisibleRect(Rect())) return false
        var view: View? = native
        while (view != null) {
            val alpha = hidden[view] ?: view.alpha
            if (alpha <= 0f) return false
            if (view === source) return true
            view = view.parent as? View
        }
        return false
    }

    private fun nativeTextView(): TextView? {
        val pending = ArrayDeque<View>().apply { add(source) }
        val matches = mutableListOf<TextView>()
        var inspected = 0
        while (pending.isNotEmpty() && inspected++ < 32) {
            val view = pending.removeFirst()
            if (view is TextView && view.text.toString() == caption) matches += view
            if (view is ViewGroup) for (index in 0 until minOf(view.childCount, (32 - inspected - pending.size).coerceAtLeast(0))) {
                pending.add(view.getChildAt(index))
            }
        }
        return matches.singleOrNull()
    }

    // 清屏会隐藏字幕和交互区域；用仍可见的视频页判断是否在屏幕内，不能依赖 source.isShown。
    private fun visiblePage(): FrameLayout? {
        if (!source.isAttachedToWindow) return null
        val metrics = source.resources.displayMetrics
        var parent = source.parent as? View
        while (parent != null && !parent.javaClass.name.endsWith("DecorView")) {
            if (parent is FrameLayout && parent.width >= metrics.widthPixels * .8f &&
                parent.height >= metrics.heightPixels * .5f) {
                if (parent.isShown && parent.alpha > 0f && parent.getGlobalVisibleRect(sourceRect)) return parent
                // 整个视频卡片离屏时不得继续向 Activity 查找，避免旧视频字幕覆盖新视频。
                if (CleanSceneBinding.containsView(parent) {
                    it is android.view.SurfaceView || it is android.view.TextureView
                }) return null
            }
            parent = parent.parent as? View
        }
        return null
    }

    private fun baseline(textHeight: Int): Float {
        if (source.isShown && source.height > 0) {
            lastBaseY = (sourceLocation[1] - overlayLocation[1] + source.height).toFloat()
        }
        return (lastBaseY ?: ((layerHost?.height ?: 0) * .78f)) - textHeight
    }

    private fun diagnostic(value: String) {
        if (value != lastDiagnostic) {
            lastDiagnostic = value
            HookRuntime.event("SubtitleOverlay", value)
        }
    }
    private fun resetTranslation() {
        if (requested || translated != null) generation++
        requested = false
        translated = null
    }
    fun hit(activity: android.app.Activity, x: Float, y: Float): Boolean {
        val text = label ?: return false
        if (source.rootView !== activity.window.decorView || layer?.visibility != View.VISIBLE) return false
        text.getLocationOnScreen(overlayLocation)
        return x >= overlayLocation[0] && x <= overlayLocation[0] + text.width &&
            y >= overlayLocation[1] && y <= overlayLocation[1] + text.height
    }

    fun position(): Pair<Float, Float> {
        val settings = configuration() ?: return 0f to 0f
        return draggedPosition ?: (settings.string("subtitle_x", "0")!!.toFloat() to settings.string("subtitle_y", "0")!!.toFloat())
    }

    fun canDrag(): Boolean = configuration()?.let {
        it.boolean("subtitle_style_enabled") && it.boolean("subtitle_drag_enabled") && !(it.boolean("subtitle_lock_x") && it.boolean("subtitle_lock_y"))
    } == true

    fun drag(origin: Pair<Float, Float>, dx: Float, dy: Float): Pair<Float, Float>? {
        val settings = configuration() ?: return null
        if (!canDrag()) return null
        val host = layerHost ?: return null
        if (host.width <= 0 || host.height <= 0) return null
        val lockX = settings.boolean("subtitle_lock_x")
        val lockY = settings.boolean("subtitle_lock_y")
        val requested = ((origin.first + (if (lockX) 0f else dx) * 100f / host.width).coerceIn(-1000f, 1000f) to
            (origin.second + (if (lockY) 0f else dy) * 100f / host.height).coerceIn(-1000f, 1000f))
        savedRevision = configuration()?.revision ?: -1L
        draggedPosition = requested
        render()
        val text = label ?: return null
        source.getLocationOnScreen(sourceLocation)
        host.getLocationOnScreen(overlayLocation)
        val baseY = baseline(text.measuredHeight)
        return ((if (lockX) origin.first else (text.left - (host.width - text.width) / 2f) * 100f / host.width) to
            (if (lockY) origin.second else (text.top - baseY) * 100f / host.height)).also { draggedPosition = it }
    }

    fun discardDrag() { draggedPosition = null; source.postInvalidateOnAnimation() }
    private fun restoreSource() {
        hidden.forEach { (view, alpha) -> view.alpha = alpha }
        hidden.clear()
    }
    private fun unmount() {
        restoreSource()
        layer?.let { layerHost?.overlay?.remove(it) }
        layer = null
        layerHost = null
        label = null
    }
    override fun close() {
        if (sources.remove(source) != null) sourceRevision++
        source.removeOnAttachStateChangeListener(this)
        onViewDetachedFromWindow(source)
        caption = ""
        state = "HIDDEN"
    }
}

internal object SubtitlePlacement {
    fun place(width: Int, height: Int, textWidth: Int, textHeight: Int, baseY: Float,
        xPercent: Float, yPercent: Float, margin: Int): Pair<Int, Int> {
        val minX = margin.coerceAtMost((width - textWidth).coerceAtLeast(0))
        val minY = margin.coerceAtMost((height - textHeight).coerceAtLeast(0))
        val maxX = (width - textWidth - margin).coerceAtLeast(minX)
        val maxY = (height - textHeight - margin).coerceAtLeast(minY)
        return ((width - textWidth) / 2f + width * xPercent / 100f).toInt().coerceIn(minX, maxX) to
            (baseY + height * yPercent / 100f).toInt().coerceIn(minY, maxY)
    }
}
