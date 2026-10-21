package io.github.meiyongai.toki.subtitle.hook

/** Completed videos stay until viewing advances two batches; reading never changes retention. */
internal class VideoCaptionCache(private val capacity: Int = 100) {
    private class Video(val values: MutableMap<String, String> = linkedMapOf(), var expected: Set<String>? = null) {
        val complete: Boolean get() = expected?.let { it.isNotEmpty() && values.keys.containsAll(it) } ?: false
    }
    private val videos = LinkedHashMap<String, Video>(capacity)
    private val pending = LinkedHashMap<String, Video>(6, .75f, true)
    private val watched = ArrayDeque<String>()
    private val counted = mutableSetOf<String>()
    private val expired = mutableSetOf<String>()
    private var selected = ""
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
                }
            }
        }
        if (expired.remove(video)) counted.remove(video)
        return released
    }
    operator fun get(video: String, text: String): String? =
        (videos[video] ?: pending[video])?.values?.get(CaptionTimeline.normalize(text))
    fun expect(video: String, texts: List<String>) {
        val expected = texts.filter { it.length in 1..1000 && it.any(Char::isLetter) }.map(CaptionTimeline::normalize).toSet()
        if (expected.isEmpty()) return
        if ((videos[video] ?: pending[video])?.expected == expected) return
        val entry = videos.remove(video) ?: pending[video] ?: Video()
        entry.expected = expected
        retain(video, entry)
    }
    fun put(video: String, text: String, translated: String) {
        if (video in expired && video != selected) return
        val entry = videos[video] ?: pending[video] ?: Video()
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
    fun complete(video: String, texts: List<String>): Boolean {
        if (texts.isEmpty()) return false
        val required = texts.filter { it.length in 1..1000 && it.any(Char::isLetter) }
        if (required.isEmpty()) return true
        return (videos[video] ?: pending[video])?.let { entry ->
            required.all { CaptionTimeline.normalize(it) in entry.values }
        } == true
    }
    val size: Int get() = videos.size
    fun clear() {
        videos.clear(); pending.clear(); watched.clear(); counted.clear(); expired.clear(); selected = ""
    }
}

internal data class CaptionTranslationInput(val video: String, val text: String)
