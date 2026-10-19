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
    // 当前文件加后四条文件的有界队列；切换视频清除尚未开始的旧任务。
    private val worker = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(5), ThreadPoolExecutor.DiscardOldestPolicy())
    private var video = ""
    private var url = ""
    @Volatile private var token = 0L
    private var cues = emptyList<CaptionTimeline.Cue>()
    private var normalized = emptyList<String>()
    private var latestText = ""
    private var current: () -> Boolean = { false }
    private var index = -1
    private val timelines = object : LinkedHashMap<String, List<CaptionTimeline.Cue>>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CaptionTimeline.Cue>>?) = size > 6
    }
    private var upcoming = emptyList<FeedCaptionLookAhead.Track>()
    private var checkedAheadAt = 0L
    private val loading = mutableSetOf<String>()
    private val attempted = mutableSetOf<String>()
    @Volatile private var closed = false
    private val connections = java.util.concurrent.ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private fun disconnectAll() { connections.toList().forEach { it.disconnect() }; connections.clear() }

    fun update(id: String, text: String, valid: () -> Boolean) {
        if (closed) return
        val trackUrl = track(id) ?: run { if (video != id) cancel(); return }
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
            checkedAheadAt = 0L
            timelines[trackUrl]?.let {
                cues = it; normalized = it.map { cue -> CaptionTimeline.normalize(cue.text) }
                queue()
            }
            if (cues.isEmpty()) {
                worker.execute {
                    if (closed || token != generation) return@execute
                    val parsed = try { CaptionTimeline.parse(read(trackUrl, generation)) } catch (_: Exception) { emptyList() }
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
    }
    private fun warmUpcoming() {
        if (!current()) return
        upcoming = try { translator.upcomingTracks(video).take(4) } catch (_: Exception) { emptyList() }
        val generation = token
        upcoming.forEach { track ->
            if (track.url in timelines || !attempted.add(track.url) || !loading.add(track.url)) return@forEach
            worker.execute {
                if (closed || token != generation) return@execute
                val parsed = try { CaptionTimeline.parse(read(track.url, generation)) } catch (_: Exception) { emptyList() }
                main.post {
                    if (!closed && token == generation) {
                        loading.remove(track.url)
                        if (parsed.isNotEmpty()) timelines[track.url] = parsed
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
        val currentTexts = if (found == null) emptyList() else cues.subList(found, minOf(cues.size, found + 7)).map { it.text }
        val planned = plan(currentTexts, upcoming.map { timelines[it.url].orEmpty().map { cue -> cue.text } })
        translator.prefetch(planned) {
            !closed && token == generation && current() && translator.isEnabled(video)
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
        token++; disconnectAll(); worker.queue.clear()
        video = ""; url = ""; latestText = ""; index = -1
        cues = emptyList(); normalized = emptyList(); current = { false }
        upcoming = emptyList(); loading.clear(); attempted.clear(); checkedAheadAt = 0L
    }
    override fun close() { closed = true; cancel(); worker.shutdown(); timelines.clear() }

    companion object {
        internal fun plan(current: List<String>, upcoming: List<List<String>>): List<String> {
            val bounded = upcoming.take(4)
            val openings = (0 until 6).flatMap { position -> bounded.mapNotNull { it.getOrNull(position) } }
            return (current.take(3) + openings + current.drop(3).take(4)).distinct().take(32)
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
