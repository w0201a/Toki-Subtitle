package io.github.meiyongai.toki.subtitle.hook

import io.github.meiyongai.toki.subtitle.provider.ConfigClient

/** Fail-closed, process-frozen rollout: never switch writers or subtitle controllers during live downloads. */
internal object CommentCoreRollout {
    const val KEY = "comment_caption_core_v3"
    private var selected: Boolean? = null
    @Synchronized fun enabled(): Boolean = selected ?: (ConfigClient.isReady && ConfigClient.getBoolean(KEY)).also { selected = it }
}
