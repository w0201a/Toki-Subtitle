package io.github.meiyongai.toki.subtitle.hook

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Versioned bounded snapshot data, not Java serialization of host objects. */
internal object CommentSnapshotCodec {
    const val MAX_BYTES = 4 * 1024 * 1024
    fun encode(snapshot: OfflineCommentData.Snapshot): ByteArray {
        require(snapshot.account.matches(Regex("[a-f0-9]{64}")) && snapshot.video.length in 1..128)
        require(snapshot.rows.size <= OfflineCommentData.MAX_ROWS && snapshot.total >= 0 && snapshot.capturedAt >= 0)
        require(snapshot.nextCursor == null || snapshot.nextCursor >= 0)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                require(encoded.size <= 320000 && encoded.toString(Charsets.UTF_8) == value)
                out.writeInt(encoded.size); out.write(encoded); require(bytes.size() <= MAX_BYTES)
            }
            out.writeInt(0x54434d31); out.writeInt(2)
            text(snapshot.account); text(snapshot.video)
            out.writeLong(snapshot.capturedAt); out.writeLong(snapshot.total)
            out.writeBoolean(snapshot.hasMore); out.writeBoolean(snapshot.truncated)
            out.writeLong(snapshot.nextCursor ?: -1L); out.writeBoolean(snapshot.retainedOlder)
            out.writeInt(snapshot.rows.size)
            val seen = mutableSetOf<String>()
            snapshot.rows.forEach { row ->
                require(row.id.isNotBlank() && row.id.length in 1..128 && seen.add(row.id) && row.source.length <= OfflineCommentData.MAX_TEXT)
                require(row.translation == null || row.translation.length <= 80000)
                require(row.depth in 0..3 && row.replyCount >= 0 && row.author.length <= 256 &&
                    row.handle.length <= 128 && row.authorId.length <= 128 && row.language.length <= 64 && row.parentId.length <= 128)
                text(row.id); text(row.author); text(row.handle); text(row.authorId); text(row.source)
                out.writeBoolean(row.translation != null); row.translation?.let(::text)
                text(row.language); out.writeInt(row.depth); out.writeLong(row.replyCount); out.writeBoolean(row.sourceTruncated); text(row.parentId)
                require(bytes.size() <= MAX_BYTES)
            }
            require(snapshot.replyCursors.size <= OfflineCommentData.MAX_ROWS)
            out.writeInt(snapshot.replyCursors.size)
            snapshot.replyCursors.forEach { (root, cursor) ->
                require(root.isNotBlank() && root.length <= 128 && (cursor.next == null || cursor.next >= 0))
                text(root); out.writeLong(cursor.next ?: -1); out.writeBoolean(cursor.hasMore)
                require(bytes.size() <= MAX_BYTES)
            }
        }
        return bytes.toByteArray()
    }
    fun decode(bytes: ByteArray): OfflineCommentData.Snapshot {
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            fun text(limit: Int): String {
                val size = input.readInt()
                require(size in 0..minOf(320000, input.available()))
                val raw = ByteArray(size); input.readFully(raw)
                val value = raw.toString(Charsets.UTF_8)
                require(value.length <= limit && value.toByteArray(Charsets.UTF_8).contentEquals(raw))
                return value
            }
            require(input.readInt() == 0x54434d31)
            val version = input.readInt(); require(version in 1..2)
            val account = text(64); val video = text(128)
            require(account.matches(Regex("[a-f0-9]{64}")) && video.isNotBlank())
            val capturedAt = input.readLong(); val total = input.readLong()
            require(capturedAt >= 0 && total >= 0)
            val more = input.readBoolean(); val truncated = input.readBoolean()
            val cursor = input.readLong(); require(cursor >= -1)
            val retained = input.readBoolean()
            val count = input.readInt(); require(count in 0..OfflineCommentData.MAX_ROWS)
            val seen = mutableSetOf<String>()
            val rows = (0 until count).map {
                val id = text(128); require(id.isNotBlank() && seen.add(id))
                val author = text(256); val handle = text(128); val user = text(128); val source = text(OfflineCommentData.MAX_TEXT)
                val translated = if (input.readBoolean()) text(80000) else null
                val language = text(64); val depth = input.readInt(); val replies = input.readLong()
                require(depth in 0..3 && replies >= 0)
                val shortened = input.readBoolean(); val parent = text(128)
                OfflineCommentData.Row(id, author, handle, user, source, translated, language, depth, replies, shortened, parent)
            }
            val replies = linkedMapOf<String, OfflineCommentData.ReplyCursor>()
            if (version >= 2) {
                val roots = input.readInt(); require(roots in 0..OfflineCommentData.MAX_ROWS)
                repeat(roots) {
                    val root = text(128); require(root.isNotBlank() && root !in replies)
                    val next = input.readLong(); require(next >= -1)
                    replies[root] = OfflineCommentData.ReplyCursor(next.takeIf { it >= 0 }, input.readBoolean())
                }
            }
            require(input.available() == 0)
            OfflineCommentData.Snapshot(account, video, capturedAt, rows, total, more, truncated, cursor.takeIf { it >= 0 }, retained, replies)
        }
    }
}
