package io.github.meiyongai.toki.subtitle.hook

import android.media.MediaDataSource
import java.io.RandomAccessFile

/** Virtual local MP4: overlay the saved native header while leaving both cache files untouched. */
internal class OfflineMdlDataSource(private val source: LocalVideoFile.Verified, private val active: () -> Boolean) : MediaDataSource() {
    private val input = RandomAccessFile(source.file, "r")
    private var closed = false
    @Synchronized override fun getSize(): Long { LocalVideoFile.verify(source, active); return source.size }
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        LocalVideoFile.verify(source, active)
        check(!closed)
        if (size == 0) return 0
        if (position >= source.size) return -1
        val count = minOf(size.toLong(), source.size - position).toInt()
        input.seek(position)
        input.readFully(buffer, offset, count)
        source.restoration?.overlay(position, buffer, offset, count)
        LocalVideoFile.verify(source, active)
        return count
    }
    @Synchronized override fun close() { if (!closed) { closed = true; input.close() } }
}
