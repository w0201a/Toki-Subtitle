package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** 已选字幕文件优先读取私有离线存档；切换视频使迟到结果失效。 */
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
    private val remoteResume = SubtitleRemoteResume()
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
        val networkResumed = remoteResume.observe(android.os.SystemClock.uptimeMillis(), TranslationNetworkPolicy::remoteAvailable)
        if (video != id || identityFor(url) != identityFor(trackUrl) || networkResumed && cues.isEmpty()) {
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
                val remote = TranslationNetworkPolicy.capture()
                worker.execute {
                    if (closed || token != generation) return@execute
                    val parsed = try { loadCues(trackUrl, generation, remote) } catch (error: Exception) {
                        if (token == generation) {
                            HookRuntime.event("CaptionPrefetch", "current fetch failed=${error.javaClass.simpleName}")
                            if (!remote.permitsNetwork()) main.post {
                                if (!closed && token == generation) TranslationNetworkPolicy.showMissingCaption()
                            }
                        }
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
        } else {
            url = trackUrl // Signed URL refresh must not cancel a valid timeline or batch.
            if (changed) queue()
        }
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
        upcoming.forEach { track -> if (track.url.isNotBlank()) noteCandidate(track.video, track.url) }
        upcoming = upcoming.map { candidate -> candidate.copy(url = track(candidate.video) ?: candidate.url) }
        val generation = token
        upcoming.forEach { track ->
            if (track.url.isBlank()) return@forEach
            if ((retryDownloads[track.url] ?: 0L) > android.os.SystemClock.uptimeMillis()) return@forEach
            if (track.url in timelines || !attempted.add(track.url) || !loading.add(track.url)) return@forEach
            val remote = TranslationNetworkPolicy.capture()
            worker.execute {
                if (closed || token != generation) return@execute
                val parsed = try { loadCues(track.url, generation, remote) } catch (error: Exception) {
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
    private fun loadCues(location: String, generation: Long, remote: LocalOnlyRequest): List<CaptionTimeline.Cue> {
        check(!closed && token == generation)
        val identity = identityFor(location)
        CaptionTimelineDiskCache.read(identity)?.let { stored ->
            val parsed = CaptionTimeline.parse(stored)
            if (parsed.isNotEmpty()) return parsed
        }
        val body = read(location, generation, remote)
        val parsed = CaptionTimeline.parse(body)
        if (parsed.isNotEmpty() && !closed && token == generation) CaptionTimelineDiskCache.save(identity, body)
        return parsed
    }

    private fun read(location: String, generation: Long, remote: LocalOnlyRequest): String {
        var next = location
        repeat(4) {
            check(!closed && token == generation)
            val address = URL(next)
            require(address.protocol == "https")
            val host = address.host.lowercase()
            require(domains.any { host == it || host.endsWith(".$it") })
            check(!closed && token == generation && remote.permitsNetwork()) { "Local-only caption file unavailable" }
            val request = address.openConnection() as HttpURLConnection
            connections.add(request)
            request.connectTimeout = 5000; request.readTimeout = 5000; request.instanceFollowRedirects = false
            try {
                check(!closed && token == generation && remote.permitsNetwork()) { "Caption network access revoked" }
                val code = request.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    next = URL(address, request.getHeaderField("Location") ?: error("redirect")).toString()
                } else {
                    require(code == 200 && request.contentLengthLong <= 1_048_576)
                    val bytes = request.inputStream.use { input ->
                        java.io.ByteArrayOutputStream().use { output ->
                            val buffer = ByteArray(8192)
                            while (true) {
                                check(!closed && token == generation && remote.permitsNetwork())
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
        private val trackIdentities = LinkedHashMap<String, String>()
        private const val PREF_NAME = "toki_subtitle_tracks"
        private const val KEY_JSON = "tracks"
        private var persisted = false

        private fun trackPrefs(): android.content.SharedPreferences? =
            HookRuntime.hostContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        @Synchronized fun loadTracks() {
            if (persisted) return
            try {
                val preferences = trackPrefs() ?: return
                persisted = true
                val raw = preferences.getString(KEY_JSON, null) ?: return
                if (raw.length > 2 * 1024 * 1024) return
                val decoded = String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
                val rows = decoded.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
                    val sep = line.indexOf('\u0001')
                    if (sep <= 0) null else line.substring(0, sep) to line.substring(sep + 1)
                }.take(128).toMap()
                rows.forEach { (video, location) ->
                    if (video.isNotBlank() && video.length <= 256 && location.length <= 8192 && video !in tracks) {
                        tracks[video] = location
                        trackIdentities[video] = trackFingerprint(location)
                    }
                }
                while (tracks.size > 128) {
                    val oldest = tracks.keys.first()
                    tracks.remove(oldest)
                    trackIdentities.remove(oldest)
                }
                persisted = true
                HookRuntime.event("CaptionPrefetch", "track identities restored=${rows.size}")
            } catch (error: Exception) {
                HookRuntime.event("CaptionPrefetch", "track restore unavailable type=" + error.javaClass.simpleName)
            }
        }

        @Synchronized private fun persistTracks() {
            try {
                val preferences = trackPrefs() ?: return
                val text = buildString {
                    tracks.forEach { (video, location) -> append(video).append('\u0001').append(location).append('\n') }
                }
                val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                preferences.edit().putString(KEY_JSON, encoded).apply()
            } catch (error: Exception) {
                HookRuntime.event("CaptionPrefetch", "track write unavailable type=" + error.javaClass.simpleName)
            }
        }
        @Synchronized fun noteCandidate(video: String, location: String?) {
            loadTracks()
            // Playback selection is authoritative; a stale feed model cannot flip it back.
            if (video !in tracks) note(video, location)
        }
        private fun identityFor(location: String): String = if (location.isBlank()) "" else trackFingerprint(location)
        @Synchronized fun note(video: String, location: String?) {
            loadTracks()
            if (!location.isNullOrBlank()) {
                if (tracks[video] == location) return
                tracks[video] = location
                trackIdentities[video] = trackFingerprint(location)
            } else {
                tracks.remove(video)
                trackIdentities.remove(video)
            }
            while (tracks.size > 128) {
                val oldest = tracks.keys.first()
                tracks.remove(oldest)
                trackIdentities.remove(oldest)
            }
            persistTracks()
        }
        @Synchronized private fun track(video: String) = tracks[video]
        @Synchronized fun identity(video: String): String = trackIdentities[video].orEmpty()

        /** Only the stable digest partitions translations; signed URLs are kept in host-private track preferences. */
        internal fun trackFingerprint(location: String): String {
            val stable = runCatching {
                val uri = java.net.URI(location).normalize()
                val volatile = setOf("ts", "_rticket", "timestamp", "expires", "expire", "x-expires",
                    "signature", "x-signature", "sign", "sig", "token", "auth", "auth_key",
                    "authorization", "x-bogus", "x-tt-params", "x-tt-token")
                val query = uri.rawQuery.orEmpty().split('&').filter { pair ->
                    pair.substringBefore('=').lowercase() !in volatile
                }.sorted().joinToString("&")
                "${uri.scheme?.lowercase()}://${uri.rawAuthority?.lowercase()}${uri.rawPath}" +
                    if (query.isBlank()) "" else "?$query"
            }.getOrDefault(location)
            val bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(stable.toByteArray(Charsets.UTF_8)).take(12)
            val digits = "0123456789abcdef"
            return buildString(24) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(digits[value ushr 4]); append(digits[value and 0x0f])
                }
            }
        }
    }
}
