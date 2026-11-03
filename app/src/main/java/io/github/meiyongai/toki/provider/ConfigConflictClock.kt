package io.github.meiyongai.toki.subtitle.provider

import org.json.JSONObject
import java.security.MessageDigest

/** Per-key manager revisions distinguish an explicit same-value edit from an unrelated publication. */
internal object ConfigConflictClock {
    const val KEY_REVISIONS = "__toki_key_revisions_v1"
    const val HOST_BASELINES = "__host_baselines_v1"
    const val HOST_SUPPRESSED = "__host_suppressed_v1"
    private const val MAX_METADATA = 128 * 1024

    fun revisions(raw: Any?, revision: Long): Map<String, Long> {
        if (raw == null) return emptyMap()
        require(raw is String && raw.length <= MAX_METADATA)
        val objectValue = JSONObject(raw)
        return objectValue.keys().asSequence().filter { it in ConfigSchema.keys }.associateWith { key ->
            val value = objectValue.get(key)
            require((value is Int || value is Long) && (value as Number).toLong() in 0..revision)
            (value as Number).toLong()
        }
    }

    fun baselines(raw: Any?): Map<String, String> {
        if (raw == null) return emptyMap()
        require(raw is String && raw.length <= MAX_METADATA)
        val objectValue = JSONObject(raw)
        return objectValue.keys().asSequence().filter { it in ConfigSchema.keys }.associateWith { key ->
            val value = objectValue.get(key)
            require(value is String && value.matches(Regex("[0-9a-f]{64}")))
            value
        }
    }

    fun encode(values: Map<String, *>): String = JSONObject().apply {
        values.toSortedMap().forEach { (key, value) -> put(key, value) }
    }.toString().also { require(it.length <= MAX_METADATA) }

    fun fingerprint(base: Map<String, *>, key: String, revisions: Map<String, Long>): String {
        val value = if (key !in base) "absent" else when (val item = base[key]) {
            is Boolean -> "boolean:$item"
            is String -> "string:$item"
            else -> error("Invalid configuration type")
        }
        val imported = base[ConfigStore.IMPORT_REVISION] as? Long ?: 0L
        val bytes = MessageDigest.getInstance("SHA-256").digest(
            ("$imported|" + (revisions[key] ?: 0L) + "|" + value).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }
    }
}
