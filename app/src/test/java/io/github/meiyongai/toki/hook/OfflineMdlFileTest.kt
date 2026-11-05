package io.github.meiyongai.toki.subtitle.hook

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic metadata only. Real accounts, keys and video data must never enter this fixture. */
class OfflineMdlFileTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = "native-cache-key"
    private fun box(type: String, body: ByteArray) = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { it.writeInt(8 + body.size); it.writeBytes(type); it.write(body) }
    }.toByteArray()
    private fun mp4() = box("ftyp", "isom0000".toByteArray()) + box("moov", ByteArray(200) { 7 }) + box("mdat", ByteArray(512) { it.toByte() })
    private fun node(original: ByteArray, key: String = this.key): ByteArray {
        val record = 96 + key.length
        val bytes = ByteArray(record + 160)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun i(at: Int, value: Int) { b.putInt(at, value) }
        fun tag(at: Int, value: String) { value.toByteArray().copyInto(bytes, at) }
        i(0, bytes.size); tag(4, "ttmd"); i(12, 1); b.putLong(16, original.size.toLong())
        i(24, 32); i(28, record + 8); i(32, 56); tag(36, "mfni")
        b.putLong(56, original.size.toLong()); b.putLong(64, original.size.toLong())
        i(88, key.length + 8); tag(92, "fkey"); tag(96, key)
        i(record, bytes.size); tag(record + 4, "ttmd"); i(record + 8, 1); i(record + 12, 128)
        original.copyInto(bytes, record + 24, 0, 128)
        i(bytes.size - 8, bytes.size); tag(bytes.size - 4, "ttmd")
        return bytes
    }
    private data class Fixture(val media: File, val sidecar: File, val original: ByteArray)
    private fun fixture(): Fixture {
        val original = mp4()
        return Fixture(folder.newFile().apply { writeBytes(original.copyOf().apply { fill(0, 0, 128) }) },
            folder.newFile().apply { writeBytes(node(original)) }, original)
    }
    private fun inspect(f: Fixture, current: () -> Boolean = { true }) = OfflineMdlFile.inspect(f.media, f.sidecar, key, listOf(folder.root), current)
    private fun fails(reason: LocalVideoFile.Failure, action: () -> Unit) {
        assertEquals(reason, assertThrows(LocalVideoFile.Unavailable::class.java) { action() }.reason)
    }
    @Test fun copyReconstructsExactBytesWithoutChangingEitherSource() {
        val f = fixture(); val media = f.media.readBytes(); val sidecar = f.sidecar.readBytes()
        val verified = inspect(f); val output = ByteArrayOutputStream()
        LocalVideoFile.copy(verified, output)
        assertArrayEquals(f.original, output.toByteArray())
        assertArrayEquals(media, f.media.readBytes()); assertArrayEquals(sidecar, f.sidecar.readBytes())
    }
    @Test fun randomRangesOverlayOnlyRetainedHeaderAndRespectDestinationOffset() {
        val f = fixture(); val verified = inspect(f)
        for ((position, count) in listOf(0 to 16, 100 to 70, 127 to 3, 128 to 64, 200 to 99)) {
            val bytes = ByteArray(count + 10) { 91 }; f.media.readBytes().copyInto(bytes, 5, position, position + count)
            verified.restoration!!.overlay(position.toLong(), bytes, 5, count)
            assertArrayEquals(f.original.copyOfRange(position, position + count), bytes.copyOfRange(5, 5 + count))
            assertEquals(91.toByte(), bytes[0]); assertEquals(91.toByte(), bytes.last())
        }
    }
    @Test fun sidecarKeyMustMatchNativeDbKey() {
        val f = fixture(); fails(LocalVideoFile.Failure.UNSUPPORTED) { OfflineMdlFile.inspect(f.media, f.sidecar, "different-key", listOf(folder.root)) }
    }
    @Test fun partialRangeAndUnknownVersionNeverClaimComplete() {
        for (offset in listOf(12, 24, 28, 32, 40, 48, 72, 80, 88, 92, 96, 96 + key.length + 8, 96 + key.length + 12, 96 + key.length + 16)) {
            val bytes = node(mp4()); bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
            fails(LocalVideoFile.Failure.UNSUPPORTED) { OfflineMdlFile.parse(bytes, key) }
        }
        for (offset in listOf(16, 56, 64)) {
            val bytes = node(mp4()); bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
            fails(LocalVideoFile.Failure.INCOMPLETE) { OfflineMdlFile.parse(bytes, key) }
        }
    }
    @Test fun malformedBoundedRecordsAndUnsafeKeysAreRejected() {
        val bytes = node(mp4())
        for (size in listOf(0, 32, 255, bytes.size - 1, bytes.size + 1, 4097))
            fails(LocalVideoFile.Failure.UNSUPPORTED) { OfflineMdlFile.parse(bytes.copyOf(size), key) }
        for (value in listOf("", "../other", "a/b", "x\\y", "a.b", "x".repeat(513), "a\n")) assertFalse(OfflineMdlFile.validKey(value))
    }
    @Test fun missingMediaAndMismatchedLengthsAreNotComplete() {
        val f = fixture(); f.media.appendBytes(byteArrayOf(0))
        fails(LocalVideoFile.Failure.INCOMPLETE) { inspect(f) }
        fails(LocalVideoFile.Failure.MISSING) { OfflineMdlFile.inspect(File(folder.root, "missing"), f.sidecar, key, listOf(folder.root)) }
    }
    @Test fun conflictingHeaderIsNotSilentlyOverwritten() {
        val f = fixture(); f.media.writeBytes(f.original)
        fails(LocalVideoFile.Failure.UNSUPPORTED) { inspect(f) }
    }
    @Test fun accountChangeAndCancellationStopBeforeCopy() {
        val f = fixture(); var current = true; val verified = inspect(f) { current }
        val output = ByteArrayOutputStream(); current = false
        fails(LocalVideoFile.Failure.CHANGED) { LocalVideoFile.copy(verified, output) }; assertEquals(0, output.size())
        current = true
        fails(LocalVideoFile.Failure.CHANGED) { LocalVideoFile.copy(verified, output) { false } }; assertEquals(0, output.size())
    }
    @Test fun changedSidecarCannotBeUsedAfterValidation() {
        val f = fixture(); val verified = inspect(f)
        f.sidecar.appendBytes(byteArrayOf(0))
        fails(LocalVideoFile.Failure.CHANGED) { LocalVideoFile.verify(verified) }
    }
    @Test fun sameSizeSidecarMutationIsDetectedAtFullBoundaryEvenWithRestoredTimestamp() {
        val f = fixture(); val verified = inspect(f); val modified = f.sidecar.lastModified()
        f.sidecar.writeBytes(f.sidecar.readBytes().apply { this[8] = (this[8].toInt() xor 1).toByte() })
        assertTrue(f.sidecar.setLastModified(modified))
        fails(LocalVideoFile.Failure.CHANGED) { verified.restoration!!.verify(true) }
    }
    @Test fun corruptContainerAfterHeaderRestorationIsRejected() {
        val f = fixture(); val bytes = f.media.readBytes(); ByteBuffer.wrap(bytes).putInt(224, Int.MAX_VALUE); f.media.writeBytes(bytes)
        fails(LocalVideoFile.Failure.INCOMPLETE) { inspect(f) }
    }
    @Test fun neitherMediaNorSidecarMayEscapeAllowedRoots() {
        val f = fixture(); val other = folder.newFolder()
        fails(LocalVideoFile.Failure.OUTSIDE_STORAGE) { OfflineMdlFile.inspect(f.media, f.sidecar, key, listOf(other)) }
        fails(LocalVideoFile.Failure.OUTSIDE_STORAGE) { OfflineMdlFile.inspect(File(other, "missing"), f.sidecar, key, listOf(other)) }
    }
}
