package io.github.meiyongai.toki.subtitle.update

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Separate from feature configuration and host storage; updates never reset Toki or media settings. */
internal class UpdatePreferences(private val preferences: SharedPreferences) : UpdateStateStore {
    override val lock: Any get() = processLock
    override fun read(): UpdateState {
        val encoded = preferences.getString("state", null) ?: return UpdateState()
        val json = JSONObject(encoded)
        check(json.getInt("schema") == 1) { "Unknown update state" }
        val next = json.getLong("nextAutomaticAt")
        require(next >= 0)
        val pending = if (json.isNull("pending")) null else AppRelease.fromCache(json.getString("pending"))
        val notified = if (json.isNull("notified")) null else requireNotNull(ReleaseVersion.parse(json.getString("notified")))
        return UpdateState(next, pending, notified)
    }
    override fun write(state: UpdateState) {
        val json = JSONObject().put("schema", 1).put("nextAutomaticAt", state.nextAutomaticAt)
            .put("pending", state.pending?.encode() ?: JSONObject.NULL)
            .put("notified", state.notified?.toString() ?: JSONObject.NULL)
        check(preferences.edit().putString("state", json.toString()).commit()) { "Update state was not persisted" }
    }
    companion object {
        private val processLock = Any()
        fun engine(context: Context, installedName: String): UpdateEngine {
            val app = context.applicationContext
            val version = requireNotNull(ReleaseVersion.parse(installedName.removeSuffix("-debug")))
            return UpdateEngine(version, UpdatePreferences(app.getSharedPreferences("toki_app_updates", Context.MODE_PRIVATE)),
                GitHubReleaseSource(installedName))
        }
    }
}
