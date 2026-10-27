package io.github.meiyongai.toki.subtitle.hook

/** Completed videos stay until viewing advances two batches; reading never changes retention. */
internal class VideoCaptionCache(private val capacity: Int = 100) {
    private class Video(val track: String, val values: MutableMap<String, String> = linkedMapOf(), var expected: Set<String>? = null) {
        val complete: Boolean get() = expected?.let { it.isNotEmpty() && values.keys.containsAll(it) } ?: false
    }
    private val videos = LinkedHashMap<String, Video>(capacity)
    private val pending = LinkedHashMap<String, Video>(6, .75f, true)
    private val watched = ArrayDeque<String>()
    private val counted = mutableSetOf<String>()
    private val expired = mutableSetOf<String>()
    private val activeTracks = LinkedHashMap<String, String>()
    private var selected = ""
    private var hits = 0
    private var misses = 0
    private var trackMismatches = 0
    init { require(capacity > 0) }
    /** Selection, rather than background cache access, advances the watched-video window. */
    fun select(video: String): Int {
        if (video.isBlank() || video == selected) return 0
        val previous = selected
        selected = video
        var released = 0
        if (previous.isNotBlank() && counted.add(previous)) {
            watched.addLast(previous)
            if (watched.size >= 10) repeat(5) {
                val old = watched.removeFirst()
                // A backwards selection must keep the newly selected video's complete subtitles.
                if (old != selected) {
                    expired.add(old)
                    if (videos.remove(old) != null) released++
                    pending.remove(old)
                    activeTracks.remove(old)
                }
            }
        }
        if (expired.remove(video)) counted.remove(video)
        return released
    }
    operator fun get(video: String, text: String, track: String = ""): String? {
        val entry = videos[video] ?: pending[video]
        if (entry == null) { misses++; return null }
        if (entry.track != track) { misses++; trackMismatches++; return null }
        return entry.values[CaptionTimeline.normalize(text)].also { if (it == null) misses++ else hits++ }
    }
    fun selectTrack(video: String, track: String = "") {
        val previous = activeTracks.put(video, track)
        if (previous != null && previous != track) {
            trackMismatches++
            videos.remove(video)
            pending.remove(video)
        }
        while (activeTracks.size > capacity + 6) {
            val old = activeTracks.keys.firstOrNull { it != selected && it !in videos && it !in pending } ?: break
            activeTracks.remove(old)
        }
    }
    fun expect(video: String, texts: List<String>, track: String = "") {
        selectTrack(video, track)
        val expected = texts.filter { it.length in 1..1000 && it.any(Char::isLetter) }.map(CaptionTimeline::normalize).toSet()
        if (expected.isEmpty()) return
        val previous = videos[video] ?: pending[video]
        if (previous?.track == track && previous.expected == expected) return
        val entry = if (previous?.track == track) videos.remove(video) ?: pending[video] ?: Video(track)
            else { videos.remove(video); pending.remove(video); Video(track) }
        entry.expected = expected
        retain(video, entry)
    }
    fun put(video: String, text: String, translated: String, track: String = "") {
        if (video in expired && video != selected) return
        if (activeTracks[video] != track) return
        val previous = videos[video] ?: pending[video]
        val entry = if (previous?.track == track) previous else {
            videos.remove(video); pending.remove(video); Video(track)
        }
        entry.values[CaptionTimeline.normalize(text)] = translated
        retain(video, entry)
    }
    private fun retain(video: String, entry: Video) {
        if (entry.complete && (video in videos || videos.size < capacity)) {
            pending.remove(video); videos[video] = entry
        }
        else { videos.remove(video); pending[video] = entry }
        while (pending.size > 6) {
            val old = pending.keys.firstOrNull { it != selected } ?: break
            pending.remove(old)
        }
    }
    fun complete(video: String, texts: List<String>, track: String = ""): Boolean {
        if (texts.isEmpty()) return false
        val required = texts.filter { it.length in 1..1000 && it.any(Char::isLetter) }
        if (required.isEmpty()) return true
        return (videos[video] ?: pending[video])?.let { entry ->
            entry.track == track && required.all { CaptionTimeline.normalize(it) in entry.values }
        } == true
    }
    val size: Int get() = videos.size
    val partialSize: Int get() = pending.size
    fun diagnostic(): String = "completed=$size partial=$partialSize hits=$hits misses=$misses trackMismatches=$trackMismatches capacity=$capacity"
    fun clear() {
        videos.clear(); pending.clear(); watched.clear(); counted.clear(); expired.clear(); activeTracks.clear(); selected = ""
        hits = 0; misses = 0; trackMismatches = 0
    }
}

internal data class CaptionTranslationInput(val video: String, val text: String)
