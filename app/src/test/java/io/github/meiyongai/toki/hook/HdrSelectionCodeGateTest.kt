package io.github.meiyongai.toki.subtitle.hook

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class HdrSelectionCodeGateTest {
    private val payload = "inspected player bytes".toByteArray()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun archive(entries: Map<String, ByteArray>): String {
        // Retain tiny fixtures: no automatic deletion of user filesystem contents.
        val file = Files.createTempFile("toki-hdr-codegate-", ".zip")
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return file.toString()
    }
    @Test fun matchingInspectedDexAcrossBaseAndSplitIsAccepted() {
        val a = archive(mapOf("classes2.dex" to payload)); val b = archive(mapOf("classes4.dex" to payload))
        assertTrue(HdrSelectionContract.verifyPaths(listOf(a,b), mapOf("classes2.dex" to hash(payload), "classes4.dex" to hash(payload))))
    }
    @Test fun changedOrMissingDexRejectsBeforeObfuscatedHooks() {
        val a = archive(mapOf("classes2.dex" to payload))
        assertFalse(HdrSelectionContract.verifyPaths(listOf(a), mapOf("classes2.dex" to hash("different".toByteArray()))))
        assertFalse(HdrSelectionContract.verifyPaths(listOf(a), mapOf("classes4.dex" to hash(payload))))
        assertFalse(HdrSelectionContract.verifyPaths(listOf(a), emptyMap()))
    }
    @Test fun duplicateDexOwnershipIsNotTreatedAsProvenRuntimeCode() {
        val a = archive(mapOf("classes2.dex" to payload)); val b = archive(mapOf("classes2.dex" to payload))
        assertFalse(HdrSelectionContract.verifyPaths(listOf(a,b), mapOf("classes2.dex" to hash(payload))))
    }
}
