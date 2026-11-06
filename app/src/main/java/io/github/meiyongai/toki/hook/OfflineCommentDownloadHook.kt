package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import io.github.meiyongai.toki.subtitle.model.OfflineCommentPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** Download-owned background companions. Ingestion, comment pages and subtitle-channel translation never block each other. */
internal object OfflineCommentDownloadHook {
    private class IncompleteCoverage : IllegalStateException()
    private const val FEATURE = "OfflineCommentDownloadHook"
    const val KEY = "offline_comment_download_cache"
    const val BACKFILL = "offline_comment_backfill_active"
    const val PASSIVE = "offline_comment_backfill_passive"
    private var enqueueVisible: ((Any) -> Unit)? = null
    private val progress = AtomicReference<Bundle?>()
    fun statusSnapshot(): Bundle? = if (CommentCoreRollout.enabled()) CommentEngine.statusSnapshot() else progress.get()?.let(::Bundle)
    fun refresh(model: Any) { if (CommentCoreRollout.enabled()) CommentEngine.offer(model) else enqueueVisible?.invoke(model) }
    fun passive(model: Any) { if (ConfigClient.isReady && ConfigClient.getBoolean(PASSIVE)) refresh(model) }
    private fun prefs(context: Context) = context.getSharedPreferences("toki_offline_comment_jobs_v1", Context.MODE_PRIVATE)
    fun networkAllowed(): Boolean {
        return try {
            if (!ConfigClient.isReady || !ConfigClient.getBoolean(KEY)) return false
            val manager = HookRuntime.hostContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) { false }
    }
    fun capture() = LocalOnlyRequest { networkAllowed() }
    fun init(module: XposedModule, loader: ClassLoader) {
        if (CommentCoreRollout.enabled()) CommentEngine.install(module, loader) else initLegacy(module, loader)
    }
    /** Retained for recovery/reference only. Never installed alongside the v3 engine. */
    private fun initLegacy(module: XposedModule, loader: ClassLoader) {
        val app = checkNotNull(HookRuntime.hostContext); val main = Handler(Looper.getMainLooper())
        fun worker(name: String) = Executors.newSingleThreadExecutor { task -> Thread(task, name).apply { isDaemon = true } }
        val indexWorker = worker("toki-comment-index")
        val sourceWorker = worker("toki-comment-pages")
        val translationWorker = worker("toki-comment-translations")
        val mediaWorker = worker("toki-comment-media")
        val reader = HostOfflineComments(loader); val network = HostCommentPageNetwork(loader); val replies = HostCommentReplyNetwork(loader)
        data class IngressModel(val model: Any, val generation: Long)
        val ingressGeneration = AtomicLong(0L)
        val registrationRetryAt = AtomicLong(0L)
        val jobs = OfflineCommentJobStore(prefs(app)); val ingress = OfflineCommentIngress<IngressModel>()
        val aweme = Class.forName("com.ss.android.ugc.aweme.feed.model.Aweme", false, loader)
        val id = aweme.getMethod("getAid")
        val repository = Class.forName("X.0A6x", false, loader)
        val list = repository.getDeclaredMethod("LJIJI").apply { check(Modifier.isStatic(modifiers) && List::class.java.isAssignableFrom(returnType)) }
        val completion = Class.forName("X.0rF7", false, loader).getDeclaredMethod("LIZ", aweme, String::class.java).apply {
            check(Modifier.isStatic(modifiers) && returnType == Void.TYPE); isAccessible = true
        }
        val transport by lazy { NativeSubtitleTranslator.transport(loader, ::capture) }
        val facade by lazy { loader.adaptiveClass("X.03hV") }; val service by lazy { facade.adaptiveDeclaredField("LIZIZ") }
        val language by lazy { facade.adaptiveDeclaredMethod("LLILLIZIL") }
        fun cacheLimit(account: String? = null, video: String? = null): Int {
            val fallback = OfflineCommentPreferences.limit(ConfigClient.getString(OfflineCommentPreferences.LIMIT))
            return if (account == null || video == null) fallback else OfflineCommentLimitOverrides.configured(app, account, video)
        }
        fun quotas(account: String, video: String) = OfflineCommentLimitOverrides.quotas(app, account, video)
        fun target(): String = CommentTranslationKey.normalizedTarget(language.invoke(service.get(null)) as String)
            ?: error("Translation target unavailable")
        val closedFlag = AtomicBoolean(false); val readyFlag = AtomicBoolean(false)
        val draining = AtomicBoolean(false); val scanning = AtomicBoolean(false)
        val fetching = AtomicBoolean(false); val translating = AtomicBoolean(false); val cachingMedia = AtomicBoolean(false)
        val failureRuns = OfflineCommentWorkPlan.Lane.entries.associateWith { AtomicInteger(0) }
        val cooldowns = OfflineCommentWorkPlan.Lane.entries.associateWith { AtomicLong(0L) }
        val lastFailure = AtomicReference("")
        fun closed() = closedFlag.get()
        fun ready() = readyFlag.get()
        lateinit var pump: () -> Unit
        lateinit var drain: () -> Unit
        fun publish() {
            if (closed()) return
            val rows = try { jobs.rows(reader.account(app)).map { it.second } } catch (_: Exception) { emptyList() }
            progress.set(Bundle().apply {
                putInt("batchSize", OfflineCommentWorkPlan.BATCH_SIZE); putInt("videos", rows.size)
                putInt("originals", rows.count { it.optBoolean("firstPage") })
                putInt("sourcesReady", rows.count { it.optBoolean("sourceReady") })
                putInt("mediaReady", rows.count { it.optBoolean("mediaReady", true) }); putInt("commentLimit", cacheLimit())
                putInt("ready", rows.count { it.optString("state") == "complete" })
                putInt("preparedPartial", rows.count { it.optBoolean("sourceReady") && it.optBoolean("translationReady") && it.optBoolean("mediaReady", true) && it.optString("state") != "complete" })
                putInt("partial", rows.count { it.optString("state") == "partial" })
                putInt("retrying", rows.count { row -> listOf("source", "translation", "media").any { lane -> row.optLong(lane + "RetryAt") > System.currentTimeMillis() } })
                putInt("queued", ingress.size()); putBoolean("network", networkAllowed()); putBoolean("verified", ready())
                putString("failure", if (rows.all { it.optString("state") == "complete" }) "" else lastFailure.get())
                putLong("updated", System.currentTimeMillis())
            })
            HookRuntime.detail(FEATURE, "batch=5 videos=${rows.size} sourceReady=${rows.count { it.optBoolean("sourceReady") }} " +
                "ready=${rows.count { it.optString("state") == "complete" }} queued=${ingress.size()}; " + lastFailure.get())
        }
        fun record(model: Any) {
            if (closed() || !ready() || !ConfigClient.isReady || !ConfigClient.getBoolean(KEY) || !aweme.isInstance(model)) return
            val video = id.invoke(model) as? String ?: return
            val account = reader.account(app); val key = CommentSnapshotStore.key(account, video)
            val seed = JSONObject().put("account", account).put("video", video).put("cursor", 0L).put("roots", JSONArray())
                .put("epoch", prefs(app).getLong("epoch:" + account, 0L))
            OfflineCommentArchive.saveModel(app, account, video, reader.serializeModel(model))
            jobs.register(key, seed) { row ->
                val first = try { OfflineCommentArchive.page(app, account, video, 0L) != null } catch (_: Exception) { false }
                if (OfflineCommentJobStore.repairSource(row, cacheLimit(account, video), first, quotas(account, video))) {
                    OfflineCommentArchive.status(app, account, video, "pending")
                }
                if (!first && (row.optBoolean("firstPage") || row.optBoolean("sourceReady") || row.optLong("cursor") > 0L || row.optBoolean("rootPhase") || row.getJSONArray("roots").length() > 0)) {
                    row.put("cursor", 0L).put("roots", JSONArray()).put("rootPhase", false)
                        .put("sourceReady", false).put("translationReady", false).put("mediaReady", false).put("state", "pending")
                        .put("sourceRevision", row.optLong("sourceRevision") + 1L)
                }
                row.put("firstPage", first)
            }
        }
        val ingressTick = Runnable { drain() }

        drain = {
            val wait = registrationRetryAt.get() - System.currentTimeMillis()
            if (wait > 0L && !closed()) { main.removeCallbacks(ingressTick); main.postDelayed(ingressTick, wait) }
            else if (!closed() && ready() && ConfigClient.isReady && ConfigClient.getBoolean(KEY) && draining.compareAndSet(false, true)) indexWorker.execute {
                try { ingress.take(true).forEach { item ->
                    if (item.generation == ingressGeneration.get()) try { record(item.model) } catch (error: Exception) {
                        (id.invoke(item.model) as? String)?.let { ingress.offer(it, item) }
                        registrationRetryAt.set(System.currentTimeMillis() + 30_000L)
                        lastFailure.set(OfflineCommentRequestFailure.describe("register", error))
                        HookRuntime.event(FEATURE, lastFailure.get())
                    }
                } } finally {
                    draining.set(false); publish()
                    main.post { if (ingress.size() > 0) drain(); pump() }
                }
            }
        }
        fun offer(model: Any) {
            if (closed() || !aweme.isInstance(model)) return
            if (ConfigClient.isReady && !ConfigClient.getBoolean(KEY)) return
            val video = id.invoke(model) as? String ?: return
            ingress.offer(video, IngressModel(model, ingressGeneration.get())); drain()
        }
        enqueueVisible = ::offer
        fun backfill() {
            if (!closed() && ready() && ConfigClient.isReady && ConfigClient.getBoolean(KEY) &&
                ConfigClient.getBoolean(BACKFILL) && scanning.compareAndSet(false, true)) indexWorker.execute {
                try {
                    val generation = ingressGeneration.get()
                    (list.invoke(null) as? List<*>)?.filterNotNull()?.forEach { model ->
                        if (aweme.isInstance(model)) (id.invoke(model) as? String)?.let { ingress.offer(it, IngressModel(model, generation)) }
                    }
                } catch (error: Exception) { lastFailure.set(OfflineCommentRequestFailure.describe("download_list", error)) }
                finally { scanning.set(false); main.post { drain() } }
            }
        }
        fun <T> await(start: ((Result<T>) -> Unit) -> AutoCloseable): T {
            val latch = CountDownLatch(1); val result = AtomicReference<Result<T>?>()
            val handle = start { value -> result.set(value); latch.countDown() }
            try {
                if (!latch.await(20, TimeUnit.SECONDS)) throw TimeoutException("Offline comment request timeout")
                return checkNotNull(result.get()).getOrThrow()
            } finally { handle.close() }
        }
        fun active(job: JSONObject): Boolean = !closed() && networkAllowed() && reader.accountMatches(job.getString("account")) &&
            prefs(app).getLong("epoch:" + job.getString("account"), 0L) == job.optLong("epoch")
        fun sourcePage(key: String, job: JSONObject) {
            check(active(job))
            val account = job.getString("account"); val video = job.getString("video"); val limit = cacheLimit(account, video)
            val selected = OfflineCommentArchive.selectedRows(app, account, video, limit, quotas(account, video))
            val upgrade = OfflineCommentArchive.metadataPage(app, account, video, limit, job.optBoolean("refreshMedia"), quotas(account, video))
            val limits = quotas(account, video)
            if (limits.rootCount(selected) >= limit) job.put("rootPhase", true)
            val oldRoots = job.getJSONArray("roots")
            val roots = JSONArray()
            val archived = OfflineCommentArchive.scoped(app, account, video, limits)?.snapshot
            val rootIds = (0 until oldRoots.length()).mapTo(hashSetOf()) { oldRoots.getJSONObject(it).getString("id") }
            for (candidate in selected) {
                if (candidate.parentId.isNotBlank() || candidate.depth != 0 || candidate.id in rootIds) continue
                val cursor = archived?.replyCursors?.get(candidate.id)
                if (limits.acceptsReplies(selected, candidate.id) && (cursor?.hasMore ?: (candidate.replyCount > selected.count { it.parentId == candidate.id }))) {
                    oldRoots.put(JSONObject().put("id", candidate.id).put("cursor", if (cursor?.hasMore == true) cursor.next ?: 0L else 0L))
                }
            }
            for (i in 0 until oldRoots.length()) {
                val candidate = oldRoots.getJSONObject(i)
                if (limits.acceptsReplies(selected, candidate.getString("id"))) roots.put(candidate)
            }
            job.put("roots", roots)
            val root = upgrade?.root ?: if (roots.length() > 0 && (job.optBoolean("rootPhase") || job.optBoolean("nextReply")))
                roots.getJSONObject(0).getString("id") else ""
            val cursor = upgrade?.cursor ?: if (root.isNotEmpty()) roots.getJSONObject(0).getLong("cursor") else job.optLong("cursor")
            if (job.optBoolean("rootPhase") && roots.length() == 0 && upgrade == null) {
                jobs.change(key, job.optLong("epoch")) { it.put("sourceReady", true).put("sourceIncomplete", !OfflineCommentArchive.coverage(app, account, video))
                        .put("limited", limits.rootCount(selected) >= limit).put("state", "pending") }
                OfflineCommentArchive.bounded(app, account, video, limit, !(try { OfflineCommentArchive.coverage(app, account, video) } catch (_: Exception) { false }))
                return
            }
            val model = reader.parseModel(OfflineCommentArchive.model(app, account, video))
            val saved = try { OfflineCommentArchive.page(app, account, video, cursor, root) } catch (_: Exception) { null }
            val page = saved?.takeIf { !job.optBoolean("refreshSource") && !job.optBoolean("refreshMedia") &&
                OfflineCommentArchive.replyTotalsVerified(app, account, video, cursor, root) && it.rows.all { row -> row.mediaKnown } } ?: run {
                val op = CommentPageOperation(); val remote = capture()
                val response = await<Any> { done ->
                    if (root.isEmpty()) network.fetch(app, reader, video, model, cursor, account, remote, op, done)
                    else replies.fetch(app, reader, video, model, root, cursor, account, remote, op, done)
                    op
                }
                val raw = reader.copyResponse(app, video, account, response, System.currentTimeMillis(), root.takeIf { it.isNotEmpty() }) { active(job) }
                val incoming = if (root.isEmpty()) raw else raw.copy(rows = raw.rows.map {
                    it.copy(depth = (it.depth + 1).coerceAtMost(3), parentId = it.parentId.ifBlank { root })
                })
                val admitted = OfflineCommentBudget.mergeScoped(selected, incoming.rows, limits).mapTo(hashSetOf()) { it.id }
                val merged = incoming.copy(rows = incoming.rows.filter { it.id in admitted })
                OfflineCommentArchive.save(app, merged, cursor, root, verifiedReplyTotals = true) { active(job) }
                merged
            }
            val after = OfflineCommentArchive.selectedRows(app, account, video, limit, quotas(account, video))
            if (upgrade != null && !job.optBoolean("refreshMedia") && !OfflineCommentArchive.replyTotalsVerified(app, account, video, cursor, root) && after.count { !it.mediaKnown } >= selected.count { !it.mediaKnown })
                throw IllegalStateException("Comment media metadata unavailable")
            if (upgrade == null) {
                val next = OfflineCommentDownloadProgress.advance(cursor, page)
                if (root.isEmpty()) {
                    val known = (0 until roots.length()).map { roots.getJSONObject(it).getString("id") }.toMutableSet()
                    page.rows.filter { it.depth == 0 && it.parentId.isBlank() && it.replyCount > page.rows.count { child -> child.parentId == it.id } }
                        .forEach { if (known.add(it.id)) roots.put(JSONObject().put("id", it.id).put("cursor", 0L)) }
                    val noGrowth = OfflineCommentDownloadProgress.noGrowth(job.optInt("sourceNoGrowth"),
                        selected.filter { it.parentId.isBlank() }.mapTo(hashSetOf()) { it.id },
                        after.filter { it.parentId.isBlank() }.mapTo(hashSetOf()) { it.id }, page.hasMore)
                    job.put("sourceNoGrowth", noGrowth)
                    if (next != null && noGrowth < 3) job.put("cursor", next) else {
                        job.put("rootPhase", true)
                        if (noGrowth >= 3) job.put("sourceIncomplete", true)
                    }
                    job.put("nextReply", true)
                } else {
                    val remaining = JSONArray()
                    for (i in 1 until roots.length()) remaining.put(roots.get(i))
                    if (next != null) remaining.put(roots.getJSONObject(0).put("cursor", next))
                    job.put("roots", remaining).put("nextReply", false)
                }
            }
            val quota = limits.rootCount(after) >= limit && job.getJSONArray("roots").let { queue ->
                (0 until queue.length()).none { limits.acceptsReplies(after, queue.getJSONObject(it).getString("id")) }
            } && after.all { it.mediaKnown }
            val ended = job.optBoolean("rootPhase") && job.getJSONArray("roots").length() == 0
            val covered = ended && OfflineCommentArchive.coverage(app, account, video)
            check(active(job))
            jobs.change(key, job.optLong("epoch")) { row ->
                if (ended && !covered && !quota) row.put("coverageFailures", row.optInt("coverageFailures") + 1)
                else if (covered || quota) row.put("coverageFailures", 0)
                row.put("cursor", job.optLong("cursor"))
                    .put("roots", job.getJSONArray("roots"))
                    .put("rootPhase", job.optBoolean("rootPhase"))
                    .put("sourceNoGrowth", job.optInt("sourceNoGrowth"))
                    .put("sourceIncomplete", ended && !covered && !quota || job.optBoolean("sourceIncomplete"))
                    .put("nextReply", job.optBoolean("nextReply")).put("firstPage", true)
                    .put("sourceReady", ended || covered || quota).put("limited", quota && !covered)
                    .put("translationReady", false).put("mediaReady", false).put("refreshSource", false).put("refreshMedia", false).put("mediaRetryAt", 0L)
                    .put("sourceRevision", row.optLong("sourceRevision") + 1L)
                    .put("sourceAttempts", 0).put("sourceRetryAt", 0L).put("state", "pending")
            }
            OfflineCommentArchive.bounded(app, account, video, limit, quota && !covered)
            if (ended && !covered && !quota) HookRuntime.event(FEATURE, "server exhausted before coverage; saved partial archive retained")
            OfflineCommentArchive.status(app, account, video, "pending")
        }
        fun translateRow(key: String, job: JSONObject) {
            check(active(job)); val lang = target()
            if (job.optString("target") != lang) jobs.change(key, job.optLong("epoch")) { it.put("target", lang).put("translationReady", false) }
            val account = job.getString("account"); val video = job.getString("video")
            val work = OfflineCommentArchive.nextTranslation(app, account, video, lang, cacheLimit(account, video), quotas(account, video))
            if (work == null) {
                if (OfflineCommentArchive.translationsReady(app, account, video, lang, cacheLimit(account, video), quotas(account, video)) &&
                    jobs.finishTranslation(key, job.optLong("epoch"), job.optLong("sourceRevision"))) {
                    val state = jobs.get(key)?.optString("state") ?: "pending"
                    OfflineCommentArchive.status(app, account, video, state)
                }
                return
            }
            require(!work.row.sourceTruncated) { "Comment original is incomplete" }
            val restored = CommentTranslationStore.restore(app, work.page, lang).rows.first { it.id == work.row.id && it.source == work.row.source }
            val known = restored.translation?.takeIf { it.isNotBlank() && CommentTranslationKey.normalizedTarget(restored.language) == lang }
            val translated = known ?: await<String> { done ->
                val cancelled = AtomicBoolean(false); var task: ManualCommentTranslation? = null
                main.post {
                    if (cancelled.get() || !active(job)) { done(Result.failure(IllegalStateException("Cancelled"))); return@post }
                    val once = AtomicBoolean(false)
                    task = ManualCommentTranslation(translate = { text, complete ->
                        if (cancelled.get() || !active(job)) { complete(null); AutoCloseable { } } else transport(text, complete)
                    }, post = { action, delay -> main.postDelayed(action, delay); Unit }, remove = { main.removeCallbacks(it) }, changed = { state ->
                        if (!state.busy && once.compareAndSet(false, true)) {
                            val value = state.translated ?: if (state.failed == 0 && state.completed == state.total) state.original else null
                            done(if (value != null && state.failed == 0) Result.success(value) else Result.failure(IllegalStateException("Translation incomplete")))
                        }
                    })
                    task?.start(work.row.source)
                }
                AutoCloseable { cancelled.set(true); main.post { task?.close() } }
            }
            check(active(job) && target() == lang)
            check(OfflineCommentArchive.commitTranslation(app, account, video, work.row, lang, translated) { active(job) && target() == lang })
            jobs.change(key, job.optLong("epoch")) { it.put("translationAttempts", 0).put("translationRetryAt", 0L).put("state", "pending") }
        }
        fun mediaRow(key: String, job: JSONObject) {
            check(active(job)); val account = job.getString("account"); val video = job.getString("video")
            val asset = OfflineCommentArchive.nextMedia(app, account, video, cacheLimit(account, video), quotas(account, video))
            if (asset != null) {
                OfflineCommentMediaStore.cache(app, account, video, asset) { active(job) }
                jobs.change(key, job.optLong("epoch")) { it.put("mediaAttempts", 0).put("mediaRetryAt", 0L) }
            } else if (OfflineCommentArchive.mediaReady(app, account, video, cacheLimit(account, video), quotas(account, video))) {
                if (jobs.finishMedia(key, job.optLong("epoch"), job.optLong("sourceRevision")))
                    OfflineCommentArchive.status(app, account, video, jobs.get(key)?.optString("state") ?: "pending")
            } else jobs.change(key, job.optLong("epoch")) { it.put("mediaRetryAt", System.currentTimeMillis() + 3_000L) }
        }
        fun failed(key: String, job: JSONObject, lane: OfflineCommentWorkPlan.Lane, error: Exception) {
            val prefix = when (lane) { OfflineCommentWorkPlan.Lane.SOURCE -> "source"; OfflineCommentWorkPlan.Lane.TRANSLATION -> "translation"; OfflineCommentWorkPlan.Lane.MEDIA -> "media" }
            val detail = OfflineCommentRequestFailure.describe(prefix, error)
            lastFailure.set(detail)
            jobs.change(key, job.optLong("epoch")) {
                val attempt = maxOf(it.optInt(prefix + "Attempts") + 1,
                    if (lane == OfflineCommentWorkPlan.Lane.SOURCE) it.optInt("coverageFailures") else 0)
                it.put(prefix + "Attempts", attempt).put(prefix + "RetryAt", System.currentTimeMillis() + OfflineCommentWorkPlan.retryDelay(attempt))
                    .put("state", "partial").put("lastFailure", detail)
                if (lane == OfflineCommentWorkPlan.Lane.MEDIA && attempt >= 3) {
                    it.put("refreshMedia", true).put("sourceReady", false).put("sourceRetryAt", 0L)
                }
            }
            if (active(job)) OfflineCommentArchive.status(app, job.getString("account"), job.getString("video"), "partial", detail)
            val remoteFailure = generateSequence(error as Throwable?) { it.cause }.take(8)
                .any { it is IOException || it is TimeoutException }
            if (remoteFailure) {
                val failures = failureRuns.getValue(lane).incrementAndGet()
                if (failures >= 3) cooldowns.getValue(lane).set(System.currentTimeMillis() + OfflineCommentWorkPlan.retryDelay(failures))
            }
            HookRuntime.event(FEATURE, detail)
        }
        val sourceTick = Runnable { pump() }; val translationTick = Runnable { pump() }; val mediaTick = Runnable { pump() }
        fun launch(lane: OfflineCommentWorkPlan.Lane) {
            val running = when (lane) { OfflineCommentWorkPlan.Lane.SOURCE -> fetching; OfflineCommentWorkPlan.Lane.TRANSLATION -> translating; OfflineCommentWorkPlan.Lane.MEDIA -> cachingMedia }
            val wait = cooldowns.getValue(lane).get() - System.currentTimeMillis()
            if (wait > 0L && !closed()) {
                val tick = when (lane) { OfflineCommentWorkPlan.Lane.SOURCE -> sourceTick; OfflineCommentWorkPlan.Lane.TRANSLATION -> translationTick; OfflineCommentWorkPlan.Lane.MEDIA -> mediaTick }
                main.removeCallbacks(tick); main.postDelayed(tick, wait); return
            }
            if (closed() || !ready() || !networkAllowed() || !running.compareAndSet(false, true)) return
            val executor = when (lane) { OfflineCommentWorkPlan.Lane.SOURCE -> sourceWorker; OfflineCommentWorkPlan.Lane.TRANSLATION -> translationWorker; OfflineCommentWorkPlan.Lane.MEDIA -> mediaWorker }
            executor.execute {
                var scheduleFailed = false
                try {
                    val account = reader.account(app)
                    if (lane == OfflineCommentWorkPlan.Lane.TRANSLATION) {
                        val lang = target()
                        for ((key, row) in jobs.rows(account)) if (row.optString("target") != lang) {
                            jobs.change(key, row.optLong("epoch")) {
                                it.put("target", lang).put("translationReady", false).put("translationRetryAt", 0L).put("state", "pending")
                            }
                        }
                    }
                    val batch = OfflineCommentWorkPlan.batch(jobs.plans(account, lane), lane, System.currentTimeMillis())
                    for (item in batch) {
                        if (closed() || !networkAllowed() || cooldowns.getValue(lane).get() > System.currentTimeMillis()) break
                        val job = jobs.get(item.key) ?: continue
                        if (!active(job)) break
                        val prefix = when (lane) { OfflineCommentWorkPlan.Lane.SOURCE -> "source"; OfflineCommentWorkPlan.Lane.TRANSLATION -> "translation"; OfflineCommentWorkPlan.Lane.MEDIA -> "media" }
                        jobs.change(item.key, job.optLong("epoch")) { it.put(prefix + "LastRun", System.currentTimeMillis()) }
                        try {
                            when (lane) { OfflineCommentWorkPlan.Lane.SOURCE -> sourcePage(item.key, job); OfflineCommentWorkPlan.Lane.TRANSLATION -> translateRow(item.key, job); OfflineCommentWorkPlan.Lane.MEDIA -> mediaRow(item.key, job) }
                            failureRuns.getValue(lane).set(0)
                        }
                        catch (error: Exception) { failed(item.key, job, lane, error) }
                    }
                } catch (error: Exception) { scheduleFailed = true; lastFailure.set(OfflineCommentRequestFailure.describe("schedule", error)) }
                finally {
                    running.set(false)
                    if (!closed()) {
                        indexWorker.execute { publish() }
                        val delay = try { OfflineCommentWorkPlan.nextDelay(jobs.plans(reader.account(app), lane), lane, System.currentTimeMillis()) } catch (_: Exception) { 5_000L }
                        val tick = when (lane) { OfflineCommentWorkPlan.Lane.SOURCE -> sourceTick; OfflineCommentWorkPlan.Lane.TRANSLATION -> translationTick; OfflineCommentWorkPlan.Lane.MEDIA -> mediaTick }
                        main.removeCallbacks(tick)
                        if (delay != null || scheduleFailed) main.postDelayed(tick, if (scheduleFailed) maxOf(5_000L, delay ?: 5_000L) else delay!!)
                    }
                }
            }
        }
        pump = { launch(OfflineCommentWorkPlan.Lane.SOURCE); launch(OfflineCommentWorkPlan.Lane.TRANSLATION); launch(OfflineCommentWorkPlan.Lane.MEDIA) }
        module.trackHook(FEATURE, completion, requiresConfiguration = false).intercept { chain ->
            val model = chain.args[0]; val result = chain.proceed()
            if (model != null) offer(model)
            result
        }
        module.deoptimize(completion)
        val clearReason=Class.forName("X.0A9I",false,loader)
        val manualClear=clearReason.enumConstants!!.single{(it as Enum<*>).name=="CLICK_CLEAR_ICON"}
        val clearEvent=Class.forName("X.0A9A",false,loader).getDeclaredMethod("LJIIIZ",Int::class.javaPrimitiveType,Long::class.javaPrimitiveType,clearReason)
        module.trackHook(FEATURE,clearEvent,requiresConfiguration=false).intercept{chain->
            val result=chain.proceed()
            if(ready()&&chain.args[2]===manualClear) {
                ingressGeneration.incrementAndGet(); ingress.clear()
                val account=reader.account(app)
                // Epoch barrier is synchronous; outstanding fetch/translation callbacks cannot repopulate cleared data.
                check(prefs(app).edit().putLong("epoch:"+account,prefs(app).getLong("epoch:"+account,0L)+1).commit())
                indexWorker.execute {
                    val edit=prefs(app).edit()
                    for((key,value)in prefs(app).all)try{if(JSONObject(value as String).optString("account")==account)edit.remove(key)}catch(_:Exception){}
                    edit.commit()
                    OfflineCommentArchive.clearAccountAfterManualClear(app,account)
                    HookRuntime.count(FEATURE,"用户手动清空全部离线视频后清理同账号评论伴随缓存")
                }
            }
            result
        }

        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var validated: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && validated != network) {
                    validated = network
                    indexWorker.execute {
                        try { jobs.retry(reader.account(app)) } catch (_: Exception) { }
                        main.post { backfill(); drain(); pump() }
                    }
                } else if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && validated == network) validated = null
            }
            override fun onLost(network: Network) { if (validated == network) validated = null }
        }
        try { cm.registerDefaultNetworkCallback(callback) } catch (_: Exception) { }
        HookRuntime.onDispose(FEATURE) {
            closedFlag.set(true); enqueueVisible = null; progress.set(null); ingress.clear()
            main.removeCallbacks(sourceTick); main.removeCallbacks(translationTick); main.removeCallbacks(mediaTick); main.removeCallbacks(ingressTick)
            indexWorker.shutdownNow(); sourceWorker.shutdownNow(); translationWorker.shutdownNow(); mediaWorker.shutdownNow()
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) { }
        }
        indexWorker.execute {
            readyFlag.set(OfflineCommentDownloadContract.verify(app))
            publish()
            main.post {
                if (ready()) { drain(); backfill(); pump() }
                else HookRuntime.state(FEATURE, "下载回调代码不匹配，未自动联网")
            }
        }
        var signature: List<Any>? = null
        HookRuntime.subscribe(FEATURE) { snapshot -> if (snapshot != null) {
            val next = listOf(snapshot.boolean(KEY), snapshot.boolean(BACKFILL), snapshot.boolean(PASSIVE), snapshot.string(OfflineCommentPreferences.LIMIT, "2500") ?: "2500",
                OfflineCommentPreferences.replies(snapshot.string(OfflineCommentPreferences.REPLY_LIMIT)), OfflineCommentPreferences.threadReplies(snapshot.string(OfflineCommentPreferences.THREAD_LIMIT)))
            if (next != signature) { signature = next; if (snapshot.boolean(KEY)) backfill() }
            if (snapshot.boolean(KEY)) { drain(); pump() }
            else { ingressGeneration.incrementAndGet(); ingress.clear() }
            HookRuntime.appliedConfiguration(FEATURE, snapshot)
        } }
    }
}
