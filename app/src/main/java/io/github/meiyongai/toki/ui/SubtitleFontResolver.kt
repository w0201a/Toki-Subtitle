package io.github.meiyongai.toki.subtitle.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import io.github.meiyongai.toki.subtitle.provider.SubtitleFontStore
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Async file/IPC loading shared by preview and playback. Late loads never change a newer selection. */
internal class SubtitleFontResolver(
    private val load: (Context, String, Int) -> Typeface,
    private val execute: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val clock: () -> Long,
) {
    companion object {
        private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(4),
            { runnable -> Thread(runnable, "toki-subtitle-font").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
        private val main = Handler(Looper.getMainLooper())
        val shared = SubtitleFontResolver(load = { context, id, style ->
            val base = context.contentResolver.openFileDescriptor(SubtitleFontStore.uri(id), "r")?.use {
                checkNotNull(Typeface.Builder(it.fileDescriptor).build())
            } ?: error("Font provider unavailable")
            Typeface.create(base, style)
        }, execute = { worker.execute(it) }, dispatch = { main.post(it) }, clock = SystemClock::uptimeMillis)
    }
    private class Entry(var face: Typeface? = null, var loading: Boolean = false, var retryAt: Long = 0) {
        val views = WeakHashMap<TextView, Unit>()
    }
    private val entries = LinkedHashMap<String, Entry>(8, .75f, true)
    private val selections = WeakHashMap<TextView, String>()
    @Synchronized fun resolve(view: TextView, family: String, style: Int, asset: String): Typeface {
        val fallback = Typeface.create(family, style)
        if (!SubtitleFontStore.validId(asset)) { selections[view] = "system:$family:$style"; return fallback }
        val key = "$asset:$style"; selections[view] = key
        val entry = entries.getOrPut(key) { Entry() }
        entry.face?.let { return it }
        if (entry.loading) { entry.views[view] = Unit; return fallback }
        if (clock() < entry.retryAt) return fallback
        entry.views[view] = Unit; entry.loading = true
        val app = view.context.applicationContext
        try {
            execute(Runnable {
                val result = runCatching { load(app, asset, style) }
                dispatch(Runnable {
                    synchronized(this) {
                        entry.loading = false
                        if (entries[key] !== entry) return@synchronized
                        entry.face = result.getOrNull(); entry.retryAt = if (entry.face == null) clock() + 5000 else 0
                        val targets = entry.views.keys.map(::WeakReference); entry.views.clear()
                        for (target in targets) target.get()?.takeIf { selections[it] == key }?.let { current ->
                            entry.face?.let { current.typeface = it }; current.requestLayout(); current.invalidate()
                        }
                        trim()
                    }
                })
            })
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            entry.loading = false; entry.retryAt = clock() + 5000; entry.views.clear()
        }
        trim()
        return fallback
    }
    private fun trim() {
        while (entries.size > 8) {
            val removable = entries.entries.firstOrNull { !it.value.loading && it.key !in selections.values } ?: break
            entries.remove(removable.key) // Memory references only; font files remain untouched.
        }
    }
}
