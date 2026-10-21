package io.github.meiyongai.toki.subtitle.hook

/** Advance only after a batch has completed; wait for feed updates when no next batch is loaded. */
internal class CaptionBatchCursor {
    var offset = 0
        private set
    var allowed = true
        private set
    fun complete(batchSize: Int, completedVideos: Int) {
        require(batchSize in 1..5)
        offset += batchSize
        allowed = completedVideos < 100
    }
    fun reset() { offset = 0; allowed = true }
}
