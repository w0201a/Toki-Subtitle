package io.github.meiyongai.toki.subtitle.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** 鉴权本地回写：兼容字幕位置与版本化功能设置补丁，不提供配置读取或任意键写入。 */
class SubtitlePositionProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = checkNotNull(context)
        val uid = Binder.getCallingUid()
        val allowed = ManualHostAdaptation.packages.filter {
            try { ctx.packageManager.getApplicationInfo(it, 0).uid == uid }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        }
        check(allowed.isNotEmpty() && method in setOf("position", HostSettingsWire.METHOD)) { "Unsupported subtitle caller" }
        val input = checkNotNull(extras)
        val patch = if (method == HostSettingsWire.METHOD) HostSettingsWire.decode(checkNotNull(input.getString("patch"))).also {
            check(it.host in allowed) { "Caller cannot impersonate another host" }
        } else null
        // Authenticate before clearing identity; nested framework calls must run as the manager, not TikTok.
        val identity = Binder.clearCallingIdentity()
        try {
            if (patch != null) {
                val revision = ConfigClient.applyHostPatch(ctx, patch)
                return Bundle().apply { putInt("protocol", 1); putBoolean("saved", true); putString("id", patch.id); putLong("revision", revision) }
            }
            val values = listOf("subtitle_x", "subtitle_y").associateWith {
                require(input.containsKey(it))
                val value = input.getFloat(it)
                require(value.isFinite() && value in -1000f..1000f)
                value.toString()
            }
            ConfigClient.putStrings(ctx, values)
            return Bundle().apply { putBoolean("saved", true) }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
