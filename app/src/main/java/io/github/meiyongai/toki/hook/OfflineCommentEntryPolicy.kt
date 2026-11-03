package io.github.meiyongai.toki.subtitle.hook

/** Click ownership is stricter than merely sharing a video ID somewhere in the feed. */
internal object OfflineCommentEntryPolicy {
    fun intercept(enabled: Boolean, inspectedCode: Boolean, selectedVideo: String?, clickedVideo: String?,
        selectedOffline: Boolean, clickedOffline: Boolean, samePage: Boolean, visible: Boolean): Boolean =
        enabled && inspectedCode && selectedOffline && clickedOffline && samePage && visible &&
            !selectedVideo.isNullOrBlank() && selectedVideo == clickedVideo
}
