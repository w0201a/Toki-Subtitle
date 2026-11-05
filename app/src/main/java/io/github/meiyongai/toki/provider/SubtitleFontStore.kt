package io.github.meiyongai.toki.subtitle.provider

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import io.github.meiyongai.toki.subtitle.model.FontFileFormat
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Immutable, app-private font copies. No automatic eviction or source-file modification. */
internal object SubtitleFontStore {
    const val AUTHORITY = "io.github.meiyongai.toki.subtitle.fonts"
    const val ASSET = "subtitle_font_asset"
    const val NAME = "subtitle_font_asset_name"
    private const val PREFS = "toki_subtitle_font_assets_v1"
    data class Asset(val id: String, val name: String, val kind: String, val bytes: Long, val file: File)
    fun validId(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))
    fun label(value: String): String {
        var text = value.filterNot { it.isISOControl() || it == '/' || it == '\\' }.trim().take(120)
        if (text.lastOrNull()?.isHighSurrogate() == true) text = text.dropLast(1)
        return text.ifBlank { "Custom font" }
    }
    private fun directory(context: Context) = File(context.noBackupFilesDir, "subtitle-fonts-v1").canonicalFile
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun uri(id: String): Uri { require(validId(id)); return Uri.parse("content://$AUTHORITY/font/$id") }
    fun id(uri: Uri): String {
        require(uri.scheme == "content" && uri.authority == AUTHORITY && uri.query == null && uri.fragment == null &&
            uri.pathSegments.size == 2 && uri.pathSegments[0] == "font")
        return uri.pathSegments[1].also { require(validId(it)) }
    }
    private fun digest(input: InputStream): String {
        val hash = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val count = input.read(buffer); if (count < 0) break
            if (count == 0) continue
            total += count; require(total <= FontFileFormat.MAX_BYTES)
            hash.update(buffer, 0, count)
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    /** Structural and platform validation must both finish before registration or selection. */
    @Synchronized fun importFont(context: Context, name: String, input: InputStream,
        validateTypeface: (File) -> Unit = { checkNotNull(Typeface.Builder(it).build()) }): Asset = input.use { source ->
        val dir = directory(context); check(dir.isDirectory || dir.mkdirs())
        val destination = File(dir, "font-" + UUID.randomUUID() + ".font")
        check(destination.createNewFile())
        // Failed private candidates remain unregistered; never delete without user confirmation.
        destination.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024); var total = 0L
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val single = source.read(); if (single < 0) break
                    require(++total <= FontFileFormat.MAX_BYTES); output.write(single)
                } else {
                    total += count; require(total <= FontFileFormat.MAX_BYTES); output.write(buffer, 0, count)
                }
            }
            output.flush(); output.fd.sync()
        }
        val info = FontFileFormat.inspect(destination)
        validateTypeface(destination)
        val id = destination.inputStream().use(::digest)
        val asset = Asset(id, label(name), info.kind, info.size, destination)
        val metadata = JSONObject().put("name", asset.name).put("kind", asset.kind).put("bytes", asset.bytes)
            .put("file", destination.name).put("at", System.currentTimeMillis())
        check(prefs(context).edit().putString(id, metadata.toString()).commit())
        for (host in ManualHostAdaptation.packages) try {
            context.packageManager.getApplicationInfo(host, 0)
            context.grantUriPermission(host, uri(id), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) { }
          catch (_: SecurityException) { /* The provider still requires an authorized host UID. */ }
        asset
    }
    fun resolve(context: Context, id: String, verify: Boolean = true): Asset {
        require(validId(id))
        val metadata = JSONObject(prefs(context).getString(id, null) ?: throw java.io.FileNotFoundException("Unknown font"))
        val filename = metadata.getString("file")
        require(filename.matches(Regex("font-[0-9a-fA-F-]{36}\\.font")))
        val dir = directory(context); val file = File(dir, filename).canonicalFile
        check(file.parentFile == dir && file.isFile && file.canRead())
        val size = metadata.getLong("bytes")
        check(size in 1..FontFileFormat.MAX_BYTES.toLong() && file.length() == size)
        val kind = metadata.getString("kind"); check(kind == "ttf" || kind == "otf")
        if (verify) {
            check(file.inputStream().use(::digest) == id) { "Font copy changed" }
            check(FontFileFormat.inspect(file).kind == kind)
        }
        return Asset(id, label(metadata.getString("name")), kind, size, file)
    }
    fun recent(context: Context): String {
        val result = org.json.JSONArray()
        val rows = prefs(context).all.entries.mapNotNull { (id, value) ->
            if (!validId(id)) null else runCatching { id to JSONObject(value as String) }.getOrNull()
        }.sortedByDescending { it.second.optLong("at") }.take(128)
        for ((id, row) in rows) if (runCatching { resolve(context, id, false) }.isSuccess)
            result.put(JSONObject().put("id", id).put("name", label(row.optString("name"))))
        return result.toString()
    }
}
