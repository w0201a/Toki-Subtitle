package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

/** Tests the pure file validator/copier, not Android decodability, cache binding or gallery publication. */
class LocalVideoFileTest {
    @get:Rule val folder = TemporaryFolder()
    private fun box(type: String, body: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { it.writeInt(8 + body.size); it.writeBytes(type); it.write(body) }
    }.toByteArray()
    private fun bytes() = box("ftyp", "isom0000".toByteArray()) + box("moov", byteArrayOf(1)) + box("mdat", ByteArray(512) { it.toByte() })
    private fun file(name: String = "cache"): File = folder.newFile(name).also { it.writeBytes(bytes()) }
    private fun failure(reason: LocalVideoFile.Failure, action: () -> Unit) {
        val error = assertThrows(LocalVideoFile.Unavailable::class.java) { action() }
        assertEquals(reason, error.reason)
    }
    @Test fun completeLocalBytesAreCopiedExactlyWithoutChangingTheSource() {
        val source = file()
        val original = source.readBytes()
        val verified = LocalVideoFile.inspect(source, source.length(), source.length(), listOf(folder.root))
        val output = ByteArrayOutputStream()
        LocalVideoFile.copy(verified, output)
        assertArrayEquals(original, output.toByteArray())
        assertArrayEquals(original, source.readBytes())
    }
    @Test fun fileExistenceDoesNotProveCompletenessAndUnknownOrWrongSizesAreRefused() {
        val source = file()
        for ((total, cached) in listOf(0L to 0L, source.length() to 1L, source.length() + 1 to source.length() + 1)) {
            failure(LocalVideoFile.Failure.INCOMPLETE) { LocalVideoFile.inspect(source, total, cached, listOf(folder.root)) }
        }
    }
    @Test fun pathsOutsideAllowedRootsAndFragmentedMediaAreRefused() {
        val source = file()
        val other = folder.newFolder("different")
        failure(LocalVideoFile.Failure.OUTSIDE_STORAGE) { LocalVideoFile.inspect(source, source.length(), source.length(), listOf(other)) }
        source.appendBytes(box("moof", byteArrayOf(1)))
        failure(LocalVideoFile.Failure.UNSUPPORTED) { LocalVideoFile.inspect(source, source.length(), source.length(), listOf(folder.root)) }
    }
    @Test fun truncatedBoxesAndChangedSourceAreNotReportedAsComplete() {
        val source = file()
        source.appendBytes(byteArrayOf(0, 0, 0, 100, 109, 100, 97, 116))
        failure(LocalVideoFile.Failure.INCOMPLETE) { LocalVideoFile.inspect(source, source.length(), source.length(), listOf(folder.root)) }
        val second = file("second")
        val verified = LocalVideoFile.inspect(second, second.length(), second.length(), listOf(folder.root))
        second.appendBytes(byteArrayOf(0))
        failure(LocalVideoFile.Failure.CHANGED) { LocalVideoFile.copy(verified, ByteArrayOutputStream()) }
    }
    @Test fun cancelledExportsDoNotWriteOrModifySource() {
        val source = file()
        val verified = LocalVideoFile.inspect(source, source.length(), source.length(), listOf(folder.root))
        val output = ByteArrayOutputStream()
        failure(LocalVideoFile.Failure.CHANGED) { LocalVideoFile.copy(verified, output) { false } }
        assertEquals(0, output.size())
        assertTrue(source.exists())
    }
}
