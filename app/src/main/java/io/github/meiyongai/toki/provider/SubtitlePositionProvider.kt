package io.github.meiyongai.toki.subtitle.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** 宿主拖动位置的窄写入通道：仅允许已安装 TikTok UID 写入两个位置数值。 */
class SubtitlePositionProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = checkNotNull(context)
        val uid = Binder.getCallingUid()
        val allowed = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill").any {
            try { ctx.packageManager.getApplicationInfo(it, 0).uid == uid }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        }
        check(allowed && method == "position") { "Unsupported subtitle caller" }
        val input = checkNotNull(extras)
        val values = listOf("subtitle_x", "subtitle_y").associateWith {
            require(input.containsKey(it))
            val value = input.getFloat(it)
            require(value.isFinite() && value in -1000f..1000f)
            value.toString()
        }
        ConfigClient.putStrings(ctx, values)
        return Bundle().apply { putBoolean("saved", true) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
