package io.github.meiyongai.toki.subtitle.hook

import android.content.SharedPreferences
import java.util.Base64
import java.util.concurrent.Executors

/** Serialization and disk commits run on one coalesced writer, never in the subtitle frame callback. */
internal object CaptionCacheStorage {
    private data class Write(val preferences: SharedPreferences, val key: String,
        val videos: Map<String, VideoCaptionCache.Video>)
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "toki-caption-persistence").apply { isDaemon = true }
    }
    private val pending = linkedMapOf<String, Write>()
    private var running = false

    fun load(preferences: SharedPreferences, key: String): Map<String, VideoCaptionCache.Video>? {
        return try {
            val raw = preferences.getString(key, null) ?: return null
            require(raw.length <= (CaptionCacheCodec.MAX_BYTES + 2) / 3 * 4)
            CaptionCacheCodec.decode(Base64.getDecoder().decode(raw))
        } catch (error: Exception) {
            HookRuntime.event("NativeSubtitleTranslation", "cache restore rejected type=" + error.javaClass.simpleName)
            null
        }
    }

    @Synchronized fun save(preferences: SharedPreferences, key: String, videos: Map<String, VideoCaptionCache.Video>) {
        pending[key] = Write(preferences, key, videos)
        if (running) return
        running = true
        writer.execute { drain() }
    }

    private fun drain() {
        while (true) {
            val next = synchronized(this) {
                val key = pending.keys.firstOrNull()
                if (key == null) { running = false; return }
                checkNotNull(pending.remove(key))
            }
            try {
                val encoded = Base64.getEncoder().encodeToString(CaptionCacheCodec.encode(next.videos))
                if (!next.preferences.edit().putString(next.key, encoded).commit()) {
                    HookRuntime.event("NativeSubtitleTranslation", "cache write not committed")
                }
            } catch (error: Exception) {
                HookRuntime.event("NativeSubtitleTranslation", "cache write unavailable type=" + error.javaClass.simpleName)
            }
        }
    }
}
