package io.github.meiyongai.toki.subtitle.hook

import android.content.Context

/** Private, bounded source-caption archive. Called only by CaptionPrefetch's download workers. */
internal object CaptionTimelineDiskCache {
    private const val MAX_TRACKS = 100
    private const val MAX_CHARACTERS = 2 * 1024 * 1024
    private fun preferences() = HookRuntime.hostContext?.getSharedPreferences("toki_caption_timelines", Context.MODE_PRIVATE)

    @Synchronized fun read(identity: String): String? = try {
        if (identity.isBlank()) null else preferences()?.getString(identity, null)?.takeIf { it.length <= 1_048_576 }
    } catch (error: Exception) {
        HookRuntime.event("CaptionPrefetch", "timeline restore unavailable type=" + error.javaClass.simpleName)
        null
    }

    @Synchronized fun save(identity: String, body: String) {
        if (identity.isBlank() || body.length > 1_048_576) return
        try {
            val prefs = preferences() ?: return
            val current = prefs.all
            if (identity !in current && current.size >= MAX_TRACKS) return
            // Four bytes per UTF-16 code unit is a conservative UTF-8 storage bound.
            val characters = current.entries.asSequence().filter { it.key != identity }
                .sumOf { (it.value as? String)?.length?.toLong() ?: 0L } + body.length
            if (characters > MAX_CHARACTERS) return
            if (current[identity] == body) return
            if (!prefs.edit().putString(identity, body).commit()) {
                HookRuntime.event("CaptionPrefetch", "timeline write not committed")
            }
        } catch (error: Exception) {
            HookRuntime.event("CaptionPrefetch", "timeline write unavailable type=" + error.javaClass.simpleName)
        }
    }
}
