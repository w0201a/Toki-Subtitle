package io.github.meiyongai.toki.subtitle.provider

import java.util.UUID

/** Store scoped requests in the existing retry field; old module processes can still read the config schema. */
internal object ManualHostAdaptation {
    const val KEY = "adaptation_retry_token"
    private const val PREFIX = "hosts-v1|"
    val packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    private val nonce = Regex("[A-Za-z0-9-]{1,128}")
    private fun requests(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        if (!raw.startsWith(PREFIX)) return if (nonce.matches(raw)) packages.associateWith { raw } else emptyMap()
        val entries = raw.removePrefix(PREFIX).split(';')
        if (entries.size !in 1..packages.size) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (entry in entries) {
            val pair = entry.split(':', limit = 2)
            if (pair.size != 2 || pair[0] !in packages || !nonce.matches(pair[1]) || pair[0] in result) return emptyMap()
            result[pair[0]] = pair[1]
        }
        return result
    }
    fun values(previous: String?, target: String, token: String = UUID.randomUUID().toString()): Map<String, Any> {
        require(target in packages && nonce.matches(token))
        val merged = requests(previous).toMutableMap().apply { put(target, token) }
        val encoded = PREFIX + packages.mapNotNull { host -> merged[host]?.let { "$host:$it" } }.joinToString(";")
        return mapOf(KEY to encoded)
    }
    fun requested(raw: String?, target: String): Boolean = target in packages && target in requests(raw)
    fun token(raw: String?, target: String, previousToken: String): String = requests(raw)[target] ?: previousToken
    fun pending(raw: String?, target: String, previousToken: String): Boolean =
        requested(raw, target) && token(raw, target, previousToken) != previousToken
}
