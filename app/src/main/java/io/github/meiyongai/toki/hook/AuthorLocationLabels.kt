package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.text.SpannedString
import android.view.View
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Display-only labels with bounded state; refresh is rejected after holder reuse or a native text replacement. */
internal object AuthorLocationLabels {
    private const val FEATURE = "AuthorLocationHook"
    private data class Entry(val owner: WeakReference<Any>, val original: CharSequence,
        val video: String, val author: String, var rendered: CharSequence, var renderedText: String)

    fun resolve(loader: ClassLoader): AuthorLabelContract? = try {
        AuthorLabelContract(
            loader.loadClass("com.ss.android.ugc.aweme.feed.assem.videoauthorinfo.VideoAuthorInfoRelationAssem"),
            loader.loadClass("com.ss.android.ugc.aweme.feed.model.VideoItemParams"),
            loader.loadClass("com.ss.android.ugc.aweme.feed.model.Aweme"),
            loader.loadClass("com.ss.android.ugc.aweme.profile.model.User"))
    } catch (error: Exception) {
        HookRuntime.event(FEATURE, "author label contract unavailable type=" + error.javaClass.simpleName)
        null
    } catch (error: LinkageError) {
        HookRuntime.event(FEATURE, "author label contract unavailable type=" + error.javaClass.simpleName)
        null
    }

    fun install(module: XposedModule, contract: AuthorLabelContract) {
        val main = Handler(Looper.getMainLooper())
        val entries = WeakHashMap<TextView, Entry>()
        var enabled = false
        var applying = false
        var closed = false
        var attachmentChanged: ((TextView, Boolean) -> Unit)? = null
        val detach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                (view as? TextView)?.let { attachmentChanged?.invoke(it, enabled) }
            }
            override fun onViewDetachedFromWindow(view: View) {
                // Restore only this owned label while detached, then redecorate on attachment if still current.
                (view as? TextView)?.let { attachmentChanged?.invoke(it, false) }
            }
        }
        fun forget(view: TextView) {
            entries.remove(view)
            view.removeOnAttachStateChangeListener(detach)
        }
        fun release() {
            entries.keys.toList().forEach(::forget)
            attachmentChanged = null
        }
        fun refreshView(view: TextView, active: Boolean) {
            val entry = entries[view] ?: return
            try {
                val owner = entry.owner.get()
                val current = owner?.let(contract::current)
                if (owner == null || current == null || contract.label.get(owner) !== view || current.video != entry.video ||
                    current.author != entry.author || view.text !== entry.rendered || view.text.toString() != entry.renderedText) {
                    forget(view)
                } else {
                    val text = AuthorLabelPresentation.display(entry.original, current.region, active)
                    applying = true
                    try { contract.bind.invoke(owner, view, text) } finally { applying = false }
                    entry.rendered = view.text
                    entry.renderedText = view.text.toString()
                }
            } catch (error: Exception) {
                forget(view)
                HookRuntime.event(FEATURE, "author label refresh skipped type=" + error.javaClass.simpleName)
            }
        }
        attachmentChanged = { view, active -> if (!closed) refreshView(view, active) }
        fun refresh(active: Boolean) {
            entries.keys.toList().forEach { view -> refreshView(view, active && view.isAttachedToWindow) }
        }
        HookRuntime.onDispose(FEATURE) {
            closed = true
            if (Looper.myLooper() == main.looper) { refresh(false); release() }
            else main.post { refresh(false); release() }
        }
        module.trackHook(FEATURE, contract.bind, requiresConfiguration = false).intercept { chain ->
            if (closed || applying || Looper.myLooper() != main.looper) return@intercept chain.proceed()
            val owner = checkNotNull(chain.thisObject)
            val view = chain.args[0] as? TextView ?: return@intercept chain.proceed()
            if (contract.label.get(owner) !== view) return@intercept chain.proceed()
            val source = chain.args[1] as? CharSequence ?: run {
                val result = chain.proceed()
                forget(view)
                return@intercept result
            }
            val data = try { contract.current(owner) } catch (error: ReflectiveOperationException) {
                HookRuntime.event(FEATURE, "author model not ready type=" + error.javaClass.simpleName)
                null
            } ?: run {
                val result = chain.proceed(arrayOf(view, AuthorLabelPresentation.original(source)))
                forget(view)
                return@intercept result
            }
            if (view !in entries && entries.size >= 256)
                return@intercept chain.proceed(arrayOf(view, AuthorLabelPresentation.original(source)))
            val unwrapped = AuthorLabelPresentation.original(source)
            val original = if (unwrapped is Spanned) SpannedString(unwrapped) else unwrapped.toString()
            val result = chain.proceed(arrayOf(view, AuthorLabelPresentation.display(original, data.region, enabled && view.isAttachedToWindow)))
            if (view !in entries) view.addOnAttachStateChangeListener(detach)
            entries[view] = Entry(WeakReference(owner), original, data.video, data.author, view.text, view.text.toString())
            result
        }
        HookRuntime.subscribe(FEATURE) { snapshot ->
            enabled = snapshot?.boolean(AuthorLocationHook.KEY_SHOW_AUTHOR_LOCATION) == true
            if (!closed) refresh(enabled)
            if (snapshot != null) HookRuntime.appliedConfiguration(FEATURE, snapshot)
        }
        HookRuntime.event(FEATURE, "primary author label contract registered; model values unchanged")
    }
}
