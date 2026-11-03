package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test

class CommentSnapshotCodecTest {
    private fun snapshot() = OfflineCommentData.Snapshot("a".repeat(64), "video", 1000,
        listOf(OfflineCommentData.Row("root", "作者", "handle", "uid", "line one\nline two 🌍", "译文\u0000尾", "zh-hans", 0, 1),
            OfflineCommentData.Row("reply", "reply author", "", "2", "reply", null, "", 1, 0, parentId = "root")),
        100, true, true, 40, true)
    @Test fun binaryRoundTripPreservesControlCharactersIdentityCursorAndReplyParents() {
        val snapshot = snapshot()
        assertEquals(snapshot, CommentSnapshotCodec.decode(CommentSnapshotCodec.encode(snapshot)))
    }
    @Test fun damagedTruncatedOrTrailingDataIsNotLoadedAsAnEmptySuccess() {
        val bytes = CommentSnapshotCodec.encode(snapshot())
        val wrongVersion = bytes.clone().apply { this[7] = 7 }
        assertThrows(Exception::class.java) { CommentSnapshotCodec.decode(wrongVersion) }
        assertThrows(Exception::class.java) { CommentSnapshotCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows(Exception::class.java) { CommentSnapshotCodec.decode(bytes + byteArrayOf(1)) }
        assertThrows(Exception::class.java) { CommentSnapshotCodec.decode(ByteArray(CommentSnapshotCodec.MAX_BYTES + 1)) }
    }
    @Test fun invalidIdentityDuplicateRowsBadLengthsAndMalformedUnicodeAreRejected() {
        val value = snapshot()
        assertThrows(IllegalArgumentException::class.java) { CommentSnapshotCodec.encode(value.copy(account = "unknown")) }
        assertThrows(IllegalArgumentException::class.java) { CommentSnapshotCodec.encode(value.copy(rows = listOf(value.rows[0], value.rows[0]))) }
        assertThrows(IllegalArgumentException::class.java) { CommentSnapshotCodec.encode(value.copy(nextCursor = -2)) }
        assertThrows(IllegalArgumentException::class.java) { CommentSnapshotCodec.encode(value.copy(rows = listOf(value.rows[0].copy(source = "a".repeat(16001))))) }
        assertThrows(IllegalArgumentException::class.java) { CommentSnapshotCodec.encode(value.copy(rows = listOf(value.rows[0].copy(source = "\uD800")))) }
    }
}
