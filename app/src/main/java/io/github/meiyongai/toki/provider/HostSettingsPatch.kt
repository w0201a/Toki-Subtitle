package io.github.meiyongai.toki.subtitle.provider

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest

/** Local Binder payload only: no remote server, diagnostic data or caller-supplied storage paths. */
internal data class HostSettingsPatch(val host: String, val id: String, val values: Map<String, Any?>, val replace: Boolean = false, val onlyAbsent: Boolean = false)
internal data class HostSettingsAck(val id: String, val revision: Long)
internal data class HostSettingsSyncStatus(val pending: Int = 0, val revision: Long? = null, val error: String? = null)

internal object HostSettingsWire {
    const val METHOD = "settings-patch-v1"
    const val PENDING = "__host_mirror_pending_v1"
    const val PAYLOAD = "__host_mirror_values_v1"
    const val REQUEST = "__host_mirror_request_v1"
    const val RECONCILE = "__host_mirror_reconcile_v1"
    const val REPLACE = "__host_mirror_replace_v1"
    const val ACK = "__host_mirror_ack_v1"
    const val RECEIPTS = "__host_mirror_receipts_v1"
    private val nonce = Regex("[A-Za-z0-9-]{1,128}")
    fun validate(patch: HostSettingsPatch) {
        require(patch.host in ManualHostAdaptation.packages && nonce.matches(patch.id))
        require(!patch.onlyAbsent || !patch.replace)
        require(patch.values.size <= ConfigSchema.keys.size)
        require(patch.values.keys.all { it in ConfigSchema.keys })
        ConfigArchive.validate(patch.values.filterValues { it != null }.mapValues { requireNotNull(it.value) })
    }
    fun valuesJson(values: Map<String, Any?>): String = JSONObject().apply {
        values.toSortedMap().forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }.toString()
    fun values(raw: String): Map<String, Any?> {
        val data = JSONObject(raw)
        return data.keys().asSequence().associateWith { key ->
            val value = data.get(key)
            require(value === JSONObject.NULL || value is String || value is Boolean)
            if (value === JSONObject.NULL) null else value
        }
    }
    fun encode(patch: HostSettingsPatch): String {
        validate(patch)
        return JSONObject().apply {
            put("protocol", 1); put("host", patch.host); put("id", patch.id); put("replace", patch.replace)
            if (patch.onlyAbsent) put("onlyAbsent", true)
            put("values", JSONObject().apply { patch.values.toSortedMap().forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } })
        }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= ConfigArchive.MAX_BYTES) }
    }
    fun decode(text: String): HostSettingsPatch {
        require(text.toByteArray(Charsets.UTF_8).size <= ConfigArchive.MAX_BYTES)
        val reader = JSONTokener(text)
        val root = reader.nextValue()
        require(root is JSONObject && reader.nextClean().code == 0)
        val keys = root.keys().asSequence().toSet()
        val required = setOf("protocol", "host", "id", "replace", "values")
        require(keys == required || keys == required + "onlyAbsent")
        require(!root.has("onlyAbsent") || root.get("onlyAbsent") is Boolean)
        require(root.get("protocol") == 1 && root.get("host") is String && root.get("id") is String && root.get("replace") is Boolean)
        val values = root.get("values"); require(values is JSONObject)
        return HostSettingsPatch(root.getString("host"), root.getString("id"), values.keys().asSequence().associateWith {
            val value = values.get(it)
            require(value === JSONObject.NULL || value is String || value is Boolean)
            if (value === JSONObject.NULL) null else value
        }, root.getBoolean("replace"), root.optBoolean("onlyAbsent", false)).also(::validate)
    }
    fun bounded(text: String): String {
        require(text.toByteArray(Charsets.UTF_8).size <= ConfigArchive.MAX_BYTES)
        return text
    }
    fun digest(patch: HostSettingsPatch): String = MessageDigest.getInstance("SHA-256")
        .digest(encode(patch).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(java.util.Locale.ROOT, it.toInt() and 255) }

    private fun receipts(raw: String?): List<JSONObject> {
        if (raw == null) return emptyList()
        require(raw.length <= 32768)
        val array = JSONArray(raw); require(array.length() <= 64)
        return (0 until array.length()).map { index -> array.getJSONObject(index).also {
            require(it.getString("host") in ManualHostAdaptation.packages && nonce.matches(it.getString("id")))
            require(it.getLong("revision") >= 0 && it.getString("digest").matches(Regex("[0-9a-f]{64}")))
        } }
    }
    fun receipt(raw: String?, patch: HostSettingsPatch): Long? = receipts(raw).firstOrNull {
        it.getString("host") == patch.host && it.getString("id") == patch.id
    }?.let {
        require(it.getString("digest") == digest(patch)) { "Reused sync id with changed payload" }
        it.getLong("revision")
    }
    fun remember(raw: String?, patch: HostSettingsPatch, revision: Long): String {
        val prior = receipts(raw)
        val retained = prior.filter { it.getString("host") != patch.host } +
            prior.filter { it.getString("host") == patch.host && it.getString("id") != patch.id }.takeLast(31)
        return JSONArray(retained + JSONObject().apply {
            put("host", patch.host); put("id", patch.id); put("revision", revision); put("digest", digest(patch))
        }).toString()
    }
}
