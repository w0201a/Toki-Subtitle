package io.github.meiyongai.toki.subtitle.hook

import android.content.SharedPreferences
import org.json.JSONObject

/** Durable job updates merge only lane-owned fields; an epoch/revision guard prevents stale writers completing new work. */
internal class OfflineCommentJobStore(private val preferences: SharedPreferences) {
    @Synchronized fun rows(account: String): List<Pair<String, JSONObject>> = preferences.all.mapNotNull { (key, value) ->
        val row = try { JSONObject(value as? String ?: return@mapNotNull null) } catch (_: Exception) { return@mapNotNull null }
        if (row.optString("account") != account || row.optString("video").isBlank()) null else key to row
    }
    @Synchronized fun get(key: String): JSONObject? = preferences.getString(key, null)?.let(::JSONObject)
    @Synchronized fun put(key: String, row: JSONObject): Boolean {
        if (preferences.getLong("epoch:" + row.getString("account"), 0L) != row.optLong("epoch")) return false
        check(preferences.edit().putString(key, row.toString()).commit()) { "Comment job write failed" }
        return true
    }
    @Synchronized fun register(key: String, seed: JSONObject, action: (JSONObject) -> Unit): Boolean {
        val current = get(key) ?: seed
        if (current.optLong("epoch") != seed.optLong("epoch")) return false
        val beforeRevision = current.optLong("sourceRevision")
        action(current)
        // Legacy migration may run after a source worker has already advanced this job.
        current.put("sourceRevision", maxOf(beforeRevision, current.optLong("sourceRevision")))
        return put(key, current)
    }
    @Synchronized fun change(key: String, epoch: Long, action: (JSONObject) -> Unit): Boolean {
        val current = get(key) ?: return false
        if (current.optLong("epoch") != epoch || preferences.getLong("epoch:" + current.getString("account"), 0L) != epoch) return false
        action(current)
        return put(key, current)
    }
    @Synchronized fun finishTranslation(key: String, epoch: Long, sourceRevision: Long): Boolean {
        val current = get(key) ?: return false
        if (current.optLong("sourceRevision") != sourceRevision) return false
        return change(key, epoch) {
            it.put("translationReady", true).put("translationAttempts", 0).put("translationRetryAt", 0L)
            it.put("state", if (it.optBoolean("sourceReady") && it.optBoolean("mediaReady", true)) "complete" else "pending")
        }
    }
    @Synchronized fun finishMedia(key: String, epoch: Long, sourceRevision: Long): Boolean {
        val current = get(key) ?: return false
        if (current.optLong("sourceRevision") != sourceRevision) return false
        return change(key, epoch) {
            it.put("mediaReady", true).put("mediaAttempts", 0).put("mediaRetryAt", 0L)
            it.put("state", if (it.optBoolean("sourceReady") && it.optBoolean("translationReady")) "complete" else "pending")
        }
    }
    @Synchronized fun retry(account: String) {
        for ((key, row) in rows(account)) {
            row.put("sourceRetryAt", 0L).put("translationRetryAt", 0L).put("mediaRetryAt", 0L)
            put(key, row)
        }
    }
    fun plans(account: String, lane: OfflineCommentWorkPlan.Lane): List<OfflineCommentWorkPlan.Job> = rows(account).map { (key, row) ->
        val prefix = when (lane) { OfflineCommentWorkPlan.Lane.SOURCE -> "source"; OfflineCommentWorkPlan.Lane.TRANSLATION -> "translation"; OfflineCommentWorkPlan.Lane.MEDIA -> "media" }
        OfflineCommentWorkPlan.Job(key, row.optBoolean("firstPage"), row.optBoolean("sourceReady"), row.optBoolean("translationReady"),
            row.optLong(prefix + "RetryAt"), row.optLong(prefix + "LastRun"), row.optBoolean("mediaReady", true))
    }
}
