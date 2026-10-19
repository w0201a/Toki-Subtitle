package io.github.meiyongai.toki.subtitle.provider

import java.util.ArrayDeque

/** 有界诊断队列；不记录视频/评论正文，避免 Binder 回复无限膨胀。 */
internal object TrialLog {
    private val lines = ArrayDeque<String>()
    private var dropped = 0L
    @Synchronized fun add(tag: String, message: String) {
        if (lines.size == 600) { lines.removeFirst(); dropped++ }
        lines.addLast("${System.currentTimeMillis()} $tag ${message.take(500)}")
    }
    @Synchronized fun snapshot(): String = "build=${io.github.meiyongai.toki.subtitle.BuildConfig.VERSION_NAME} dropped=$dropped\n" + lines.joinToString("\n")
}
