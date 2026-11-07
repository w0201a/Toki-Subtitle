package io.github.meiyongai.toki.subtitle.hook

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.AbsListView
import android.widget.Toast
import android.widget.RadioButton
import android.widget.RadioGroup
import io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
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
        if (!CommentCoreRollout.enabled()) return showLegacy(activity, video, reader, network, replies, model, current, dismissed)
        return CommentReaderSheet.show(activity, video, reader, model, current, dismissed)
    }
    /** Recovery implementation, never entered by the new reader. */
    private fun showLegacy(activity: Activity, video: String, reader: HostOfflineComments, network: HostCommentPageNetwork, replies: HostCommentReplyNetwork, model: Any,
        current: () -> Boolean, dismissed: (AlertDialog) -> Unit): AlertDialog {
        val context = TokiDialogs.builder(HostSettingsEntry.localizedContext(activity)).context
        val app = activity.applicationContext
        val main = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val generation = AtomicLong(0)
        val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8), ThreadPoolExecutor.AbortPolicy())
        val sheet = OfflineCommentsSheet(context)
        val container = sheet.root
        val status = sheet.status
        val networkStatus = sheet.networkStatus
        val online = sheet.online
        val nextPage = sheet.nextPage
        val modes = sheet.modes
        val modeButtons = listOf(
            OfflineCommentData.Mode.TRANSLATED to R.string.offline_comment_mode_translated,
            OfflineCommentData.Mode.ORIGINAL to R.string.offline_comment_mode_original,
            OfflineCommentData.Mode.BILINGUAL to R.string.offline_comment_mode_bilingual).associate { (mode, label) ->
                mode to RadioButton(context).apply { id = android.view.View.generateViewId(); setText(if (mode == OfflineCommentData.Mode.BILINGUAL) R.string.toki_comment_bilingual else label); textSize = 12f }.also {
                    modes.addView(it, RadioGroup.LayoutParams(0, -2, 1f))
                }
            }
        fun configuredMode(): OfflineCommentData.Mode = when (ConfigClient.getString(OfflineCommentPreferences.MODE, "translated")) {
            "original" -> OfflineCommentData.Mode.ORIGINAL
            "bilingual" -> OfflineCommentData.Mode.BILINGUAL
            else -> OfflineCommentData.Mode.TRANSLATED
        }
        TokiDialogs.segmented(modes)
        val limitButton = sheet.limitButton
        val list = sheet.list
        var pageOperation: CommentPageOperation? = null
        var pageTimeout: Runnable? = null
        var loadingLocal = false
        var fetching = false
        var onlineContinuation = false
        var preparing = false
        var translationOnly = false
        var nextReplyTurn = true
        var continueStream: () -> Unit = {}
        var translateLoaded: () -> Unit = {}
        var reloadForLimit: () -> Unit = {}
        var displayedLimit: Int? = null
        var displayedQuotas: OfflineCommentBudget.Quotas? = null
        var mediaEpoch = 0L
        var mediaTasks = 0
        fun feedback(message: String, busy: Boolean = false) { sheet.feedback(message, busy) }
        val attemptedTranslations = mutableSetOf<String>()
        val mediaWorker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(8), ThreadPoolExecutor.AbortPolicy())
        fun effectiveLimit(account: String) = OfflineCommentLimitOverrides.configured(app, account, video)
        fun quotas(account: String) = OfflineCommentLimitOverrides.quotas(app, account, video)
        var archiveState="missing"
        var storedCounts: OfflineCommentArchive.Counts? = null
        var cacheDirty = false
        var cacheReading = false
        var refreshCached: () -> Unit = {}
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
            online.isEnabled = !closed.get() && (!loadingLocal || onlineContinuation)
            online.setText(if (onlineContinuation || preparing || mediaTasks > 0) R.string.toki_action_stop else R.string.toki_stream_online)
            nextPage.isEnabled = !closed.get() && !fetching && !loadingLocal && !preparing && snapshot != null
            sheet.reload.isEnabled = !closed.get() && !fetching && !loadingLocal
            limitButton.isEnabled = snapshot != null && !loadingLocal && !closed.get()
            sheet.progress.visibility = if (fetching || loadingLocal || preparing || mediaTasks > 0) android.view.View.VISIBLE else android.view.View.GONE
            sheet.reload.setText(if (loadingLocal) R.string.offline_comments_loading else R.string.offline_comments_reload)
            nextPage.setText(if (preparing) R.string.toki_action_translating else R.string.toki_stream_translate)
            sheet.more.visibility = android.view.View.GONE
            sheet.footer.text = context.getString(when {
                fetching || loadingLocal -> R.string.toki_stream_loading
                snapshot != null && quotas(snapshot.account).rootCount(snapshot.rows) >= effectiveLimit(snapshot.account) -> R.string.toki_stream_limit
                adapter.hasNext || onlineContinuation -> R.string.toki_stream_hint
                else -> R.string.toki_stream_end
            }, snapshot?.let { effectiveLimit(it.account) } ?: 0)
        }
        fun renderSummary() {
            val snapshot = adapter.snapshot
            if (snapshot == null) sheet.summary.text = status.text
            if (snapshot != null) {
                val top = snapshot.rows.count { it.parentId.isBlank() && it.depth == 0 }
                val replyRows = snapshot.rows.size - top
                val assets = snapshot.rows.sumOf { it.media.size }
                sheet.summary.text = context.getString(R.string.toki_cached_compact, top, replyRows)
                val defaultLimit = OfflineCommentPreferences.limit(ConfigClient.getString(OfflineCommentPreferences.LIMIT))
                val ownLimit = OfflineCommentLimitOverrides.savedLimit(app, snapshot.account, video)
                displayedLimit = effectiveLimit(snapshot.account)
                displayedQuotas = quotas(snapshot.account)
                sheet.limitSummary.text = (if (ownLimit == null) context.getString(R.string.toki_limit_inherited, defaultLimit)
                    else context.getString(R.string.toki_limit_overridden, ownLimit, defaultLimit)) + "\n" +
                    context.getString(R.string.toki_reply_limits, quotas(snapshot.account).replies, quotas(snapshot.account).perThread)
                status.text = context.getString(R.string.toki_cached_counts, top, replyRows, assets) + "\n" +
                    context.getString(R.string.toki_count_breakdown, storedCounts?.comments ?: top, storedCounts?.replies ?: replyRows, top, replyRows, snapshot.total) +
                    (if (snapshot.hasMore || snapshot.truncated) "\n" + context.getString(R.string.offline_comments_partial) else "") +
                    (if (snapshot.retainedOlder) "\n" + context.getString(R.string.comment_page_retained_older) else "")
                if (snapshot.rows.isEmpty()) status.text = context.getString(R.string.offline_comments_empty) + "\n" + status.text
                if (!snapshot.hasMore && snapshot.total > top + replyRows) status.append("\n" + context.getString(R.string.toki_server_exhausted))
                if(archiveState!="missing")status.append("\n"+context.getString(
                    if(archiveState=="complete")R.string.offline_comment_cache_complete else R.string.offline_comment_cache_incomplete))
            }
            refreshButtons()
        }
        val translation = CachedCommentTranslationSession(
            post = { runnable, delay -> main.postDelayed(runnable, delay); Unit }, remove = { main.removeCallbacks(it) },
            valid = { valid() && translatingSnapshot != null && adapter.snapshot === translatingSnapshot },
            changed = { row, target, state ->
                adapter.update(row, target, state)
                if (!state.busy && state.translated == null && preparing) {
                    feedback(context.getString(if (state.failed > 0) R.string.cached_comment_partial_translation else R.string.cached_comment_translation_unchanged, state.failed))
                    main.post { translateLoaded() }
                }
            },
            complete = { row, target, text ->
                val snapshot = translatingSnapshot
                if (snapshot != null && valid()) {
                    val token = generation.get()
                    try { worker.execute {
                        val result=try{
                            if(!valid()||generation.get()!=token)throw IllegalStateException("Cancelled")
                            check(OfflineCommentArchive.updateTranslation(app,snapshot,row,target,text)) { "No matching archived comment" }
                            CommentTranslationStore.Saved.COMMITTED
                        }catch(_:Exception){CommentTranslationStore.Saved.UNAVAILABLE}
                        main.post { if(!closed.get()&&generation.get()==token&&valid()&&adapter.snapshot===snapshot)
                            {
                                adapter.saved(row,target,text,result)
                                if (result == CommentTranslationStore.Saved.COMMITTED) {
                                    adapter.snapshot?.let { old -> adapter.submit(old.copy(rows = old.rows.map { if (it.id == row.id && it.source == row.source) it.copy(translation = text, language = target) else it }), retain = true) }
                                }
                                feedback(context.getString(if (result == CommentTranslationStore.Saved.COMMITTED) R.string.cached_comment_translation_saved else R.string.cached_comment_translation_save_failed, target))
                                refreshButtons()
                                if (preparing) main.post { translateLoaded() }
                            } }
                    } } catch (_: java.util.concurrent.RejectedExecutionException) {
                        preparing = false; feedback(context.getString(R.string.offline_comments_busy)); refreshButtons()
                    }
                }
            })
        fun requestTranslation(snapshot: OfflineCommentData.Snapshot, row: OfflineCommentData.Row) {
            if (!valid() || adapter.snapshot !== snapshot) return
            if (translation.busy || fetching) {
                Toast.makeText(context, context.getString(R.string.cached_comment_translation_busy), Toast.LENGTH_SHORT).show(); return
            }
            val remote = TranslationNetworkPolicy.capture()
            if (!remote.permitsNetwork()) { feedback(context.getString(R.string.comment_page_network_unavailable)); TranslationNetworkPolicy.showMissingTranslation(); preparing = false; refreshButtons(); return }
            val channel = try { CachedCommentTranslateHook.channel() } catch (_: Exception) { null }
            if (channel == null) { feedback(context.getString(R.string.cached_comment_prepare)); Toast.makeText(context, context.getString(R.string.cached_comment_prepare), Toast.LENGTH_LONG).show(); preparing = false; refreshButtons(); return }
            translatingSnapshot = snapshot
            feedback(context.getString(R.string.toki_action_translating), true)
            attemptedTranslations += row.id + ":" + row.source
            translation.start(row, channel.target) { text, done ->
                if (!valid() || !remote.permitsNetwork()) { done(null); AutoCloseable { } }
                else channel.translate(text) { value -> done(if (remote.permitsNetwork()) value else null) }
            }
        }
        adapter = OfflineCommentListAdapter(context, ::valid, ::requestTranslation, ::renderSummary)
        val mediaRefresh = Runnable { if (!closed.get() && valid()) adapter.notifyDataSetChanged() }
        val mediaSubscription = OfflineCommentMediaStore.observe { main.removeCallbacks(mediaRefresh); main.postDelayed(mediaRefresh, 120L) }
        // Handler.hasCallbacks is API29+. Coalesce on the main thread without delaying
        // a queued refresh indefinitely while new progress notifications keep arriving.
        var archiveRefreshQueued = false
        val archiveChanged = Runnable {
            archiveRefreshQueued = false
            if (!closed.get()) { cacheDirty = true; refreshCached() }
        }
        val archiveSubscription = OfflineCommentArchive.observe { account, changedVideo ->
            if (changedVideo == video) main.post {
                if (!closed.get() && adapter.snapshot?.account?.let { it == account } != false) {
                    cacheDirty = true
                    if (!archiveRefreshQueued) {
                        archiveRefreshQueued = main.postDelayed(archiveChanged, 500L)
                    }
                }
            }
        }
        adapter.mediaLoader = model.javaClass.classLoader ?: context.classLoader
        adapter.setMode(configuredMode())
        modes.check(modeButtons.getValue(configuredMode()).id)
        var syncingMode = false
        modes.setOnCheckedChangeListener { _, selected ->
            if (syncingMode) return@setOnCheckedChangeListener
            val mode = modeButtons.entries.firstOrNull { it.value.id == selected }?.key ?: return@setOnCheckedChangeListener
            adapter.setMode(mode)
            val value = when (mode) { OfflineCommentData.Mode.ORIGINAL -> "original"; OfflineCommentData.Mode.BILINGUAL -> "bilingual"; else -> "translated" }
            try { ConfigClient.putString(context, OfflineCommentPreferences.MODE, value)
                feedback(context.getString(R.string.toki_action_mode, modeButtons.getValue(mode).text))
            } catch (_: Exception) { feedback(context.getString(R.string.manual_comment_save_error)) }
        }
        val cancelSubscription = CachedCommentTranslateHook.watch {
            translation.cancel(); adapter.cancelPending(); preparing = false; onlineContinuation = false
            feedback(context.getString(R.string.toki_action_stopped)); refreshButtons()
        }
        val configSubscription = io.github.meiyongai.toki.subtitle.provider.ConfigClient.addStateListener { snapshot ->
            if (snapshot != null) main.post {
                if (valid()) {
                    val limit = adapter.snapshot?.let { effectiveLimit(it.account) }
                    if (limit != null && displayedLimit != null && (limit != displayedLimit || adapter.snapshot?.let { quotas(it.account) } != displayedQuotas) && !fetching && !translation.busy) reloadForLimit()
                    val mode = configuredMode()
                    if (adapter.mode() != mode) {
                        adapter.setMode(mode)
                        syncingMode = true
                        try { modes.check(modeButtons.getValue(mode).id) } finally { syncingMode = false }
                    }
                }
            }
            if (snapshot == null || snapshot.boolean(TranslationNetworkPolicy.KEY)) {
                mediaEpoch++
                onlineContinuation = false; preparing = false
                val wasFetching = fetching; cancelPage()
                if (wasFetching) networkStatus.text = context.getString(R.string.comment_page_cancelled)
                refreshButtons()
            }
        }
        fun prepareMedia(snapshot: OfflineCommentData.Snapshot) {
            val assets = snapshot.rows.flatMap { it.media }.distinctBy { it.id }
            if (assets.isEmpty() || !TranslationNetworkPolicy.capture().permitsNetwork()) return
            val account = snapshot.account
            val token = mediaEpoch
            mediaTasks++; feedback(context.getString(R.string.toki_stream_media, assets.size), true)
            try { mediaWorker.execute {
                var saved = 0; var failed = 0
                for (asset in assets) {
                    if (closed.get() || token != mediaEpoch || !current() || !reader.accountMatches(account) || !TranslationNetworkPolicy.capture().permitsNetwork()) break
                    try {
                        OfflineCommentMediaStore.cache(app, account, video, asset) {
                            !closed.get() && token == mediaEpoch && current() && reader.accountMatches(account) && TranslationNetworkPolicy.capture().permitsNetwork()
                        }
                        saved++
                    } catch (_: Exception) { failed++ }
                }
                main.post { mediaTasks = (mediaTasks - 1).coerceAtLeast(0); if (!closed.get() && token == mediaEpoch && valid()) {
                    feedback(context.getString(R.string.toki_stream_media_done, saved, failed))
                    refreshButtons()
                    adapter.notifyDataSetChanged()
                } }
            } } catch (_: java.util.concurrent.RejectedExecutionException) {
                mediaTasks = (mediaTasks - 1).coerceAtLeast(0)
                feedback(context.getString(R.string.offline_comment_media_missing)); refreshButtons()
            }
        }
        translateLoaded = {
            if (!closed.get() && valid() && !translation.busy && !fetching) {
                val snapshot = adapter.snapshot
                val row = snapshot?.rows?.firstOrNull {
                    it.source.isNotBlank() && !it.sourceTruncated && it.translation.isNullOrBlank() && it.id + ":" + it.source !in attemptedTranslations
                }
                if (snapshot != null && row != null) {
                    if ((try { CachedCommentTranslateHook.channel() } catch (_: Exception) { null }) == null || !TranslationNetworkPolicy.capture().permitsNetwork()) {
                        preparing = false; feedback(context.getString(R.string.cached_comment_prepare)); refreshButtons()
                    } else requestTranslation(snapshot, row)
                }
                else { preparing = false; feedback(context.getString(R.string.toki_action_translation_done)); refreshButtons(); if (!translationOnly) continueStream() }
            }
        }
        list.adapter = adapter
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: AbsListView?, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
                if (totalItemCount > 0 && firstVisibleItem + visibleItemCount >= totalItemCount - 3) {
                    if (adapter.hasNext) adapter.append() else main.post { continueStream() }
                }
            }
        })
        val dialog = TokiDialogs.builder(context).setView(container).create()
        sheet.close.setOnClickListener { dialog.dismiss() }
        fun load() {
            if (closed.get() || !current()) return
            val token = generation.incrementAndGet()
            cancelPage(); translation.cancel(); translatingSnapshot = null; loadingLocal = true
            val preferredTarget = try { CachedCommentTranslateHook.channel()?.target } catch (_: Exception) { null }
            mediaEpoch++; adapter.submit(null); feedback(context.getString(R.string.offline_comments_loading), true); onlineContinuation = false; preparing = false; attemptedTranslations.clear()
            status.text = context.getString(R.string.offline_comments_loading)
            sheet.summary.text = status.text
            worker.queue.clear() // Only obsolete memory tasks, never cache files or records.
            try {
                worker.execute {
                    val result = try {
                        val account = reader.account(app)
                        var savedReadFailed = false
                        var archiveReadFailed = false
                        val saved = try { CommentSnapshotStore.read(app, account, video) } catch (_: Exception) { savedReadFailed = true; null }
                        val archived = try { OfflineCommentArchive.scoped(app, account, video, quotas(account)) }
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
                        Result.success(Triple(restored,archived?.stored,try { OfflineCommentArchive.state(app,account,video) } catch (_: Exception) { "partial" }))
                    } catch (error: Exception) { Result.failure<Triple<OfflineCommentData.Snapshot,OfflineCommentArchive.Counts?,String>>(error) }
                      catch (error: LinkageError) { Result.failure<Triple<OfflineCommentData.Snapshot,OfflineCommentArchive.Counts?,String>>(error) }
                    main.post {
                        if (closed.get() || generation.get() != token || !current()) return@post
                        loadingLocal = false
                        val value = result.getOrNull()?.first
                        storedCounts = result.getOrNull()?.second
                        cacheDirty = false
                        // All local rows up to the configured budget are one continuous reading list.
                        archiveState=result.getOrNull()?.third?:"missing"
                        if (value != null && reader.accountMatches(value.account)) {
                            adapter.submit(value.copy(rows = quotas(value.account).select(value.rows)))
                            feedback(context.getString(R.string.toki_action_local_loaded, adapter.snapshot?.rows?.size ?: 0))
                        } else {
                            adapter.submit(null)
                            val reason = (result.exceptionOrNull() as? OfflineCommentData.Unavailable)?.reason
                            status.text = context.getString(when {
                                value != null || reason == OfflineCommentData.Failure.ACCOUNT_CHANGED -> R.string.offline_comments_account_changed
                                reason == OfflineCommentData.Failure.MISSING -> R.string.offline_comments_missing
                                reason == OfflineCommentData.Failure.CORRUPT -> R.string.offline_comments_corrupt
                                else -> R.string.offline_comments_unsupported
                            })
                        }
                        if (adapter.snapshot == null) { sheet.summary.text = status.text; feedback(status.text.toString()) }
                        refreshButtons()
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                loadingLocal = false; status.text = context.getString(R.string.offline_comments_busy); refreshButtons()
            }
        }
        refreshCached = refresh@{
            if (!cacheDirty || cacheReading || closed.get() || loadingLocal || fetching || translation.busy || !valid()) return@refresh
            val before = adapter.snapshot ?: return@refresh
            val token = generation.get()
            val limits = quotas(before.account)
            cacheReading = true; cacheDirty = false
            try { worker.execute {
                val read = try { OfflineCommentArchive.scoped(app, before.account, video, limits) } catch (_: Exception) { null }
                val state = try { OfflineCommentArchive.state(app, before.account, video) } catch (_: Exception) { "partial" }
                main.post {
                    cacheReading = false
                    if (!closed.get() && generation.get() == token && adapter.snapshot === before && !loadingLocal && !fetching && !translation.busy && valid()) {
                        if (read != null) {
                            storedCounts = read.stored; archiveState = state
                            if (read.snapshot != before) {
                                val first = list.firstVisiblePosition; val top = list.getChildAt(0)?.top ?: 0
                                adapter.submit(read.snapshot, retain = true); list.setSelectionFromTop(first, top)
                            } else renderSummary()
                        }
                    } else if (!closed.get()) cacheDirty = true
                }
            } } catch (_: java.util.concurrent.RejectedExecutionException) { cacheReading = false; cacheDirty = true }
        }
        fun requestPage(cursor: Long, replyRoot: String? = null) {
            val selected = adapter.snapshot
            val limit = selected?.let { effectiveLimit(it.account) } ?: OfflineCommentPreferences.limit(ConfigClient.getString(OfflineCommentPreferences.LIMIT))
            if (selected != null && (if (replyRoot == null) quotas(selected.account).rootCount(selected.rows) >= limit
                else !quotas(selected.account).acceptsReplies(selected.rows, replyRoot))) {
                onlineContinuation = false; preparing = false; feedback(context.getString(R.string.offline_comment_limit_reached)); refreshButtons(); return
            }
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
            if (!remote.permitsNetwork()) { onlineContinuation = false; preparing = false; feedback(context.getString(R.string.comment_page_network_unavailable)); refreshButtons(); return }
            val token = generation.incrementAndGet()
            translation.cancel(); translatingSnapshot = null
            val op = CommentPageOperation(); pageOperation = op; fetching = true
            feedback(context.getString(if (replyRoot == null) R.string.comment_page_loading else R.string.comment_reply_loading), true); refreshButtons()
            val preferredTarget = try { CachedCommentTranslateHook.channel()?.target } catch (_: Exception) { null }
            fun failed(error: Throwable?) {
                if (closed.get() || generation.get() != token || pageOperation !== op) return
                onlineContinuation = false; preparing = false
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
                                    val bounded = merged.copy(rows = OfflineCommentBudget.mergeScoped(old?.rows.orEmpty(), merged.rows, quotas(account)))
                                    check(op.active && reader.accountMatches(account))
                                    val admitted = bounded.rows.mapTo(hashSetOf()) { it.id }
                                    OfflineCommentArchive.save(app,page.copy(rows = page.rows.filter { it.id in admitted }),cursor,replyRoot.orEmpty(), verifiedReplyTotals = true) {
                                        op.active&&!closed.get()&&generation.get()==token&&reader.accountMatches(account)
                                    }
                                    val saved=CommentSnapshotStore.Saved.COMMITTED
                                    val restored = try { CommentTranslationStore.restore(app, bounded, preferredTarget) } catch (_: Exception) { bounded }
                                    Result.success(restored to saved)
                                } catch (error: Exception) { Result.failure<Pair<OfflineCommentData.Snapshot, CommentSnapshotStore.Saved>>(error) }
                                  catch (error: LinkageError) { Result.failure<Pair<OfflineCommentData.Snapshot, CommentSnapshotStore.Saved>>(error) }
                                main.post {
                                    if (closed.get() || generation.get() != token || pageOperation !== op || !current()) return@post
                                    val value = processed.getOrNull()
                                    if (value == null || !reader.accountMatches(value.first.account)) failed(processed.exceptionOrNull()) else {
                                        cancelPage()
                                        val firstPosition = list.firstVisiblePosition
                                        val firstTop = list.getChildAt(0)?.top ?: 0
                                        adapter.submit(value.first, retain = true)
                                        list.setSelectionFromTop(firstPosition, firstTop)
                                        prepareMedia(value.first)
                                        refreshButtons()
                                        if (preparing) main.post { translateLoaded() } else if (onlineContinuation) main.postDelayed({ continueStream() }, 150L)
                                        feedback(context.getString(when (value.second) {
                                            CommentSnapshotStore.Saved.COMMITTED -> R.string.comment_page_saved
                                            CommentSnapshotStore.Saved.FULL -> R.string.comment_page_not_saved_full
                                            CommentSnapshotStore.Saved.UNAVAILABLE -> R.string.comment_page_not_saved
                                            CommentSnapshotStore.Saved.CANCELLED -> R.string.comment_page_cancelled
                                        }))
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
        limitButton.setOnClickListener {
            val snapshot = adapter.snapshot ?: return@setOnClickListener
            val fallback = OfflineCommentPreferences.limit(ConfigClient.getString(OfflineCommentPreferences.LIMIT))
            val selected = effectiveLimit(snapshot.account)
            TokiDialogs.builder(context).setTitle(R.string.offline_comment_limit_title)
                .setItems(arrayOf(context.getString(R.string.toki_root_limit_label), context.getString(R.string.toki_reply_limits_title))) { _, choice ->
                    if (!valid() || adapter.snapshot?.account != snapshot.account || !reader.accountMatches(snapshot.account)) return@setItems
                    if (choice == 0) OfflineCommentLimitDialog.show(context, selected, true) save@{ value ->
                        if (!valid() || adapter.snapshot?.account != snapshot.account || !reader.accountMatches(snapshot.account)) return@save
                        OfflineCommentLimitOverrides.set(app, snapshot.account, video, value)
                        OfflineCommentDownloadHook.refresh(model)
                        feedback(context.getString(R.string.toki_action_limit_saved, value ?: fallback)); load()
                    } else {
                        val limits = quotas(snapshot.account)
                        OfflineCommentLimitDialog.replies(context, limits.replies, limits.perThread) save@{ total, thread ->
                            if (!valid() || adapter.snapshot?.account != snapshot.account || !reader.accountMatches(snapshot.account)) return@save
                            ConfigClient.putValues(context, mapOf(OfflineCommentPreferences.REPLY_LIMIT to total.toString(),
                                OfflineCommentPreferences.THREAD_LIMIT to thread.toString()))
                            OfflineCommentDownloadHook.refresh(model); load()
                        }
                    }
                }.show()
        }
        adapter.loadReplies = { snapshot, row ->
            if (adapter.snapshot === snapshot) {
                val saved = snapshot.replyCursors[row.id]
                val cursor = if (saved?.hasMore == true) saved.next ?: 0L else 0L
                feedback(context.getString(R.string.comment_reply_loading), true)
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
                    onlineContinuation = false; preparing = false
                    status.text = context.getString(R.string.offline_comments_account_changed)
                    networkStatus.text = ""; refreshButtons()
                }
                if (cacheDirty) refreshCached()
                main.postDelayed(this, 1000)
            }
        }
        fun release() {
            closed.set(true); mediaEpoch++; generation.incrementAndGet(); cancelPage(); translation.close()
            cancelSubscription.close(); configSubscription.close(); mediaSubscription.close(); archiveSubscription.close(); translatingSnapshot = null
            worker.shutdown(); worker.queue.clear(); mediaWorker.shutdown(); mediaWorker.queue.clear(); main.removeCallbacksAndMessages(null)
            adapter.submit(null)
        }
        dialog.setOnDismissListener {
            adapter.dismissMenus(); release(); dismissed(dialog) }
        try {
            dialog.show()
            sheet.fit(dialog)
            refreshButtons()
            continueStream = {
                val snapshot = adapter.snapshot
                if (!closed.get() && valid()) {
                    val limit = snapshot?.let { effectiveLimit(it.account) } ?: OfflineCommentPreferences.limit(ConfigClient.getString(OfflineCommentPreferences.LIMIT))
                    when (val step = OfflineCommentStreamPlan.next(snapshot, onlineContinuation, fetching || loadingLocal || translation.busy,
                        limit, nextReplyTurn, snapshot?.let { quotas(it.account) } ?: OfflineCommentBudget.Quotas(limit))) {
                        is OfflineCommentStreamPlan.Step.Replies -> { nextReplyTurn = false; requestPage(step.cursor, step.root) }
                        is OfflineCommentStreamPlan.Step.Root -> { nextReplyTurn = true; requestPage(step.cursor) }
                        OfflineCommentStreamPlan.Step.End -> {
                            onlineContinuation = false; feedback(context.getString(R.string.toki_action_online_done)); refreshButtons()
                        }
                        OfflineCommentStreamPlan.Step.Limit -> {
                            onlineContinuation = false; preparing = false; refreshButtons()
                            feedback(context.getString(R.string.offline_comment_limit_reached))
                        }
                        OfflineCommentStreamPlan.Step.Idle -> Unit
                    }
                }
            }
            sheet.reload.setOnClickListener { load() }
            reloadForLimit = { load() }
            online.setOnClickListener {
                if (onlineContinuation || preparing || mediaTasks > 0) {
                    onlineContinuation = false; preparing = false; mediaEpoch++; translation.cancel(); cancelPage()
                    feedback(context.getString(R.string.toki_action_stopped)); refreshButtons()
                    return@setOnClickListener
                }
                onlineContinuation = true; preparing = true; translationOnly = false; attemptedTranslations.clear()
                adapter.snapshot?.let { prepareMedia(it) }
                if (adapter.snapshot == null) requestPage(0L) else {
                    val snapshot = checkNotNull(adapter.snapshot)
                    if (snapshot.hasMore || snapshot.replyCursors.isNotEmpty()) continueStream() else requestPage(0L)
                    if (!fetching) translateLoaded()
                }
            }
            nextPage.setOnClickListener { translationOnly = true; preparing = true; attemptedTranslations.clear(); feedback(context.getString(R.string.toki_action_translating), true); refreshButtons(); translateLoaded() }
            main.post(checkAccount); load()
        } catch (error: Exception) { release(); throw error }
        return dialog
    }
}
