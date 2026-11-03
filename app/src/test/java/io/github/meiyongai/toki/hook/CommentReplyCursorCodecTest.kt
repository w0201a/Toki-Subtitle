package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class CommentReplyCursorCodecTest {
    private fun empty() = OfflineCommentData.Snapshot("a".repeat(64), "v", 1, emptyList(), 0, false, false)
    @Test fun v2PreservesEveryRootCursorWithoutMixingWithTheVideoCursor() {
        val value = empty().copy(nextCursor = 60, hasMore = true, replyCursors = linkedMapOf(
            "root1" to OfflineCommentData.ReplyCursor(20, true), "root2" to OfflineCommentData.ReplyCursor(80, false)))
        assertEquals(value, CommentSnapshotCodec.decode(CommentSnapshotCodec.encode(value)))
    }
    @Test fun olderV1SnapshotsRemainReadableWithoutRewritingOrRemovingThem() {
        val current = CommentSnapshotCodec.encode(empty())
        assertEquals(0, ByteBuffer.wrap(current, current.size - 4, 4).int)
        val v1 = current.copyOf(current.size - 4)
        ByteBuffer.wrap(v1).putInt(4, 1)
        assertEquals(empty(), CommentSnapshotCodec.decode(v1))
        assertEquals(1, ByteBuffer.wrap(v1).getInt(4))
    }
    @Test fun damagedCursorValuesDuplicateRootsAndOversizedRootMapsAreRejected() {
        val value = empty().copy(replyCursors = mapOf("root" to OfflineCommentData.ReplyCursor(-2, true)))
        assertThrows(IllegalArgumentException::class.java) { CommentSnapshotCodec.encode(value) }
        val oversized = empty().copy(replyCursors = (1..501).associate { it.toString() to OfflineCommentData.ReplyCursor(20, true) })
        assertThrows(IllegalArgumentException::class.java) { CommentSnapshotCodec.encode(oversized) }
        val bytes = CommentSnapshotCodec.encode(empty())
        ByteBuffer.wrap(bytes).putInt(bytes.size - 4, 1)
        assertThrows(Exception::class.java) { CommentSnapshotCodec.decode(bytes) }
    }
}
