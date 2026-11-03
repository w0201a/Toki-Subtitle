package io.github.meiyongai.toki.subtitle.hook

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Keep only the last host ORIGINAL result. A replayed module result must never become the new baseline. */
internal class VideoLayoutBindings(private val apply: (Any, Any, String) -> Unit,
    private val report: (Exception) -> Unit = {}, private val capacity: Int = 24) : AutoCloseable {
    private class Binding(owner: Any, root: View) {
        val owner = WeakReference(owner)
        val root = WeakReference(root)
        var original: Any? = null
        var reason = ""
        val outputs = mutableListOf<WeakReference<Any>>()
        var layout: View.OnLayoutChangeListener? = null
        var attach: View.OnAttachStateChangeListener? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private val bindings = WeakHashMap<Any, Binding>()
    private var closed = false
    var replaying = false
        private set
    private val refresh = Runnable {
        if (!closed && !replaying) {
            replaying = true
            try {
                bindings.values.toList().forEach { binding ->
                    val owner = binding.owner.get(); val root = binding.root.get(); val original = binding.original
                    if (owner != null && root != null && bindings[owner] === binding && original != null &&
                        root.isAttachedToWindow && root.isShown && root.width > 0 && root.height > 0 && root.getGlobalVisibleRect(Rect())) {
                        try { apply(owner, original, binding.reason) } catch (error: Exception) { report(error) }
                    }
                }
            } finally { replaying = false }
        }
    }
    init { require(capacity > 0) }
    fun bind(owner: Any, root: View) {
        if (closed || bindings[owner]?.root?.get() === root) return
        forget(owner)
        if (bindings.size >= capacity) {
            val retired = bindings.entries.firstOrNull { it.value.root.get()?.isAttachedToWindow != true }?.key
            if (retired != null) forget(retired) else return
        }
        val binding = Binding(owner, root)
        bindings[owner] = binding
        binding.layout = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) requestRefresh()
        }.also(root::addOnLayoutChangeListener)
        binding.attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { requestRefresh() }
            override fun onViewDetachedFromWindow(view: View) { binding.original = null; binding.outputs.clear() }
        }.also(root::addOnAttachStateChangeListener)
    }
    fun root(owner: Any?): View? = bindings[owner]?.root?.get()
    fun original(owner: Any?, value: Any?, reason: String): Any? {
        val binding = bindings[owner] ?: return value
        val baseline = if (value != null && binding.outputs.any { it.get() === value }) binding.original else value
        if (!closed && !replaying) { binding.original = baseline; binding.reason = reason }
        return baseline
    }
    fun transformed(owner: Any?, value: Any?) {
        if (closed || value == null) return
        val binding = bindings[owner] ?: return
        binding.outputs.removeAll { it.get() == null || it.get() === value }
        if (value !== binding.original) binding.outputs += WeakReference(value)
        while (binding.outputs.size > 8) binding.outputs.removeAt(0)
    }
    fun invalidate(owner: Any?) { bindings[owner]?.let { it.original = null; it.outputs.clear() } }
    fun requestRefresh() { if (!closed) { main.removeCallbacks(refresh); main.post(refresh) } }
    fun forget(owner: Any?) {
        val binding = bindings.remove(owner) ?: return
        binding.layout?.let { binding.root.get()?.removeOnLayoutChangeListener(it) }
        binding.attach?.let { binding.root.get()?.removeOnAttachStateChangeListener(it) }
        binding.original = null
    }
    override fun close() {
        if (closed) return
        closed = true; main.removeCallbacks(refresh)
        bindings.keys.toList().forEach(::forget)
    }
}
