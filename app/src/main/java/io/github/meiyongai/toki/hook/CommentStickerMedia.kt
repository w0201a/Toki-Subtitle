package io.github.meiyongai.toki.subtitle.hook

import java.net.URI

/** Original sticker bytes are preserved; animation is not flattened into a screenshot. */
internal object CommentStickerMedia {
    const val MAX_BYTES = 20 * 1024 * 1024
    data class Format(val extension: String, val mime: String)
    private val domains = setOf("tiktokcdn.com", "tiktokcdn-us.com", "tiktokcdn-eu.com", "tiktokv.com",
        "byteoversea.com", "ibytedtos.com", "ibyteimg.com", "byteimg.com", "muscdn.com", "tiktok.com",
        "giphy.com", "giphyusercontent.com", "tenor.com", "googleapis.com", "googleusercontent.com")

    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return false
        uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443) &&
            domains.any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    fun urls(animated: List<String>, static: List<String>): List<String> =
        (animated + static).filter(::allowed).distinct().take(12)

    fun format(bytes: ByteArray): Format? {
        fun matches(offset: Int, value: String) = bytes.size >= offset + value.length &&
            value.indices.all { bytes[offset + it].toInt() and 255 == value[it].code }
        return when {
            matches(0, "GIF87a") || matches(0, "GIF89a") -> Format("gif", "image/gif")
            bytes.size >= 8 && bytes.take(8).map { it.toInt() and 255 } == listOf(137, 80, 78, 71, 13, 10, 26, 10) -> Format("png", "image/png")
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> Format("jpg", "image/jpeg")
            matches(0, "RIFF") && matches(8, "WEBP") -> Format("webp", "image/webp")
            matches(4, "ftypavif") || matches(4, "ftypavis") -> Format("avif", "image/avif")
            else -> null
        }
    }

    fun fileName(id: String, extension: String, nonce: String): String {
        require(extension in setOf("gif", "png", "jpg", "webp", "avif"))
        val safe = id.filter { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' }.take(48).ifBlank { "sticker" }
        val unique = nonce.filter { it.isLetterOrDigit() && it.code < 128 || it == '-' }.take(48)
        return "Toki-sticker-$safe-$unique.$extension"
    }
    fun read(input: java.io.InputStream, limit: Int = MAX_BYTES): ByteArray {
        require(limit > 0)
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            check(output.size().toLong() + size <= limit) { "Image exceeds download limit" }
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }
}
