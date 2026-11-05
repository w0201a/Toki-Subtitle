package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.lang.reflect.Modifier

/** Exact current-account / selected-video native DB lookup. No scan, URL resolution or download. */
internal class HostOfflineVideoFiles(private val loader: ClassLoader) {
    data class Entry(val key: String, val directory: File, val current: () -> Boolean)
    fun find(context: Context, aweme: Any, roots: List<File>): Entry? {
        // classes6 (native current-account path) and classes2 (Aweme) are pinned by this gate.
        if (!OfflineExportCodeGate.verify(context)) return null
        val native = Class.forName("X.0A9A", false, loader)
        val path = native.getDeclaredMethod("LJIIIIZZ").apply {
            check(returnType == String::class.java && Modifier.isStatic(modifiers)); isAccessible = true
        }
        fun directory(): File? = (path.invoke(null) as? String)?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
        val directory = directory() ?: return null
        if (directory.parentFile?.name != "offlinemode" || directory.name.isBlank() ||
            roots.none { directory.toPath().startsWith(it.canonicalFile.toPath()) })
            throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.OUTSIDE_STORAGE)
        val aid = aweme.javaClass.getMethod("getAid").apply {
            check(returnType == String::class.java && !Modifier.isStatic(modifiers))
        }.invoke(aweme) as? String ?: return null
        if (aid.length !in 1..128 || !aid.all(Char::isDigit)) return null
        val database = context.getDatabasePath("offline_mode_feed_pb")
        if (!database.isFile) return null
        val key = SQLiteDatabase.openDatabase(database.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            db.rawQuery("SELECT url_key FROM offline_mode_feed_pb WHERE sec_uid=? AND aid=? AND has_cached=1 LIMIT 2",
                arrayOf(directory.name, aid)).use cursorUse@ { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return@cursorUse null
                val value = cursor.getString(0)
                if (cursor.moveToNext() || !OfflineMdlFile.validKey(value)) null else value
            }
        } ?: return null
        if (directory() != directory) throw LocalVideoFile.Unavailable(LocalVideoFile.Failure.CHANGED)
        return Entry(key, directory) { directory() == directory }
    }
}
