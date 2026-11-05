package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.AbsListView
import android.widget.Button
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

/** Offline cache reads remain separate from explicit online page/translation actions. */
internal object OfflineCommentsDialog {
    fun show(activity: Activity, video: String, reader: HostOfflineComments, network: HostCommentPageNetwork, replies: HostCommentReplyNetwork, model: Any,
        current: () -> Boolean, dismissed: (AlertDialog) -> Unit): AlertDialog {
        val context = HostSettingsEntry.localizedContext(activity)
        val app = activity.applicationContext
        val main = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val generation = AtomicLong(0)
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2), ThreadPoolExecutor.AbortPolicy())
        val density = context.resources.displayMetrics.density
        val padding = (12 * density).toInt()
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, 0, padding, 0) }
        val status = TextView(context).apply { textSize = 13f; setPadding(0, padding, 0, padding) }
        val hint = TextView(context).apply { text = context.getString(R.string.offline_comments_hint); textSize = 11f }
        val networkStatus = TextView(context).apply { textSize = 12f }
        val online = Button(context).apply { text = context.getString(R.string.comment_page_online_first) }
        val nextPage = Button(context).apply { text = context.getString(R.string.comment_page_online_next) }
        val list = ListView(context)
        var shown: AlertDialog? = null
        var pageOperation: CommentPageOperation? = null
        var pageTimeout: Runnable? = null
        var loadingLocal = false
        var fetching = false
        var archiveOffset=0
        var archiveNext:Int?=null
        var archiveState="missing"
        lateinit var adapter: OfflineCommentListAdapter
        var translatingSnapshot: OfflineCommentData.Snapshot? = null
        fun valid(): Boolean = !closed.get() && !activity.isFinishing && !activity.isDestroyed && current() &&
            adapter.snapshot?.let { reader.accountMatches(it.account) } != false
        fun cancelPage() {
            pageTimeout?.let(main::removeCallbacks); pageTimeout = null
            try { pageOperation?.close() } catch (_: Exception) { }
            pageOperation = null; fetching = false
        }
        fun refreshButtons() {
            val snapshot = adapter.snapshot
            online.isEnabled = !closed.get() && !fetching && !loadingLocal
            nextPage.isEnabled = online.isEnabled && snapshot?.hasMore == true &&
                snapshot.nextCursor?.let { it > 0 } == true && snapshot.rows.size < OfflineCommentData.MAX_ROWS
            shown?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = adapter.hasNext || archiveNext!=null
        }
        fun renderSummary() {
            val snapshot = adapter.snapshot
            if (snapshot != null) {
                val time = if (snapshot.capturedAt > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(snapshot.capturedAt))
                    else context.getString(R.string.offline_comments_unknown_time)
                status.text = context.getString(R.string.offline_comments_count, adapter.count, snapshot.rows.size, snapshot.total, time) +
                    (if (snapshot.hasMore || snapshot.truncated) "\n" + context.getString(R.string.offline_comments_partial) else "") +
                    (if (snapshot.retainedOlder) "\n" + context.getString(R.string.comment_page_retained_older) else "")
                if (snapshot.rows.isEmpty()) status.text = context.getString(R.string.offline_comments_empty) + "\n" + status.text
                if(archiveState!="missing")status.append("\n"+context.getString(
                    if(archiveState=="complete")R.string.offline_comment_cache_complete else R.string.offline_comment_cache_incomplete))
            }
            refreshButtons()
        }
        val translation = CachedCommentTranslationSession(
            post = { runnable, delay -> main.postDelayed(runnable, delay); Unit }, remove = { main.removeCallbacks(it) },
            valid = { valid() && translatingSnapshot != null && adapter.snapshot === translatingSnapshot },
            changed = { row, target, state -> adapter.update(row, target, state) },
            complete = { row, target, text ->
                val snapshot = translatingSnapshot
                if (snapshot != null && valid()) {
                    val token = generation.get()
                    worker.execute {
                        val result=try{
                            if(!valid()||generation.get()!=token)throw IllegalStateException("Cancelled")
                            OfflineCommentArchive.updateTranslation(app,snapshot,row,target,text)
                            CommentTranslationStore.Saved.COMMITTED
                        }catch(_:Exception){CommentTranslationStore.Saved.UNAVAILABLE}
                        main.post { if(!closed.get()&&generation.get()==token&&valid()&&adapter.snapshot===snapshot)
                            adapter.saved(row,target,text,result) }
                    }
                }
            })
        fun requestTranslation(snapshot: OfflineCommentData.Snapshot, row: OfflineCommentData.Row) {
            if (!valid() || adapter.snapshot !== snapshot) return
            if (translation.busy || fetching) {
                Toast.makeText(context, context.getString(R.string.cached_comment_translation_busy), Toast.LENGTH_SHORT).show(); return
            }
            val remote = TranslationNetworkPolicy.capture()
            if (!remote.permitsNetwork()) { TranslationNetworkPolicy.showMissingTranslation(); return }
            val channel = try { CachedCommentTranslateHook.channel() } catch (_: Exception) { null }
            if (channel == null) { Toast.makeText(context, context.getString(R.string.cached_comment_prepare), Toast.LENGTH_LONG).show(); return }
            translatingSnapshot = snapshot
            translation.start(row, channel.target) { text, done ->
                if (!valid() || !remote.permitsNetwork()) { done(null); AutoCloseable { } }
                else channel.translate(text) { value -> done(if (remote.permitsNetwork()) value else null) }
            }
        }
        adapter = OfflineCommentListAdapter(context, ::valid, ::requestTranslation, ::renderSummary)
        val cancelSubscription = CachedCommentTranslateHook.watch { translation.cancel(); adapter.cancelPending() }
        val configSubscription = io.github.meiyongai.toki.subtitle.provider.ConfigClient.addStateListener { snapshot ->
            if (snapshot == null || snapshot.boolean(TranslationNetworkPolicy.KEY)) {
                val wasFetching = fetching; cancelPage()
                if (wasFetching) networkStatus.text = context.getString(R.string.comment_page_cancelled)
                refreshButtons()
            }
        }
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 3 && adapter.hasNext) adapter.append()
            }
        })
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(online, LinearLayout.LayoutParams(0, -2, 1f)); addView(nextPage, LinearLayout.LayoutParams(0, -2, 1f))
        }
        container.addView(status); container.addView(hint); container.addView(actions); container.addView(networkStatus)
        val height = minOf((360 * density).toInt(), (context.resources.displayMetrics.heightPixels * .42f).toInt())
        container.addView(list, LinearLayout.LayoutParams(-1, height.coerceAtLeast((120 * density).toInt())))
        val dialog = AlertDialog.Builder(context).setTitle(context.getString(R.string.offline_comments_title)).setView(container)
            .setPositiveButton(context.getString(R.string.offline_comments_more), null)
            .setNeutralButton(context.getString(R.string.offline_comments_reload), null)
            .setNegativeButton(context.getString(R.string.common_close), null).create()
        shown = dialog
        fun load() {
            if (closed.get() || !current()) return
            val token = generation.incrementAndGet()
            cancelPage(); translation.cancel(); translatingSnapshot = null; loadingLocal = true
            val preferredTarget = try { CachedCommentTranslateHook.channel()?.target } catch (_: Exception) { null }
            adapter.submit(null); networkStatus.text = ""
            status.text = context.getString(R.string.offline_comments_loading)
            worker.queue.clear() // Only obsolete memory tasks, never cache files or records.
            try {
                worker.execute {
                    val result = try {
                        val account = reader.account(app)
                        var savedReadFailed = false
                        var archiveReadFailed = false
                        val saved = try { CommentSnapshotStore.read(app, account, video) } catch (_: Exception) { savedReadFailed = true; null }
                        val archived = try { OfflineCommentArchive.window(app,account,video,archiveOffset) }
                            catch (_: Exception) { archiveReadFailed = true; savedReadFailed = true; null }
                        val native = archived?.snapshot ?: try { reader.read(app, video) { !closed.get() && generation.get() == token } }
                            catch (error: Exception) {
                                if (saved == null) {
                                    if (savedReadFailed) throw OfflineCommentData.Unavailable(OfflineCommentData.Failure.CORRUPT)
                                    throw error
                                } else null
                            }
                        val snapshot = when {
                            archived != null -> archived.snapshot
                            saved == null -> checkNotNull(native)
                            native == null || saved.capturedAt >= native.capturedAt -> CommentPageMerge.cached(saved, native)
                            else -> CommentPageMerge.cached(native, saved)
                        }
                        val readable = if (savedReadFailed) snapshot.copy(truncated = true) else snapshot
                        if(archived==null && !archiveReadFailed)try{OfflineCommentArchive.save(app,readable,0L)}catch(_:Exception){}
                        val restored = try { CommentTranslationStore.restore(app, readable, preferredTarget) } catch (_: Exception) { readable }
                        Result.success(Triple(restored,archived?.next,try { OfflineCommentArchive.state(app,account,video) } catch (_: Exception) { "partial" }))
                    } catch (error: Exception) { Result.failure<Triple<OfflineCommentData.Snapshot,Int?,String>>(error) }
                      catch (error: LinkageError) { Result.failure<Triple<OfflineCommentData.Snapshot,Int?,String>>(error) }
                    main.post {
                        if (closed.get() || generation.get() != token || !current()) return@post
                        loadingLocal = false
                        val value = result.getOrNull()?.first
                        archiveNext=result.getOrNull()?.second
                        archiveState=result.getOrNull()?.third?:"missing"
                        if (value != null && reader.accountMatches(value.account)) adapter.submit(value) else {
                            adapter.submit(null)
                            val reason = (result.exceptionOrNull() as? OfflineCommentData.Unavailable)?.reason
                            status.text = context.getString(when {
                                value != null || reason == OfflineCommentData.Failure.ACCOUNT_CHANGED -> R.string.offline_comments_account_changed
                                reason == OfflineCommentData.Failure.MISSING -> R.string.offline_comments_missing
                                reason == OfflineCommentData.Failure.CORRUPT -> R.string.offline_comments_corrupt
                                else -> R.string.offline_comments_unsupported
                            })
                        }
                        refreshButtons()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                loadingLocal = false; status.text = context.getString(R.string.offline_comments_busy); refreshButtons()
            }
        }
        fun requestPage(cursor: Long, replyRoot: String? = null) {
            if (closed.get() || !current() || loadingLocal || fetching) return
            if (translation.busy) { networkStatus.text = context.getString(R.string.cached_comment_translation_busy); return }
            val old = adapter.snapshot
            if (replyRoot == null) {
                if (cursor > 0 && (old == null || old.nextCursor != cursor || !valid())) return
            } else {
                if (old == null || !valid() || old.rows.none { it.id == replyRoot && it.parentId.isBlank() && it.depth == 0 }) return
                if (cursor > 0 && (old.replyCursors[replyRoot]?.next != cursor || old.replyCursors[replyRoot]?.hasMore != true)) return
            }
            val remote = TranslationNetworkPolicy.capture()
            if (!remote.permitsNetwork()) { networkStatus.text = context.getString(R.string.comment_page_network_unavailable); return }
            val token = generation.incrementAndGet()
            translation.cancel(); translatingSnapshot = null
            val op = CommentPageOperation(); pageOperation = op; fetching = true
            networkStatus.text = context.getString(if (replyRoot == null) R.string.comment_page_loading else R.string.comment_reply_loading); refreshButtons()
            val preferredTarget = try { CachedCommentTranslateHook.channel()?.target } catch (_: Exception) { null }
            fun failed(error: Throwable?) {
                if (closed.get() || generation.get() != token || pageOperation !== op) return
                cancelPage(); refreshButtons()
                networkStatus.text = context.getString(if (error?.message == "Comment snapshot capacity reached")
                    R.string.comment_page_capacity else R.string.comment_page_failed)
                HookRuntime.event("OfflineVideoHook", "comment page unavailable type=" + (error?.javaClass?.simpleName ?: "timeout"))
            }
            pageTimeout = Runnable { failed(null) }.also { main.postDelayed(it, 15000) }
            try {
                worker.execute {
                    try {
                        val account = reader.account(app)
                        val receive: (Result<Any>) -> Unit = { raw ->
                            try { worker.execute {
                                val processed = try {
                                    check(op.active && generation.get() == token && !closed.get())
                                    val page = reader.copyResponse(app, video, account, raw.getOrThrow(), System.currentTimeMillis(), root = replyRoot) {
                                        op.active && !closed.get() && generation.get() == token
                                    }
                                    val merged = if (replyRoot == null) CommentPageMerge.merge(old, page, cursor)
                                        else CommentReplyMerge.merge(checkNotNull(old), replyRoot, page, cursor)
                                    check(op.active && reader.accountMatches(account))
                                    OfflineCommentArchive.save(app,page,cursor,replyRoot.orEmpty()) {
                                        op.active&&!closed.get()&&generation.get()==token&&reader.accountMatches(account)
                                    }
                                    val saved=CommentSnapshotStore.Saved.COMMITTED
                                    val restored = try { CommentTranslationStore.restore(app, merged, preferredTarget) } catch (_: Exception) { merged }
                                    Result.success(restored to saved)
                                } catch (error: Exception) { Result.failure<Pair<OfflineCommentData.Snapshot, CommentSnapshotStore.Saved>>(error) }
                                  catch (error: LinkageError) { Result.failure<Pair<OfflineCommentData.Snapshot, CommentSnapshotStore.Saved>>(error) }
                                main.post {
                                    if (closed.get() || generation.get() != token || pageOperation !== op || !current()) return@post
                                    val value = processed.getOrNull()
                                    if (value == null || !reader.accountMatches(value.first.account)) failed(processed.exceptionOrNull()) else {
                                        cancelPage(); adapter.submit(value.first)
                                        networkStatus.text = context.getString(when (value.second) {
                                            CommentSnapshotStore.Saved.COMMITTED -> R.string.comment_page_saved
                                            CommentSnapshotStore.Saved.FULL -> R.string.comment_page_not_saved_full
                                            CommentSnapshotStore.Saved.UNAVAILABLE -> R.string.comment_page_not_saved
                                            CommentSnapshotStore.Saved.CANCELLED -> R.string.comment_page_cancelled
                                        })
                                    }
                                }
                            } } catch (error: java.util.concurrent.RejectedExecutionException) { main.post { failed(error) } }
                        }
                        if (replyRoot == null) network.fetch(app, reader, video, model, cursor, account, remote, op, receive)
                        else replies.fetch(app, reader, video, model, replyRoot, cursor, account, remote, op, receive)
                    } catch (error: Exception) { main.post { failed(error) } }
                      catch (error: LinkageError) { main.post { failed(error) } }
                }
            } catch (error: java.util.concurrent.RejectedExecutionException) { failed(error) }
        }
        adapter.loadReplies = { snapshot, row ->
            if (adapter.snapshot === snapshot) {
                val saved = snapshot.replyCursors[row.id]
                val cursor = if (saved?.hasMore == true) saved.next ?: 0L else 0L
                requestPage(cursor, row.id)
            }
        }
        val checkAccount = object : Runnable {
            override fun run() {
                if (closed.get()) return
                if (!current()) { dialog.dismiss(); return }
                val snapshot = adapter.snapshot
                if (snapshot != null && !reader.accountMatches(snapshot.account)) {
                    generation.incrementAndGet(); cancelPage(); translation.cancel(); translatingSnapshot = null; adapter.submit(null)
                    status.text = context.getString(R.string.offline_comments_account_changed)
                    networkStatus.text = ""; refreshButtons()
                }
                main.postDelayed(this, 1000)
            }
        }
        fun release() {
            closed.set(true); generation.incrementAndGet(); cancelPage(); translation.close()
            cancelSubscription.close(); configSubscription.close(); translatingSnapshot = null
            worker.shutdown(); worker.queue.clear(); main.removeCallbacksAndMessages(null)
            adapter.submit(null); shown = null
        }
        dialog.setOnDismissListener { release(); dismissed(dialog) }
        try {
            dialog.show(); refreshButtons()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if(adapter.hasNext)adapter.append() else archiveNext?.let{archiveOffset=it;load()}
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { archiveOffset=0;load() }
            online.setOnClickListener { requestPage(0L) }
            nextPage.setOnClickListener { adapter.snapshot?.nextCursor?.let { requestPage(it) } }
            main.post(checkAccount); load()
        } catch (error: Exception) { release(); throw error }
        return dialog
    }
}
