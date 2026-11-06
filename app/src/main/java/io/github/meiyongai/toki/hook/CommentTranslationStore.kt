package io.github.meiyongai.toki.subtitle.hook

import android.content.Context
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Private additive translation cache. Capacity exhaustion is reported; existing user caches are not evicted/deleted. */
internal object CommentTranslationStore {
    private const val MAX_RECORDS = 2000
    private const val MAX_CHARACTERS = 2 * 1024 * 1024
    private val writer = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
        { task -> Thread(task, "toki-comment-translation-writer").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())
    enum class Saved { COMMITTED, FULL, UNAVAILABLE }
    private fun preferences(context: Context) = context.getSharedPreferences("toki_comment_translations_v1", Context.MODE_PRIVATE)

    /** Only call on a read worker. No translation service, network or native cache writes are involved. */
    fun restore(context: Context, snapshot: OfflineCommentData.Snapshot, preferredTarget: String?): OfflineCommentData.Snapshot {
        val prefs = preferences(context)
        val target = preferredTarget?.let(CommentTranslationKey::normalizedTarget)
            ?: prefs.getString("target:" + snapshot.account, null)?.let(CommentTranslationKey::normalizedTarget) ?: return snapshot
        return snapshot.copy(rows = snapshot.rows.map { row ->
            if (row.source.isBlank() || row.sourceTruncated ||
                !row.translation.isNullOrBlank() && CommentTranslationKey.normalizedTarget(row.language) == target) row else {
                val key = CommentTranslationKey.key(snapshot.account, snapshot.video, row.id, row.source, target)
                val saved = prefs.getString(key, null)?.takeIf { it.isNotBlank() && it.length <= 80000 }
                if (saved == null) row else row.copy(translation = saved, language = target)
            }
        })
    }

    fun save(context: Context, snapshot: OfflineCommentData.Snapshot, row: OfflineCommentData.Row, target: String,
        translated: String, completion: (Saved) -> Unit) {
        if (row.sourceTruncated || translated.isBlank() || translated.length > 80000) { completion(Saved.UNAVAILABLE); return }
        val app = context.applicationContext
        try {
            writer.execute {
                val saved = try {
                    val prefs = preferences(app)
                    val key = CommentTranslationKey.key(snapshot.account, snapshot.video, row.id, row.source, target)
                    val current = prefs.all
                    val records = current.keys.count { it.matches(Regex("[a-f0-9]{64}")) }
                    val targetKey = "target:" + snapshot.account
                    val normalizedTarget = requireNotNull(CommentTranslationKey.normalizedTarget(target))
                    val characters = current.entries.asSequence().filter { it.key != key && it.key != targetKey }
                        .sumOf { (it.value as? String)?.length?.toLong() ?: 0L } + translated.length + normalizedTarget.length
                    if (key !in current && records >= MAX_RECORDS || characters > MAX_CHARACTERS) Saved.FULL
                    else if (prefs.edit().putString(key, translated).putString(targetKey, normalizedTarget).commit()) Saved.COMMITTED else Saved.UNAVAILABLE
                } catch (_: Exception) { Saved.UNAVAILABLE }
                completion(saved)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { completion(Saved.UNAVAILABLE) }
    }
}
