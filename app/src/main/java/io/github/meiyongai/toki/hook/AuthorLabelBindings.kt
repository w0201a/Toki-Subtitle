package io.github.meiyongai.toki.subtitle.hook

import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Only proven primary labels are observed. Values never retain views, native span callbacks or full user models. */
internal class AuthorLabelBindings(
    private val label: (Any) -> TextView?,
    private val current: (Any) -> AuthorLabelContract.Data?,
    private val changed: () -> Unit = {},
    private val report: (Exception) -> Unit = {},
    private val capacity: Int = 256
) : AutoCloseable {
    enum class State { DISABLED, WAITING_MODEL, NAME_MISMATCH, MISSING_REGION, DETACHED, DECORATED }
    data class Statistics(val known: Int, val decorated: Int, val waiting: Int, val mismatch: Int, val missingRegion: Int)
    private class Entry(owner: Any) {
        val owner = WeakReference(owner)
        var witnessName: String? = null
        var witnessBody: String? = null
        var state = State.DISABLED
        var observer: WeakReference<ViewTreeObserver>? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
    }
    private val entries = WeakHashMap<TextView, Entry>()
    private var enabled = false
    private var closed = false
    private var applying = false
    init { require(capacity > 0) }
    private fun notifyChange() { try { changed() } catch (_: Exception) { } }
    private fun fail(error: Exception) { try { report(error) } catch (_: Exception) { } }
    private val attachment = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) {
            val text = view as? TextView ?: return
            entries[text]?.let { attach(text, it); refresh(text) }
        }
        override fun onViewDetachedFromWindow(view: View) {
            val text = view as? TextView ?: return
            entries[text]?.let { detach(it); restore(text); state(it, State.DETACHED) }
        }
    }
    private fun state(entry: Entry, value: State) {
        if (entry.state != value) { entry.state = value; notifyChange() }
    }
    private fun attach(view: TextView, entry: Entry) {
        val observer = view.viewTreeObserver
        if (entry.observer?.get() === observer && entry.preDraw != null) return
        detach(entry)
        val weak = WeakReference(view)
        val listener = ViewTreeObserver.OnPreDrawListener { weak.get()?.let(::refresh); true }
        entry.preDraw = listener; entry.observer = WeakReference(observer)
        observer.addOnPreDrawListener(listener)
    }
    private fun detach(entry: Entry) {
        entry.observer?.get()?.takeIf { it.isAlive }?.let { observer -> entry.preDraw?.let(observer::removeOnPreDrawListener) }
        entry.observer = null; entry.preDraw = null
    }
    private fun write(view: TextView, value: CharSequence) {
        applying = true
        try { view.text = value } finally { applying = false }
    }
    private fun restore(view: TextView) {
        try {
            if (AuthorLabelPresentation.ownedPrefix(view.text) != null) write(view, AuthorLabelPresentation.original(view.text))
        } catch (error: Exception) { fail(error) }
    }
    private fun release(view: TextView) {
        val entry = entries.remove(view) ?: return
        detach(entry); view.removeOnAttachStateChangeListener(attachment); restore(view); notifyChange()
    }
    /** Called after native creation/binding. A raw-name witness admits native rich badges without guessing text prefixes. */
    fun observe(owner: Any, rawName: CharSequence? = null, before: CharSequence? = null) {
        if (closed || applying) return
        val view = try { label(owner) } catch (error: Exception) { fail(error); null } ?: return
        val prior = entries[view]
        if (prior != null && prior.owner.get() !== owner) release(view)
        var entry = entries[view]
        if (entry == null) {
            if (entries.size >= capacity) return
            entry = Entry(owner); entries[view] = entry; view.addOnAttachStateChangeListener(attachment)
            if (view.isAttachedToWindow) attach(view, entry)
            notifyChange()
        }
        val native = AuthorLabelPresentation.original(view.text).toString()
        if (!rawName.isNullOrBlank() && rawName.length <= 8192 && native.endsWith(rawName.toString()) && (before !== view.text || native == rawName.toString())) {
            entry.witnessName = rawName.toString(); entry.witnessBody = native
        } else if (entry.witnessBody != null && entry.witnessBody != native) {
            entry.witnessName = null; entry.witnessBody = null
        }
        refresh(view)
    }
    fun configure(value: Boolean) {
        if (closed) return
        enabled = value; entries.keys.toList().forEach(::refresh); notifyChange()
    }
    fun refresh(view: TextView) {
        if (closed || applying) return
        val entry = entries[view] ?: return
        val owner = entry.owner.get()
        try {
            if (owner == null || label(owner) !== view) { release(view); return }
            if (!enabled || !view.isAttachedToWindow) {
                restore(view); state(entry, if (enabled) State.DETACHED else State.DISABLED); return
            }
            val data = current(owner)
            if (data == null) { restore(view); state(entry, State.WAITING_MODEL); return }
            val body = AuthorLabelPresentation.original(view.text).toString()
            val expected = data.nativeName
            val nativeMatch = !expected.isNullOrBlank() && (body == expected ||
                entry.witnessName == expected && entry.witnessBody == body)
            if (!nativeMatch) { restore(view); state(entry, State.NAME_MISMATCH); return }
            if (data.region.isNullOrBlank() || data.region.length > 128 || data.region.any(Char::isISOControl)) {
                restore(view); state(entry, State.MISSING_REGION); return
            }
            val desiredPrefix = AuthorLocationHook.formatAuthorWithLocation("", data.region)
            if (AuthorLabelPresentation.ownedPrefix(view.text) != desiredPrefix) {
                write(view, AuthorLabelPresentation.display(view.text, data.region, true))
                // Native TextWatchers may rebind or disable the feature synchronously inside setText.
                if (closed || !enabled || entries[view] !== entry || !view.isAttachedToWindow || label(owner) !== view || current(owner) != data) {
                    restore(view); state(entry, if (!enabled) State.DISABLED else State.WAITING_MODEL); return
                }
            }
            state(entry, State.DECORATED)
        } catch (error: Exception) { restore(view); state(entry, State.WAITING_MODEL); fail(error) }
    }
    fun statistics(): Statistics {
        val states = entries.values.toList()
        return Statistics(states.size, states.count { it.state == State.DECORATED }, states.count { it.state == State.WAITING_MODEL },
            states.count { it.state == State.NAME_MISMATCH }, states.count { it.state == State.MISSING_REGION })
    }
    override fun close() {
        if (closed) return
        closed = true; entries.keys.toList().forEach(::release)
    }
}
