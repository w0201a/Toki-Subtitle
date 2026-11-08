package io.github.meiyongai.toki.subtitle.update

import org.json.JSONObject

/** Stable public release identity, compared numerically rather than lexicographically. */
internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int = compareValuesBy(this, other,
        ReleaseVersion::major, ReleaseVersion::minor, ReleaseVersion::patch)
    override fun toString(): String = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): ReleaseVersion? {
            val match = Regex("v?([0-9]+)\\.([0-9]+)\\.([0-9]+)").matchEntire(value) ?: return null
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: return null }
            return ReleaseVersion(parts[0], parts[1], parts[2])
        }
    }
}

/** Notes are plain text; the only opened URL is constructed for this fixed public repository. */
internal data class AppRelease(val version: ReleaseVersion, val tag: String, val notes: String) {
    val pageUrl: String get() = "https://github.com/w0201a/Toki-Subtitle/releases/tag/$tag"
    fun encode(): String = JSONObject().put("tag", tag).put("notes", notes).toString()
    companion object {
        const val MAX_NOTES = 32_000
        fun fromApi(value: String): AppRelease? {
            val json = JSONObject(value)
            if (json.optBoolean("draft", true) || json.optBoolean("prerelease", true)) return null
            val tag = json.optString("tag_name")
            val version = ReleaseVersion.parse(tag) ?: return null
            val notes = if (json.isNull("body")) "" else json.optString("body").take(MAX_NOTES)
            return AppRelease(version, tag, notes)
        }
        fun fromCache(value: String): AppRelease {
            val json = JSONObject(value)
            val tag = json.getString("tag")
            val version = requireNotNull(ReleaseVersion.parse(tag)) { "Invalid cached release" }
            return AppRelease(version, tag, json.optString("notes").take(MAX_NOTES))
        }
    }
}
