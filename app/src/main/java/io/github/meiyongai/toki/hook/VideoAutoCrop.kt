package io.github.meiyongai.toki.subtitle.hook

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Samples only the video surface, at most six times per binding. No screen capture or files. */
internal class VideoAutoCrop : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val states = WeakHashMap<View, State>()
    private var enabled = false
    private var closed = false
    private class State(val key: String, root: View) {
        val root = WeakReference(root)
        var task: Runnable? = null
        var attempts = 0
        var sampled = false
        var inFlight = false
        var scrollObserver: ViewTreeObserver? = null
        var scrollListener: ViewTreeObserver.OnScrollChangedListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        val confirmation = VideoBorderDetector.Confirmation()
        var surface: WeakReference<View>? = null
        var scaleX = 1f
        var scaleY = 1f
        var pivotX = 0f
        var pivotY = 0f
        fun restore() {
            surface?.get()?.let {
                it.scaleX = scaleX; it.scaleY = scaleY
                it.pivotX = pivotX; it.pivotY = pivotY
            }
            surface = null
        }
        fun release() {
            attachListener?.let { root.get()?.removeOnAttachStateChangeListener(it) }
            attachListener = null
            val observer = scrollObserver
            val listener = scrollListener
            if (observer?.isAlive == true && listener != null) observer.removeOnScrollChangedListener(listener)
            scrollObserver = null; scrollListener = null
            restore()
        }
    }

    fun configure(value: Boolean) {
        enabled = value
        if (!value) {
            states.values.forEach { it.task?.let(main::removeCallbacks); it.release() }
            states.clear()
        }
    }

    fun bind(root: View, key: String) {
        if (closed || !enabled || states[root]?.key == key) return
        states.remove(root)?.let { it.task?.let(main::removeCallbacks); it.release() }
        val state = State(key, root)
        states[root] = state
        // A prefetched neighbour may become selected after the initial sampling window.
        // Retry on actual scrolling rather than keeping background polling alive.
        state.scrollObserver = root.viewTreeObserver
        state.scrollListener = ViewTreeObserver.OnScrollChangedListener {
            if (active(state) && !state.sampled && state.task == null && !state.inFlight &&
                state.root.get()?.let(::visible) == true) {
                state.attempts = 0
                schedule(state)
            }
        }.also { root.viewTreeObserver.addOnScrollChangedListener(it) }
        state.attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                if (states[v] !== state) return
                states.remove(v)
                state.task?.let(main::removeCallbacks)
                state.release()
            }
        }.also { root.addOnAttachStateChangeListener(it) }
        schedule(state)
    }

    private fun active(state: State): Boolean = !closed && enabled &&
        state.root.get()?.let { states[it] === state } == true

    private fun schedule(state: State) {
        if (!active(state) || state.attempts >= 6 || state.task != null || state.inFlight) return
        val task = Runnable {
            state.task = null
            if (!active(state)) return@Runnable
            state.attempts++
            val root = state.root.get() ?: return@Runnable
            if (!visible(root)) {
                schedule(state); return@Runnable
            }
            val view = surface(root) ?: run { schedule(state); return@Runnable }
            state.sampled = true
            state.inFlight = true
            sample(view) { pixels, width, height ->
                state.inFlight = false
                if (!active(state)) return@sample
                val borders = pixels?.let { VideoBorderDetector.detect(it, width, height) }
                if (state.confirmation.accept(borders) != null && borders != null) {
                    val zoom = borders.zoom(view.width * view.scaleX, view.height * view.scaleY, root.width, root.height)
                    if (zoom > 1.01f) {
                        state.surface = WeakReference(view)
                        state.scaleX = view.scaleX; state.scaleY = view.scaleY
                        state.pivotX = view.pivotX; state.pivotY = view.pivotY
                        view.pivotX = view.width / 2f; view.pivotY = view.height / 2f
                        view.scaleX = state.scaleX * zoom; view.scaleY = state.scaleY * zoom
                        HookRuntime.count("ImmersiveFullScreenHook", "自动裁切黑边")
                    }
                } else schedule(state)
            }
        }
        state.task = task
        main.postDelayed(task, 600)
    }

    private fun visible(root: View): Boolean {
        val rect = Rect()
        return root.isShown && root.width > 0 && root.height > 0 && root.getGlobalVisibleRect(rect) &&
            rect.height() >= root.height * 0.6f && rect.width() >= root.width * 0.6f
    }

    private fun surface(root: View): View? {
        val queue = ArrayDeque<View>().apply { add(root) }
        var visited = 0
        var best: View? = null
        while (queue.isNotEmpty() && visited++ < 160) {
            val view = queue.removeFirst()
            if (!view.isShown || view.width < 32 || view.height < 32) continue
            if ((view is TextureView || view is SurfaceView) &&
                view.width.toLong() * view.height > (best?.let { it.width.toLong() * it.height } ?: 0L)) best = view
            if (view is ViewGroup) for (i in 0 until view.childCount) queue.add(view.getChildAt(i))
        }
        return best
    }

    private fun sample(view: View, done: (IntArray?, Int, Int) -> Unit) {
        val width = 96
        val height = (width.toFloat() * view.height / view.width).toInt().coerceIn(32, 192)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var completed = false
        fun finish(success: Boolean) {
            if (completed) return
            completed = true
            val pixels = if (success) IntArray(width * height).also { bitmap.getPixels(it, 0, width, 0, 0, width, height) } else null
            bitmap.recycle()
            done(pixels, width, height)
        }
        try {
            when (view) {
                is TextureView -> finish(view.isAvailable && view.getBitmap(bitmap) != null)
                is SurfaceView -> if (view.holder.surface.isValid) {
                    PixelCopy.request(view, bitmap, { finish(it == PixelCopy.SUCCESS) }, main)
                } else finish(false)
                else -> finish(false)
            }
        } catch (_: IllegalArgumentException) { finish(false) }
        catch (_: IllegalStateException) { finish(false) }
    }

    override fun close() {
        configure(false)
        closed = true
    }
}
