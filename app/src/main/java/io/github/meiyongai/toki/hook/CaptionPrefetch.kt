package io.github.meiyongai.toki.subtitle.hook

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 已选字幕文件的只读预取；正文只在内存保存，切换视频使迟到结果失效。 */
internal class CaptionPrefetch(private val translator: NativeSubtitleTranslator) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    // 当前文件加后五条文件的有界队列；切换视频清除尚未开始的旧任务。
    private val worker = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(6), ThreadPoolExecutor.DiscardOldestPolicy())
    private var video = ""
    private var url = ""
    @Volatile private var token = 0L
    private var cues = emptyList<CaptionTimeline.Cue>()
    private var normalized = emptyList<String>()
    private var latestText = ""
    private var current: () -> Boolean = { false }
    private var index = -1
    private val timelines = object : LinkedHashMap<String, List<CaptionTimeline.Cue>>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CaptionTimeline.Cue>>?) = size > 100
    }
    private var upcoming = emptyList<FeedCaptionLookAhead.Track>()
    private val batches = CaptionBatchCursor()
    private val retryDownloads = mutableMapOf<String, Long>()
    private var checkedAheadAt = 0L
    private var queuedPlan: List<Any?>? = null
    private val loading = mutableSetOf<String>()
    private val attempted = mutableSetOf<String>()
    @Volatile private var closed = false
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private val refreshAhead = object : Runnable {
        override fun run() {
            if (closed || !current() || !translator.canPrefetch(video)) return
            warmUpcoming()
            main.postDelayed(this, 1500)
        }
    }
    private fun disconnectAll() { connections.toList().forEach { it.disconnect() }; connections.clear() }
    fun cueAt(id: String, positionMs: Long): String? =
        if (id == video) CaptionTimeline.at(cues, positionMs) else null

    fun update(id: String, text: String, valid: () -> Boolean) {
        if (closed) return
        val trackUrl = track(id).orEmpty()
        val changed = latestText != text
        latestText = text; current = valid
        if (video != id || url != trackUrl) {
            video = id; url = trackUrl; cues = emptyList(); normalized = emptyList(); index = -1
            val generation = ++token
            disconnectAll()
            worker.queue.clear()
            loading.clear()
            attempted.clear()
            upcoming = emptyList()
            batches.reset()
            retryDownloads.clear()
            checkedAheadAt = 0L
            timelines[trackUrl]?.let {
                cues = it; normalized = it.map { cue -> CaptionTimeline.normalize(cue.text) }
                queue()
            }
            if (cues.isEmpty() && trackUrl.isNotBlank()) {
                worker.execute {
                    if (closed || token != generation) return@execute
                    val parsed = try { CaptionTimeline.parse(read(trackUrl, generation)) } catch (error: Exception) {
                        if (token == generation) HookRuntime.event("CaptionPrefetch", "current fetch failed=${error.javaClass.simpleName}")
                        emptyList()
                    }
                    val keys = parsed.map { CaptionTimeline.normalize(it.text) }
                    main.post {
                        if (!closed && token == generation) {
                            cues = parsed
                            normalized = keys
                            if (parsed.isNotEmpty()) timelines[trackUrl] = parsed
                            HookRuntime.event("CaptionPrefetch", "timeline cues=${parsed.size}")
                            queue()
                        }
                    }
                }
            }
        } else if (changed) queue()
        val now = android.os.SystemClock.uptimeMillis()
        if (now >= checkedAheadAt) {
            checkedAheadAt = now + 1500
            warmUpcoming()
        }
        main.removeCallbacks(refreshAhead)
        main.postDelayed(refreshAhead, 1500)
    }
    private fun warmUpcoming() {
        if (!current()) return
        if (upcoming.isNotEmpty() && upcoming.all { track ->
                track.url.isBlank() || timelines[track.url]?.let { cues -> translator.completed(track.video, cues.map { it.text }) } == true
            }) {
            HookRuntime.event("CaptionPrefetch", "batch complete videos=${upcoming.size} completedCache=${translator.completedVideos}/100")
            batches.complete(upcoming.size, translator.completedVideos)
            upcoming = emptyList()
            queuedPlan = null
        }
        // A full cache stays intact while the user watches the first batch.
        // Only selectVideo releases an older watched batch and creates refill space.
        if (translator.completedVideos >= 100) return
        upcoming = try { translator.upcomingTracks(video, batches.offset).take(5) } catch (_: Exception) { emptyList() }
        val generation = token
        upcoming.forEach { track ->
            if (track.url.isBlank()) return@forEach
            if ((retryDownloads[track.url] ?: 0L) > android.os.SystemClock.uptimeMillis()) return@forEach
            if (track.url in timelines || !attempted.add(track.url) || !loading.add(track.url)) return@forEach
            worker.execute {
                if (closed || token != generation) return@execute
                val parsed = try { CaptionTimeline.parse(read(track.url, generation)) } catch (error: Exception) {
                    if (token == generation) HookRuntime.event("CaptionPrefetch", "upcoming fetch failed=${error.javaClass.simpleName}")
                    emptyList()
                }
                main.post {
                    if (!closed && token == generation) {
                        loading.remove(track.url)
                        if (parsed.isNotEmpty()) timelines[track.url] = parsed
                        else { attempted.remove(track.url); retryDownloads[track.url] = android.os.SystemClock.uptimeMillis() + 15_000 }
                        HookRuntime.event("CaptionPrefetch", "upcoming timeline cues=${parsed.size}")
                        queue()
                    }
                }
            }
        }
        queue()
    }
    private fun queue() {
        if (!current()) return
        val text = CaptionTimeline.normalize(latestText)
        val found = (maxOf(0, index)..minOf(cues.lastIndex, index + 12)).firstOrNull { normalized[it] == text }
            ?: normalized.indexOf(text).takeIf { it >= 0 }
        if (found != null) index = found
        val generation = token
        val planKey = listOf(generation, found, cues.size, upcoming.map { Triple(it.video, it.url, timelines[it.url]?.size) })
        if (queuedPlan == planKey && !translator.prefetchIdle) return
        queuedPlan = planKey
        val currentTexts = cues.subList(found ?: 0, cues.size).map { it.text }
        translator.expect(video, cues.map { it.text })
        val refill = if (translator.completedVideos < 100) upcoming else emptyList()
        for (track in refill) translator.expect(track.video, timelines[track.url].orEmpty().map { it.text })
        val planned = planVideos(video, currentTexts, refill.map { track ->
            track.video to timelines[track.url].orEmpty().map { cue -> cue.text }
        })
        translator.prefetchVideos(planned) {
            !closed && token == generation && current() && translator.canPrefetch(video)
        }
    }
    private fun read(location: String, generation: Long): String {
        var next = location
        repeat(4) {
            check(!closed && token == generation)
            val address = URL(next)
            require(address.protocol == "https")
            val host = address.host.lowercase()
            require(domains.any { host == it || host.endsWith(".$it") })
            val request = address.openConnection() as HttpURLConnection
            connections.add(request)
            request.connectTimeout = 5000; request.readTimeout = 5000; request.instanceFollowRedirects = false
            try {
                val code = request.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    next = URL(address, request.getHeaderField("Location") ?: error("redirect")).toString()
                } else {
                    require(code == 200 && request.contentLengthLong <= 1_048_576)
                    val bytes = request.inputStream.use { input ->
                        java.io.ByteArrayOutputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                check(!closed && token == generation)
                                val size = input.read(buffer); if (size < 0) break
                                require(output.size() + size <= 1_048_576)
                                output.write(buffer, 0, size)
                            }
                            output.toByteArray()
                        }
                    }
                    require(bytes.size <= 1_048_576)
                    return bytes.toString(Charsets.UTF_8)
                }
            } finally { request.disconnect(); connections.remove(request) }
        }
        error("redirect limit")
    }
    fun cancel() {
        main.removeCallbacks(refreshAhead)
        token++; disconnectAll(); worker.queue.clear()
        video = ""; url = ""; latestText = ""; index = -1
        cues = emptyList(); normalized = emptyList(); current = { false }
        upcoming = emptyList(); loading.clear(); attempted.clear(); checkedAheadAt = 0L
        batches.reset(); retryDownloads.clear()
        queuedPlan = null
    }
    override fun close() { closed = true; cancel(); worker.shutdown(); timelines.clear() }

    companion object {
        internal fun plan(current: List<String>, upcoming: List<List<String>>): List<String> {
            return planVideos("current", current, upcoming.mapIndexed { index, texts -> "next$index" to texts })
                .map { it.text }.distinct()
        }
        internal fun planVideos(video: String, current: List<String>, upcoming: List<Pair<String, List<String>>>): List<CaptionTranslationInput> {
            val bounded = upcoming.take(5)
            val future = (0 until (bounded.maxOfOrNull { it.second.size } ?: 0)).flatMap { position ->
                bounded.mapNotNull { (id, texts) -> texts.getOrNull(position)?.let { CaptionTranslationInput(id, it) } }
            }
            return (current.take(3).map { CaptionTranslationInput(video, it) } + future +
                current.drop(3).map { CaptionTranslationInput(video, it) }).distinct()
        }
        private val domains = setOf("tiktokcdn.com", "tiktokcdn-us.com", "tiktokcdn-eu.com", "tiktokv.com",
            "byteoversea.com", "ibytedtos.com", "muscdn.com", "tiktok.com", "ibyteimg.com")
        private val tracks = LinkedHashMap<String, String>()
        @Synchronized fun note(video: String, location: String?) {
            if (!location.isNullOrBlank()) tracks[video] = location else tracks.remove(video)
            while (tracks.size > 12) tracks.remove(tracks.keys.first())
        }
        @Synchronized private fun track(video: String) = tracks[video]
    }
}
