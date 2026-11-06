package io.github.meiyongai.toki.subtitle.hook

import java.security.MessageDigest

/** A new source, account, video, comment or target must never reuse an older saved translation. */
internal object CommentTranslationKey {
    fun normalizedTarget(value: String): String? = value.replace('_', '-').lowercase(java.util.Locale.ROOT)
        .takeIf { it.matches(Regex("[a-z]{2,8}(-[a-z0-9]{1,8})*")) }
    fun key(account: String, video: String, comment: String, source: String, target: String): String {
        require(account.matches(Regex("[a-f0-9]{64}")) && video.length in 1..128 && comment.length in 1..128)
        require(source.length in 1..OfflineCommentData.MAX_TEXT)
        val language = requireNotNull(normalizedTarget(target))
        val digest = MessageDigest.getInstance("SHA-256")
        for (value in listOf(account, video, comment, source, language)) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return DigestHex.encode(digest.digest())
    }
}
