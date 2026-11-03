package io.github.meiyongai.toki.subtitle.provider

/** Extra protection for technical metadata. Never pass user content to logging in the first place. */
internal object DiagnosticText {
    private val urls = Regex("(?i)https?://[^\\s<>]+")
    private val secrets = Regex("(?i)\\b(authorization|proxy-authorization|api[_-]?key|access[_-]?token|refresh[_-]?token|password|cookie)\\s*[:=]\\s*(?:\"[^\"]*\"|[^\\r\\n,;]+)")
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val apiKeys = Regex("\\bsk-[A-Za-z0-9_-]{8,}")
    private val paths = Regex("(?:[A-Za-z]:\\\\[^\\s]+|/(?:data|storage|sdcard)/[^\\s]+)")
    fun redact(value: String): String = paths.replace(apiKeys.replace(bearer.replace(secrets.replace(urls.replace(value, "<地址已隐藏>"),
        "$1=<敏感值已隐藏>"), "Bearer <已隐藏>"), "<密钥已隐藏>"), "<本地路径已隐藏>")
    fun line(value: String, limit: Int = 500): String = redact(value).replace("\r", "\\r").replace("\n", "\\n")
        .map { if (it.isISOControl()) ' ' else it }.joinToString("").take(limit)
}
