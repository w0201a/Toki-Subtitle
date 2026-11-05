package io.github.meiyongai.toki.subtitle.hook

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewStub
import io.github.meiyongai.toki.subtitle.model.LayoutElement
import java.lang.ref.WeakReference

/** Bounded weak-identity roots with live semantic revalidation; no labels, parent hiding or model mutation. */
internal class TemplateRootBindings(private val gate: CleanViewGate, private val current: (Any) -> Snapshot?,
    private val report: (Exception) -> Unit = {}, private val capacity: Int = 256) : AutoCloseable {
    data class Snapshot(val root: View, val identity: Any, val context: Any, val target: Boolean,
        val element: LayoutElement = LayoutElement.VIDEO_TEMPLATE)
    private class Binding(owner: Any, source: Snapshot) {
        val owner = WeakReference(owner); val root = WeakReference(source.root)
        val identity = WeakReference(source.identity); val context = WeakReference(source.context)
        val element = source.element
        var listener: View.OnAttachStateChangeListener? = null
    }
    data class Statistics(val known: Int, val attached: Int)
    /** Read-only counts: neither a visibility-success claim nor a new root scan. Main thread only. */
    fun statistics(): Statistics {
        val live = rows.filter { it.owner.get() != null && it.root.get() != null && it.identity.get() != null && it.context.get() != null }
        return Statistics(live.size, live.count { it.root.get()?.isAttachedToWindow == true })
    }
    private val rules = (TemplateAnchorPolicy.controls + LayoutElement.VIDEO_TEMPLATE).associateWith { CleanViewGate.Owner(hiddenVisibility = View.GONE) }
    private val rows = mutableListOf<Binding>()
    private var closed = false
    init { require(capacity > 0) }
    private fun safe(root: View): Boolean = root !is ViewStub && root !is SurfaceView && root !is TextureView &&
        !(root.isAttachedToWindow && root === root.rootView)
    private fun same(binding: Binding, value: Snapshot): Boolean = value.target && binding.root.get() === value.root &&
        binding.identity.get() === value.identity && binding.context.get() === value.context && binding.element == value.element && safe(value.root)
    private fun prune() { rows.toList().filter { it.owner.get() == null || it.root.get() == null || it.identity.get() == null || it.context.get() == null }.forEach(::remove) }
    fun observe(owner: Any, value: Snapshot?) {
        if (closed) return
        prune()
        if (value == null || !value.target || value.element !in rules || !safe(value.root)) { release(owner); return }
        rows.firstOrNull { it.owner.get() === owner && same(it, value) }?.let { activate(it, value.root); return }
        release(owner)
        rows.firstOrNull { it.root.get() === value.root }?.let(::remove)
        if (rows.size >= capacity) return
        val binding = Binding(owner, value); rows += binding
        binding.listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { validate(binding) }
            override fun onViewDetachedFromWindow(view: View) { gate.unbind(rules.getValue(binding.element), view) }
        }.also(value.root::addOnAttachStateChangeListener)
        activate(binding, value.root)
    }
    private fun activate(binding: Binding, root: View) {
        if (!closed && binding in rows && root.parent != null && root !== root.rootView) {
            gate.bind(rules.getValue(binding.element), root); gate.refresh(root)
        }
    }
    private fun validate(binding: Binding) {
        val owner = binding.owner.get()
        val value = try { owner?.let(current) } catch (error: Exception) { report(error); null }
        if (value == null || !same(binding, value)) remove(binding) else activate(binding, value.root)
    }
    fun configure(enabled: Boolean) = configure(if (enabled) rules.keys else emptySet())
    fun configure(enabled: Set<LayoutElement>) {
        if (closed) return
        prune(); rules.forEach { (element, rule) -> rule.clean = element in enabled }
        rows.toList().forEach(::validate)
        gate.refresh()
    }
    fun release(owner: Any?) { rows.toList().filter { it.owner.get() === owner }.forEach(::remove) }
    private fun remove(binding: Binding) {
        rows.remove(binding)
        binding.root.get()?.let { root -> binding.listener?.let(root::removeOnAttachStateChangeListener); gate.unbind(rules.getValue(binding.element), root) }
        binding.listener = null
    }
    override fun close() {
        if (closed) return
        closed = true; rows.toList().forEach(::remove); rules.values.forEach(gate::detach)
    }
}
