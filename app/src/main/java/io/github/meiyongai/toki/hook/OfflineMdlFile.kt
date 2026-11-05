package io.github.meiyongai.toki.subtitle.hook

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Read-only adapter for the observed complete MDL v1 sidecar profile, not a general MDL decoder. */
internal object OfflineMdlFile {
    data class Header(val total: Long, val prefix: ByteArray)
    class Restoration(val node: File, private val original: ByteArray, val header: Header,
        private val modified: Long, private val ownerCurrent: () -> Boolean) {
        fun verify(full: Boolean = false) {
            if (!ownerCurrent() || !node.isFile || node.length() != original.size.toLong() || node.lastModified() != modified ||
                (full && !node.readBytes().contentEquals(original))) fail(LocalVideoFile.Failure.CHANGED)
        }
        fun overlay(position: Long, bytes: ByteArray, offset: Int, count: Int) {
            if (position < header.prefix.size) {
                val n = minOf(count, header.prefix.size - position.toInt())
                header.prefix.copyInto(bytes, offset, position.toInt(), position.toInt() + n)
            }
        }
    }
    private fun fail(reason: LocalVideoFile.Failure = LocalVideoFile.Failure.UNSUPPORTED): Nothing = throw LocalVideoFile.Unavailable(reason)
    fun validKey(key: String): Boolean = key.length in 1..512 && key.matches(Regex("[A-Za-z0-9_-]+"))

    /** Strict sizes/tags, single complete range, exact native key, one offset-zero 128-byte retained header. */
    fun parse(bytes: ByteArray, key: String): Header {
        if (!validKey(key) || bytes.size !in 256..4096) fail()
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun i(at: Int) = b.getInt(at)
        fun q(at: Int) = b.getLong(at)
        fun tag(at: Int, text: String) = bytes.copyOfRange(at, at + 4).contentEquals(text.toByteArray(Charsets.US_ASCII))
        val keyBytes = key.toByteArray(Charsets.US_ASCII)
        val record = 96 + keyBytes.size
        if (bytes.size != record + 160 || i(0) != bytes.size || !tag(4, "ttmd") || i(12) != 1 ||
            i(24) != 32 || i(28) != record + 8 || i(32) != 56 || !tag(36, "mfni") ||
            q(40) != 0L || q(48) != 0L || q(72) != 0L || q(80) != 0L ||
            i(88) != keyBytes.size + 8 || !tag(92, "fkey") ||
            !bytes.copyOfRange(96, record).contentEquals(keyBytes) ||
            i(record) != bytes.size || !tag(record + 4, "ttmd") || i(record + 8) != 1 ||
            i(record + 12) != 128 || q(record + 16) != 0L ||
            i(bytes.size - 8) != bytes.size || !tag(bytes.size - 4, "ttmd")) fail()
        val total = q(16)
        if (total !in 128..8L * 1024 * 1024 * 1024 || q(56) != total || q(64) != total)
            fail(LocalVideoFile.Failure.INCOMPLETE)
        val prefix = bytes.copyOfRange(record + 24, record + 152)
        if (!prefix.copyOfRange(4, 8).contentEquals("ftyp".toByteArray(Charsets.US_ASCII))) fail()
        return Header(total, prefix)
    }

    fun inspect(media: File, sidecar: File, key: String, roots: List<File>, ownerCurrent: () -> Boolean = { true }): LocalVideoFile.Verified {
        val node = sidecar.canonicalFile
        if (roots.none { node.toPath().startsWith(it.canonicalFile.toPath()) && node != it.canonicalFile })
            fail(LocalVideoFile.Failure.OUTSIDE_STORAGE)
        if (!node.isFile || !node.canRead()) fail(LocalVideoFile.Failure.MISSING)
        if (node.length() !in 256..4096) fail()
        val modified = node.lastModified()
        val bytes = node.readBytes()
        val header = parse(bytes, key)
        val restoration = Restoration(node, bytes, header, modified, ownerCurrent)
        restoration.verify(true)
        // Only the observed zeroed prefix may be replaced; never overwrite conflicting media bytes.
        val source = media.canonicalFile
        if (roots.none { source.toPath().startsWith(it.canonicalFile.toPath()) && source != it.canonicalFile })
            fail(LocalVideoFile.Failure.OUTSIDE_STORAGE)
        if (!source.isFile || !source.canRead()) fail(LocalVideoFile.Failure.MISSING)
        RandomAccessFile(source, "r").use { input ->
            if (input.length() != header.total) fail(LocalVideoFile.Failure.INCOMPLETE)
            val prefix = ByteArray(128); input.readFully(prefix)
            if (prefix.any { it != 0.toByte() }) fail()
        }
        return LocalVideoFile.inspect(source, header.total, header.total, roots, restoration).also { restoration.verify(true) }
    }
}
