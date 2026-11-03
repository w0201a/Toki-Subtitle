package io.github.meiyongai.toki.subtitle.hook

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/** Local bytes only. Cache completeness must come from the cache index, never from file existence alone. */
internal object LocalVideoFile {
    enum class Failure { MISSING, INCOMPLETE, UNSUPPORTED, OUTSIDE_STORAGE, CHANGED }
    class Unavailable(val reason: Failure) : Exception(reason.name)
    data class Verified(val file: File, val size: Long, val modified: Long)

    fun inspect(source: File, total: Long, cached: Long, roots: List<File>): Verified {
        val file = source.canonicalFile
        if (roots.none { root -> file.toPath().startsWith(root.canonicalFile.toPath()) && file != root.canonicalFile })
            throw Unavailable(Failure.OUTSIDE_STORAGE)
        if (!file.isFile || !file.canRead()) throw Unavailable(Failure.MISSING)
        if (total <= 0 || total > 8L * 1024 * 1024 * 1024 || cached != total || file.length() != total)
            throw Unavailable(Failure.INCOMPLETE)
        RandomAccessFile(file, "r").use { input ->
            var offset = 0L
            var boxes = 0
            var ftyp = false; var moov = false; var mdat = false
            while (offset < total) {
                if (++boxes > 4096 || total - offset < 8) throw Unavailable(Failure.UNSUPPORTED)
                input.seek(offset)
                var size = input.readInt().toLong() and 0xffffffffL
                val type = input.readInt()
                var header = 8L
                if (size == 1L) { if (total - offset < 16) throw Unavailable(Failure.INCOMPLETE); size = input.readLong(); header = 16 }
                if (size == 0L) size = total - offset
                if (size < header || size > total - offset) throw Unavailable(Failure.INCOMPLETE)
                when (type) {
                    0x66747970 -> ftyp = size >= header + 8 // ftyp
                    0x6d6f6f76 -> moov = size > header // moov
                    0x6d646174 -> mdat = size > header // mdat
                    0x6d6f6f66 -> throw Unavailable(Failure.UNSUPPORTED) // separate/fragmented tracks need explicit muxing
                }
                offset += size
            }
            if (!ftyp || !moov || !mdat) throw Unavailable(Failure.UNSUPPORTED)
            if (input.length() != total) throw Unavailable(Failure.CHANGED)
        }
        return Verified(file, total, file.lastModified())
    }

    fun copy(source: Verified, output: OutputStream, active: () -> Boolean = { true }) {
        RandomAccessFile(source.file, "r").use { input ->
            fun verify() {
                if (!active() || input.length() != source.size || source.file.lastModified() != source.modified)
                    throw Unavailable(Failure.CHANGED)
            }
            verify()
            val buffer = ByteArray(128 * 1024)
            var remaining = source.size
            while (remaining > 0) {
                verify()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count <= 0) throw Unavailable(Failure.INCOMPLETE)
                output.write(buffer, 0, count); remaining -= count
            }
            verify()
            output.flush()
        }
    }
}
