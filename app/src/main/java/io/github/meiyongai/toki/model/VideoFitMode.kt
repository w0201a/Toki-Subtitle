package io.github.meiyongai.toki.subtitle.model

/** Stable persisted keys shared by settings validation and playback; existing keys retain their meaning. */
enum class VideoFitMode(val key: String) {
    CENTER("center"), SMART("smart"), FILL("fill"), CONTAIN("contain"), WIDTH("fit_width"), HEIGHT("fit_height");
    companion object {
        val keys: Set<String> = entries.mapTo(linkedSetOf()) { it.key }
        fun from(key: String?): VideoFitMode = entries.firstOrNull { it.key == key } ?: CENTER
    }
}
