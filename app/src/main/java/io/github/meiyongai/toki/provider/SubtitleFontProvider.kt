package io.github.meiyongai.toki.subtitle.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor

/** Fonts are readable only by this manager UID and the installed supported host UIDs. */
class SubtitleFontProvider : ContentProvider() {
    override fun onCreate() = true
    private fun authorize() {
        val ctx = checkNotNull(context); val caller = Binder.getCallingUid()
        val allowed = caller == android.os.Process.myUid() || ManualHostAdaptation.packages.any { host ->
            try { ctx.packageManager.getApplicationInfo(host, 0).uid == caller }
            catch (_: android.content.pm.PackageManager.NameNotFoundException) { false }
        }
        if (!allowed) throw SecurityException("Unsupported font reader")
    }
    private fun <T> ownIdentity(block: () -> T): T {
        authorize(); val identity = Binder.clearCallingIdentity()
        try { return block() } finally { Binder.restoreCallingIdentity(identity) }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ownIdentity {
        if (mode != "r") throw SecurityException("Font storage is read-only")
        val asset = SubtitleFontStore.resolve(checkNotNull(context), SubtitleFontStore.id(uri))
        ParcelFileDescriptor.open(asset.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String = ownIdentity {
        val asset = SubtitleFontStore.resolve(checkNotNull(context), SubtitleFontStore.id(uri), false)
        if (asset.kind == "otf") "font/otf" else "font/ttf"
    }
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = ownIdentity {
        require(method == "recent" && arg == null && extras == null)
        Bundle().apply { putString("fonts", SubtitleFontStore.recent(checkNotNull(context))) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Font storage is read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw SecurityException("Font storage is read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw SecurityException("Font storage is read-only")
}
