package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.security.MessageDigest
import java.util.Base64

/** Run on the dialog's read/save worker. Never removes host or Toki snapshots to make room. */
internal object CommentSnapshotStore {
    enum class Saved { COMMITTED, FULL, UNAVAILABLE, CANCELLED }
    private fun prefs(context: Context) = context.getSharedPreferences("toki_comment_snapshots_v1", Context.MODE_PRIVATE)
    internal fun key(account: String, video: String): String {
        require(account.matches(Regex("[a-f0-9]{64}")) && video.length in 1..128)
        return DigestHex.encode(MessageDigest.getInstance("SHA-256").digest((account + ":" + video).toByteArray(Charsets.UTF_8)))
    }
    fun read(context: Context, account: String, video: String): OfflineCommentData.Snapshot? {
        val raw = prefs(context).getString(key(account, video), null) ?: return null
        require(raw.length <= (CommentSnapshotCodec.MAX_BYTES + 2) / 3 * 4)
        return CommentSnapshotCodec.decode(Base64.getDecoder().decode(raw)).also { require(it.account == account && it.video == video) }
    }
    @Synchronized fun save(context: Context, snapshot: OfflineCommentData.Snapshot, active: () -> Boolean = { true }): Saved = try {
        val preferences = prefs(context)
        val name = key(snapshot.account, snapshot.video)
        val encoded = Base64.getEncoder().encodeToString(CommentSnapshotCodec.encode(snapshot))
        val all = preferences.all
        val characters = all.entries.asSequence().filter { it.key != name }
            .sumOf { (it.value as? String)?.length?.toLong() ?: 0L } + encoded.length
        if (!active()) Saved.CANCELLED
        else if (name !in all && all.size >= 100 || characters > 12 * 1024 * 1024) Saved.FULL
        else if (preferences.edit().putString(name, encoded).commit()) Saved.COMMITTED else Saved.UNAVAILABLE
    } catch (_: Exception) { Saved.UNAVAILABLE }
}
