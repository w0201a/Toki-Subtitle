package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.AbsListView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import io.github.meiyongai.toki.subtitle.R
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Persisted comments remain readable disconnected; only explicit row actions use the optional prepared online channel. */
internal object OfflineCommentsDialog {
    fun show(activity: Activity, video: String, reader: HostOfflineComments, current: () -> Boolean,
        dismissed: (AlertDialog) -> Unit): AlertDialog {
        val context = HostSettingsEntry.localizedContext(activity)
        val app = activity.applicationContext
        val main = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val generation = AtomicLong(0)
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1), ThreadPoolExecutor.AbortPolicy())
        val density = context.resources.displayMetrics.density
        val padding = (12 * density).toInt()
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, 0) }
        val status = TextView(context).apply { textSize = 13f; setPadding(0, padding, 0, padding) }
        val hint = TextView(context).apply { text = context.getString(R.string.offline_comments_hint); textSize = 11f }
        val list = ListView(context)
        var shown: AlertDialog? = null
        lateinit var adapter: OfflineCommentListAdapter
        var translatingSnapshot: OfflineCommentData.Snapshot? = null
        fun valid(): Boolean = !closed.get() && !activity.isFinishing && !activity.isDestroyed && current() &&
            adapter.snapshot?.let { reader.accountMatches(it.account) } != false
        fun renderSummary() {
            val snapshot = adapter.snapshot ?: return
            val time = if (snapshot.capturedAt > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(snapshot.capturedAt))
                else context.getString(R.string.offline_comments_unknown_time)
            status.text = context.getString(R.string.offline_comments_count, adapter.count, snapshot.rows.size, snapshot.total, time) +
                if (snapshot.hasMore || snapshot.truncated) "\n" + context.getString(R.string.offline_comments_partial) else ""
            shown?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = adapter.hasNext
        }
        val translation = CachedCommentTranslationSession(
            post = { runnable, delay -> main.postDelayed(runnable, delay); Unit },
            remove = { main.removeCallbacks(it) },
            valid = { valid() && translatingSnapshot != null && adapter.snapshot === translatingSnapshot },
            changed = { row, target, state -> adapter.update(row, target, state) },
            complete = { row, target, text ->
                val snapshot = translatingSnapshot
                if (snapshot != null && valid()) {
                    val token = generation.get()
                    CommentTranslationStore.save(app, snapshot, row, target, text) { saved ->
                        main.post {
                            if (!closed.get() && generation.get() == token && valid() && adapter.snapshot === snapshot)
                                adapter.saved(row, target, text, saved)
                        }
                    }
                }
            })
        fun requestTranslation(snapshot: OfflineCommentData.Snapshot, row: OfflineCommentData.Row) {
            if (!valid() || adapter.snapshot !== snapshot) return
            if (translation.busy) {
                Toast.makeText(context, context.getString(R.string.cached_comment_translation_busy), Toast.LENGTH_SHORT).show(); return
            }
            val remote = TranslationNetworkPolicy.capture()
            if (!remote.permitsNetwork()) { TranslationNetworkPolicy.showMissingTranslation(); return }
            val channel = try { CachedCommentTranslateHook.channel() } catch (_: Exception) { null }
            if (channel == null) {
                Toast.makeText(context, context.getString(R.string.cached_comment_prepare), Toast.LENGTH_LONG).show(); return
            }
            translatingSnapshot = snapshot
            translation.start(row, channel.target) { text, done ->
                if (!valid() || !remote.permitsNetwork()) { done(null); AutoCloseable { } }
                else channel.translate(text) { value -> done(if (remote.permitsNetwork()) value else null) }
            }
        }
        adapter = OfflineCommentListAdapter(context, ::valid, ::requestTranslation, ::renderSummary)
        val cancelSubscription = CachedCommentTranslateHook.watch { translation.cancel(); adapter.cancelPending() }
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 3 && adapter.hasNext) adapter.append()
            }
        })
        container.addView(status); container.addView(hint)
        val height = minOf((420 * density).toInt(), (context.resources.displayMetrics.heightPixels * .52f).toInt())
        container.addView(list, LinearLayout.LayoutParams(-1, height.coerceAtLeast((120 * density).toInt())))
        val dialog = AlertDialog.Builder(context).setTitle(context.getString(R.string.offline_comments_title)).setView(container)
            .setPositiveButton(context.getString(R.string.offline_comments_more), null)
            .setNeutralButton(context.getString(R.string.offline_comments_reload), null)
            .setNegativeButton(context.getString(R.string.common_close), null).create()
        shown = dialog
        fun load() {
            if (closed.get() || !current()) return
            val token = generation.incrementAndGet()
            translation.cancel(); translatingSnapshot = null
            val preferredTarget = try { CachedCommentTranslateHook.channel()?.target } catch (_: Exception) { null }
            adapter.submit(null)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            status.text = context.getString(R.string.offline_comments_loading)
            worker.queue.clear() // Memory-only obsolete read requests, not files or host data.
            try {
                worker.execute {
                    val result = try {
                        val snapshot = reader.read(app, video) { !closed.get() && generation.get() == token }
                        val restored = try { CommentTranslationStore.restore(app, snapshot, preferredTarget) } catch (_: Exception) { snapshot }
                        Result.success(restored)
                    }
                        catch (error: Exception) { Result.failure<OfflineCommentData.Snapshot>(error) }
                        catch (error: LinkageError) { Result.failure<OfflineCommentData.Snapshot>(error) }
                    main.post {
                        if (closed.get() || generation.get() != token || !current()) return@post
                        val value = result.getOrNull()
                        if (value != null && reader.accountMatches(value.account)) {
                            adapter.submit(value)
                            if (value.rows.isEmpty()) status.text = context.getString(R.string.offline_comments_empty) + "\n" + status.text
                        } else {
                            adapter.submit(null)
                            val error = result.exceptionOrNull()
                            val reason = (error as? OfflineCommentData.Unavailable)?.reason
                            status.text = context.getString(when {
                                value != null || reason == OfflineCommentData.Failure.ACCOUNT_CHANGED -> R.string.offline_comments_account_changed
                                reason == OfflineCommentData.Failure.MISSING -> R.string.offline_comments_missing
                                reason == OfflineCommentData.Failure.CORRUPT -> R.string.offline_comments_corrupt
                                else -> R.string.offline_comments_unsupported
                            })
                            HookRuntime.event("OfflineVideoHook", "cached comment read unavailable reason=" + (reason?.name ?: "ADAPTER"))
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) { status.text = context.getString(R.string.offline_comments_busy) }
        }
        val checkAccount = object : Runnable {
            override fun run() {
                if (closed.get()) return
                if (!current()) { dialog.dismiss(); return }
                val snapshot = adapter.snapshot
                if (snapshot != null && !reader.accountMatches(snapshot.account)) {
                    generation.incrementAndGet(); translation.cancel(); translatingSnapshot = null; adapter.submit(null)
                    status.text = context.getString(R.string.offline_comments_account_changed)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                }
                main.postDelayed(this, 1000)
            }
        }
        dialog.setOnDismissListener {
            closed.set(true); generation.incrementAndGet(); translation.close(); cancelSubscription.close(); translatingSnapshot = null
            worker.shutdown(); worker.queue.clear()
            main.removeCallbacksAndMessages(null); adapter.submit(null); shown = null; dismissed(dialog)
        }
        try {
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { adapter.append() }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { load() }
            main.post(checkAccount)
            load()
        } catch (error: Exception) {
            closed.set(true); translation.close(); cancelSubscription.close(); worker.shutdown(); main.removeCallbacksAndMessages(null); shown = null
            throw error
        }
        return dialog
    }
}
