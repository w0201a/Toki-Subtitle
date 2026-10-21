package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.meiyongai.toki.subtitle.provider.ConfigClient
import java.lang.reflect.Proxy
import java.util.Locale

/** 当前句与预读各一条请求通道，按 100 个视频保存译文；主线程持有状态。 */
internal class NativeSubtitleTranslator(
    private val enabled: (String) -> Boolean,
    private val translate: (String, (String?) -> Unit) -> AutoCloseable,
    private val report: (String) -> Unit = {},
) : AutoCloseable {
    private class Job(val text: String, var current: () -> Boolean, var deliver: (String) -> Unit,
        var foreground: Boolean = true, val video: String = "") {
        val key = video to CaptionTimeline.normalize(text)
        var subscription: AutoCloseable? = null
        var retries = 0
        var retryAt = 0L
        var startedAt = 0L
        var timeout: Runnable? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private val cache = VideoCaptionCache(100)
    private var pending: Job? = null
    private var active: Job? = null
    private var background: Job? = null
    private val ahead = ArrayDeque<Job>()
    private val trackPrefetch = CaptionPrefetch(this)
    var upcomingTracks: (String, Int) -> List<FeedCaptionLookAhead.Track> = { _, _ -> emptyList() }
    private var nextRequestAt = 0L
    private var nextForegroundAt = 0L
    private var prefetchBackoffAt = 0L
    private var failures = 0
    private var closed = false
    private val nativeFrames = LinkedHashMap<String, Long>()
    private val pump = Runnable { startNext() }

    fun isEnabled(videoId: String): Boolean = !closed && videoId.isNotBlank() && try {
        enabled(videoId) && !isChinese(trackLanguage(videoId))
    } catch (_: Exception) { false }
    fun canPrefetch(videoId: String): Boolean = !closed && videoId.isNotBlank() &&
        try { enabled(videoId) } catch (_: Exception) { false }

    fun request(text: String, current: () -> Boolean, deliver: (String) -> Unit) {
        request("", text, current, deliver)
    }

    fun request(videoId: String, text: String, current: () -> Boolean, deliver: (String) -> Unit) {
        if (closed || text.length !in 1..1000 || !text.any(Char::isLetter) || !current()) return
        val key = videoId to CaptionTimeline.normalize(text)
        cache[videoId, text]?.let { pending = null; report("cache-hit videos=${cache.size}"); deliver(it); return }
        // 先按正文复用，再检查旧代次；同句重现不能取消已经发出的同一请求。
        listOfNotNull(active, background).firstOrNull { it.key == key }?.let {
            it.current = current
            it.deliver = deliver
            it.foreground = true
            pending = null
            report("reuse lane=${if (it === active) "current" else "prefetch"}")
            return
        }
        if (active?.current?.invoke() == false) releaseActive()
        // 后来的当前帧替换尚未发送的旧帧，不积累视频/字幕队列。
        pending = Job(text, current, deliver, video = videoId)
        main.removeCallbacks(pump)
        // 当前句立即排程；同一主线程周期内仍合并为最新句。
        main.post(pump)
    }

    fun readAhead(videoId: String, text: String, current: () -> Boolean) {
        if (canPrefetch(videoId)) trackPrefetch.update(videoId, text, current)
    }

    internal fun prefetch(texts: List<String>, current: () -> Boolean) {
        prefetchVideos(texts.take(32).map { CaptionTranslationInput("", it) }, current)
    }

    internal fun prefetchVideos(texts: List<CaptionTranslationInput>, current: () -> Boolean) {
        ahead.clear()
        val allowed = texts.map { it.video }.distinct().associateWith { it.isBlank() || isEnabled(it) }
        texts.asSequence().filter { it.text.length in 1..1000 && it.text.any(Char::isLetter) &&
                allowed[it.video] == true }
            .map { Job(it.text, current, {}, foreground = false, video = it.video) }
            .filter { cache[it.video, it.text] == null && it.key != active?.key && it.key != background?.key }
            .distinctBy { it.key }.forEach { ahead += it }
        main.removeCallbacks(pump)
        main.post(pump)
    }

    private fun startNext() {
        if (closed) return
        if (pending?.current?.invoke() == false) pending = null
        while (ahead.firstOrNull()?.let { !it.current() || it.key == active?.key || it.key == background?.key } == true)
            ahead.removeFirst()
        // 两条有界通道：当前句不排在预读后面，也不取消仍能用于缓存的预读。
        val foreground = active == null && pending != null
        val job = if (foreground) checkNotNull(pending)
            else if (background == null && pending == null) ahead.removeFirstOrNull() ?: return
            else return
        if (!job.current()) { if (foreground) pending = null; main.post(pump); return }
        cache[job.video, job.text]?.let {
            if (foreground) pending = null
            if (job.current()) job.deliver(it)
            main.post(pump)
            return
        }
        // Prefetch pacing must not reserve the foreground lane. Foreground
        // requests keep their own 250ms bound, while prefetch yields to both.
        val wait = maxOf(if (foreground) nextForegroundAt else nextRequestAt,
            if (foreground) job.retryAt else prefetchBackoffAt) - SystemClock.uptimeMillis()
        if (wait > 0) {
            if (!foreground) ahead.addFirst(job)
            main.postDelayed(pump, wait)
            return
        }
        if (foreground) { pending = null; active = job } else background = job
        job.startedAt = SystemClock.uptimeMillis()
        nextRequestAt = job.startedAt + 250
        if (foreground) nextForegroundAt = nextRequestAt
        job.timeout = Runnable { finish(job, null, "timeout") }.also { main.postDelayed(it, 6000) }
        report("request lane=${if (foreground) "current" else "prefetch"} chars=${job.text.length}")
        try {
            val subscription = translate(job.text) { result ->
                if (Looper.myLooper() == main.looper) finish(job, result, "empty-or-error")
                else main.post { finish(job, result, "empty-or-error") }
            }
            if (active === job || background === job) job.subscription = subscription else subscription.close()
            main.post(pump)
        } catch (error: Exception) {
            finish(job, null, error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName)
        }
    }

    private fun finish(job: Job, result: String?, failure: String) {
        if (closed || (active !== job && background !== job)) return
        job.timeout?.let(main::removeCallbacks)
        if (active === job) active = null else background = null
        try { job.subscription?.close() } catch (_: Exception) { }
        val translated = result?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4000 }
        if (translated != null) {
            failures = 0
            cache.put(job.video, job.text, translated)
            val current = job.current()
            report("success lane=${if (job.foreground) "current" else "prefetch"} chars=${translated.length} current=$current cachedVideos=${cache.size} elapsedMs=${SystemClock.uptimeMillis() - job.startedAt}")
            if (current) job.deliver(translated)
        } else {
            failures = (failures + 1).coerceAtMost(5)
            val delay = (1000L shl (failures - 1)).coerceAtMost(15000L)
            // 预读失败不能让当前字幕跟着等待最多 15 秒。
            if (job.foreground && job.current()) job.retryAt = SystemClock.uptimeMillis() + delay
            else prefetchBackoffAt = SystemClock.uptimeMillis() + delay
            // 短暂网络错误仅为仍在显示的当前句重试一次，不追逐旧句，也不自动重试预读句。
            if (job.foreground && job.retries == 0 && pending == null && job.current()) {
                job.retries++
                job.subscription = null
                pending = job
            }
            report("unavailable reason=$failure; backoffMs=$delay; original retained")
        }
        main.removeCallbacks(pump)
        main.post(pump)
    }

    fun cancel() {
        trackPrefetch.cancel()
        main.removeCallbacks(pump)
        pending = null
        ahead.clear()
        releaseActive()
        background?.let { job ->
            background = null
            job.timeout?.let(main::removeCallbacks)
            try { job.subscription?.close() } catch (_: Exception) { }
        }
    }

    private fun releaseActive() {
        active?.timeout?.let(main::removeCallbacks)
        val subscription = active?.subscription
        active = null
        try { subscription?.close() } catch (_: Exception) { }
    }

    override fun close() { closed = true; cancel(); trackPrefetch.close(); cache.clear() }

    internal fun completed(video: String, texts: List<String>) = cache.complete(video, texts)
    internal fun expect(video: String, texts: List<String>) = cache.expect(video, texts)
    internal val completedVideos: Int get() = cache.size
    internal val prefetchIdle: Boolean get() = background == null && ahead.isEmpty()
    internal fun cueAt(video: String, positionMs: Long) = trackPrefetch.cueAt(video, positionMs)
    internal fun noteNativeFrame(video: String) {
        if (video.isBlank()) return
        nativeFrames[video] = SystemClock.uptimeMillis()
        while (nativeFrames.size > 100) nativeFrames.remove(nativeFrames.keys.first())
    }
    internal fun hasNativeFrame(video: String): Boolean = nativeFrames[video]?.let {
        SystemClock.uptimeMillis() - it < 2000
    } == true
    internal fun selectVideo(video: String) {
        val released = cache.select(video)
        if (released > 0) report("watched batch released=$released completedCache=${cache.size}/100")
    }

    companion object {
        // 来自播放器实际选择结果，仅用于避免重复翻译已选中的中文轨。不保留字幕正文。
        private val trackLanguages = object : LinkedHashMap<String, String>(256, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 128
        }
        @Synchronized fun noteTrack(videoId: String, language: String?) {
            if (language == null) trackLanguages.remove(videoId) else trackLanguages[videoId] = language
        }
        @Synchronized private fun trackLanguage(videoId: String): String? = trackLanguages[videoId]
        private fun isChinese(language: String?): Boolean = language?.lowercase(Locale.ROOT)
            ?.replace('_', '-')?.let { it == "zh" || it.startsWith("zh-") } == true

        fun connect(loader: ClassLoader): NativeSubtitleTranslator {
            val facade = loader.adaptiveClass("X.03hV")
            val service = facade.adaptiveDeclaredField("LIZIZ").get(null)
            val override = facade.adaptiveDeclaredMethod("LJL", String::class.java)
            val automaticPreference = runCatching {
                loader.adaptiveClass("X.08aF").adaptiveDeclaredMethod("LIZ") to
                    loader.adaptiveClass("com.ss.android.ugc.aweme.translation.service.ITranslationKevaService").adaptiveMethod("LIZ")
            }.getOrNull()
            if (automaticPreference == null) HookRuntime.event("NativeSubtitleTranslation", "optional automatic preference unavailable; module switch and per-video override remain active")
            return NativeSubtitleTranslator(enabled = { videoId ->
                ConfigClient.getBoolean("video_translate_enabled") &&
                    when ((override.invoke(service, videoId) as? Enum<*>)?.name) {
                        "OVERRIDE_TO_ORIGINAL" -> false
                        "OVERRIDE_TO_TRANSLATED" -> true
                        else -> automaticPreference?.let { (holder, automatic) -> automatic.invoke(holder.invoke(null)) == true } ?: true
                    }
            }, translate = transport(loader), report = { HookRuntime.event("NativeSubtitleTranslation", it) })
        }

        /** 独立请求通道，供评论投票使用；不进入视频字幕的排程或预读队列。 */
        fun transport(loader: ClassLoader): (String, (String?) -> Unit) -> AutoCloseable {
            val facade = loader.adaptiveClass("X.03hV")
            val service = facade.adaptiveDeclaredField("LIZIZ").get(null)
            val method = facade.adaptiveDeclaredMethod("LJIIJ", String::class.java)
            val consumer = loader.adaptiveClass("X.02u8")
            check(consumer.isInterface && consumer.adaptiveMethod("accept", Any::class.java).returnType == Void.TYPE)
            val disposable = loader.adaptiveClass("X.02tV")
            val dispose = disposable.adaptiveMethod("dispose")
            val subscribe = loader.adaptiveClass("X.02tQ").adaptiveMethod("LJJZ", consumer, consumer)
            check(disposable.isAssignableFrom(subscribe.returnType))
            val result = loader.adaptiveClass("com.ss.android.ugc.aweme.translation.model.TranslationResult")
            val content = result.adaptiveField("translatedContent")
            check(content.type == String::class.java)
            fun callback(action: (Any?) -> Unit): Any = Proxy.newProxyInstance(loader, arrayOf(consumer)) { proxy, invoked, args ->
                when (invoked.name) {
                    "accept" -> { action(args?.firstOrNull()); null }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "TokiSubtitleConsumer"
                    else -> null
                }
            }
            return { text, complete ->
                val observable = checkNotNull(method.invoke(service, text))
                val subscription = subscribe.invoke(observable,
                    callback { value -> complete(if (result.isInstance(value)) content.get(value) as? String else null) },
                    callback { error ->
                        HookRuntime.event("NativeSubtitleTranslation", "host-error type=${error?.javaClass?.simpleName}")
                        complete(null)
                    })
                AutoCloseable { if (subscription != null) dispose.invoke(subscription) }
            }
        }
    }
}
