package io.github.meiyongai.toki.subtitle.hook

import org.json.JSONObject

/** 完整字幕的轻量时间轴；只接受有正文和有效时间的条目。 */
internal object CaptionTimeline {
    private val whitespace = Regex("[\\s\u00A0]+")
    fun normalize(text: String): String = text.replace(whitespace, " ").trim()
    data class Cue(val startMs: Long, val endMs: Long, val text: String)
    fun parse(body: String): List<Cue> {
        if (body.length > 1_048_576) return emptyList()
        val source = body.removePrefix("\uFEFF").replace("\r\n", "\n")
        val cues = mutableListOf<Cue>()
        if (source.trimStart().startsWith("{")) {
            try {
                val root = JSONObject(source)
                val entries = root.optJSONArray("utterances") ?: root.optJSONArray("captions") ?: return emptyList()
                for (i in 0 until minOf(entries.length(), 3000)) {
                    val item = entries.optJSONObject(i) ?: continue
                    val text = item.optString("text").trim()
                    val start = item.optLong("start_time", -1)
                    val end = item.optLong("end_time", -1)
                    if (start >= 0 && end > start && text.length in 1..1000) cues += Cue(start, end, text)
                }
            } catch (_: Exception) { return emptyList() }
        } else {
            val timing = Regex("(?m)^(?:(\\d+):)?(\\d{2}):(\\d{2})[.,](\\d{3})\\s+-->\\s+(?:(\\d+):)?(\\d{2}):(\\d{2})[.,](\\d{3})[^\\n]*\\n")
            for (match in timing.findAll(source).take(3000)) {
                fun time(offset: Int): Long = ((match.groupValues[offset].toLongOrNull() ?: 0) * 3600 +
                    match.groupValues[offset + 1].toLong() * 60 + match.groupValues[offset + 2].toLong()) * 1000 +
                    match.groupValues[offset + 3].toLong()
                val start = time(1); val end = time(5)
                val from = match.range.last + 1
                val until = source.indexOf("\n\n", from).takeIf { it >= 0 } ?: source.length
                val text = source.substring(from, until).replace(Regex("<[^>]*>"), "")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
                if (start >= 0 && end > start && text.length in 1..1000) cues += Cue(start, end, text)
            }
        }
        return cues.sortedBy { it.startMs }.distinct()
    }
}
