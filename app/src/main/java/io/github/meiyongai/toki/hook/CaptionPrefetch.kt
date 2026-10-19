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
    // 当前文件加后两条文件的有界队列；切换视频清除尚未开始的旧任务。
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(3), ThreadPoolExecutor.DiscardOldestPolicy())
    private var video = ""
    private var url = ""
    @Volatile private var token = 0L
    private var cues = emptyList<CaptionTimeline.Cue>()
    private var normalized = emptyList<String>()
    private var latestText = ""
    private var current: () -> Boolean = { false }
    private var index = -1
    private val timelines = object : LinkedHashMap<String, List<CaptionTimeline.Cue>>(4, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CaptionTimeline.Cue>>?) = size > 4
    }
    private var upcoming = emptyList<FeedCaptionLookAhead.Track>()
    private var checkedAheadAt = 0L
    private val loading = mutableSetOf<String>()
    private val attempted = mutableSetOf<String>()
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null

    fun update(id: String, text: String, valid: () -> Boolean) {
        if (closed) return
        val trackUrl = track(id) ?: run { if (video != id) cancel(); return }
        val changed = latestText != text
        latestText = text; current = valid
        if (video != id || url != trackUrl) {
            video = id; url = trackUrl; cues = emptyList(); normalized = emptyList(); index = -1
            val generation = ++token
            connection?.disconnect()
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
        upcoming = try { translator.upcomingTracks(video).take(2) } catch (_: Exception) { emptyList() }
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
        if (cues.isEmpty() || !current()) return
        val text = CaptionTimeline.normalize(latestText)
        // 匹配当前帧后只预取后续六句；重复台词优先匹配当前位置附近。
        val found = (maxOf(0, index)..minOf(cues.lastIndex, index + 12)).firstOrNull { normalized[it] == text }
            ?: normalized.indexOf(text).takeIf { it >= 0 }
            ?: return
        index = found
        val generation = token
        val futureTexts = upcoming.flatMap { timelines[it.url].orEmpty().take(3) }.map { it.text }
        translator.prefetch(cues.subList(found, minOf(cues.size, found + 7)).map { it.text } + futureTexts) {
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
            connection = request
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
            } finally { request.disconnect(); connection = null }
        }
        error("redirect limit")
    }
    fun cancel() {
        token++; connection?.disconnect(); worker.queue.clear()
        video = ""; url = ""; latestText = ""; index = -1
        cues = emptyList(); normalized = emptyList(); current = { false }
        upcoming = emptyList(); loading.clear(); attempted.clear(); checkedAheadAt = 0L
    }
    override fun close() { closed = true; cancel(); worker.shutdown(); timelines.clear() }

    companion object {
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
