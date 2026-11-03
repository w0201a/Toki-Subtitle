package io.github.meiyongai.toki.subtitle.hook

import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewStub
import io.github.meiyongai.toki.subtitle.model.PageCleanupItem
import java.lang.ref.WeakReference

/** Bounded weak-identity ownership. Only inspected component content; no parent hiding or data mutation. */
internal class PageCleanupBindings(private val gate: CleanViewGate, private val capacity: Int = 256) : AutoCloseable {
    private class Binding(component: Any, root: View, val item: PageCleanupItem) {
        val component = WeakReference(component)
        val root = WeakReference(root)
        var detach: View.OnAttachStateChangeListener? = null
    }
    private val owners = PageCleanupItem.entries.associateWith { CleanViewGate.Owner(hiddenVisibility = View.GONE) }
    private val bindings = mutableListOf<Binding>()
    private var closed = false
    init { require(capacity > 0) }
    private fun prune() {
        bindings.toList().filter { it.component.get() == null || it.root.get() == null }.forEach(::unbind)
    }
    fun configure(enabled: Set<PageCleanupItem>) {
        if (closed) return
        prune()
        owners.forEach { (item, owner) -> owner.clean = item in enabled }
        gate.refresh()
    }
    private fun activate(binding: Binding, root: View) {
        // Holders may be created/bound BEFORE addView. Wait for attach, not a guessed parent/container.
        if (!closed && binding in bindings && root.parent != null && root !== root.rootView) {
            gate.bind(owners.getValue(binding.item), root); gate.refresh(root)
        }
    }
    fun bind(component: Any, item: PageCleanupItem, root: View?) {
        if (closed) return
        prune()
        if (root == null || root is ViewStub || root is SurfaceView || root is TextureView ||
            (root.isAttachedToWindow && root === root.rootView)) {
            release(component); return
        }
        bindings.firstOrNull { it.component.get() === component && it.item == item && it.root.get() === root }?.let {
            activate(it, root); return
        }
        release(component)
        bindings.firstOrNull { it.root.get() === root }?.let(::unbind)
        if (bindings.size >= capacity) return
        val binding = Binding(component, root, item)
        bindings += binding
        binding.detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { activate(binding, view) }
            override fun onViewDetachedFromWindow(view: View) {
                // A recycled root cannot retain an old component rule. A native rebind must register it again.
                unbind(binding)
            }
        }.also(root::addOnAttachStateChangeListener)
        activate(binding, root)
    }
    fun release(component: Any?) {
        bindings.toList().filter { it.component.get() === component }.forEach(::unbind)
    }
    private fun unbind(binding: Binding) {
        bindings.remove(binding)
        binding.root.get()?.let { root ->
            binding.detach?.let(root::removeOnAttachStateChangeListener)
            gate.unbind(owners.getValue(binding.item), root)
        }
        binding.detach = null
    }
    override fun close() {
        if (closed) return
        closed = true
        bindings.toList().forEach(::unbind)
        owners.values.forEach(gate::detach)
    }
}
