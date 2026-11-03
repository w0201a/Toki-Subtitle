package io.github.meiyongai.toki.subtitle.hook

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewStub
import java.lang.ref.WeakReference

/** Bounded weak-identity roots with live semantic revalidation; no labels, parent hiding or model mutation. */
internal class TemplateRootBindings(private val gate: CleanViewGate, private val current: (Any) -> Snapshot?,
    private val report: (Exception) -> Unit = {}, private val capacity: Int = 256) : AutoCloseable {
    data class Snapshot(val root: View, val identity: Any, val context: Any, val target: Boolean)
    private class Binding(owner: Any, source: Snapshot) {
        val owner = WeakReference(owner); val root = WeakReference(source.root)
        val identity = WeakReference(source.identity); val context = WeakReference(source.context)
        var listener: View.OnAttachStateChangeListener? = null
    }
    private val rule = CleanViewGate.Owner(hiddenVisibility = View.GONE)
    private val rows = mutableListOf<Binding>()
    private var closed = false
    init { require(capacity > 0) }
    private fun safe(root: View): Boolean = root !is ViewStub && root !is SurfaceView && root !is TextureView &&
        !(root.isAttachedToWindow && root === root.rootView)
    private fun same(binding: Binding, value: Snapshot): Boolean = value.target && binding.root.get() === value.root &&
        binding.identity.get() === value.identity && binding.context.get() === value.context && safe(value.root)
    private fun prune() { rows.toList().filter { it.owner.get() == null || it.root.get() == null || it.identity.get() == null || it.context.get() == null }.forEach(::remove) }
    fun observe(owner: Any, value: Snapshot?) {
        if (closed) return
        prune()
        if (value == null || !value.target || !safe(value.root)) { release(owner); return }
        rows.firstOrNull { it.owner.get() === owner && same(it, value) }?.let { activate(it, value.root); return }
        release(owner)
        rows.firstOrNull { it.root.get() === value.root }?.let(::remove)
        if (rows.size >= capacity) return
        val binding = Binding(owner, value); rows += binding
        binding.listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { validate(binding) }
            override fun onViewDetachedFromWindow(view: View) { gate.unbind(rule, view) }
        }.also(value.root::addOnAttachStateChangeListener)
        activate(binding, value.root)
    }
    private fun activate(binding: Binding, root: View) {
        if (!closed && binding in rows && root.parent != null && root !== root.rootView) {
            gate.bind(rule, root); gate.refresh(root)
        }
    }
    private fun validate(binding: Binding) {
        val owner = binding.owner.get()
        val value = try { owner?.let(current) } catch (error: Exception) { report(error); null }
        if (value == null || !same(binding, value)) remove(binding) else activate(binding, value.root)
    }
    fun configure(enabled: Boolean) {
        if (closed) return
        prune(); rule.clean = enabled
        rows.toList().forEach(::validate)
        gate.refresh()
    }
    fun release(owner: Any?) { rows.toList().filter { it.owner.get() === owner }.forEach(::remove) }
    private fun remove(binding: Binding) {
        rows.remove(binding)
        binding.root.get()?.let { root -> binding.listener?.let(root::removeOnAttachStateChangeListener); gate.unbind(rule, root) }
        binding.listener = null
    }
    override fun close() {
        if (closed) return
        closed = true; rows.toList().forEach(::remove); gate.detach(rule)
    }
}
