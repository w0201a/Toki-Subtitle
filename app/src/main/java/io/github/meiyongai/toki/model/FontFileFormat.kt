package io.github.meiyongai.toki.subtitle.model

import java.io.File
import java.io.RandomAccessFile

/** Structural import gate only. Android Typeface must still validate and load the font. */
internal object FontFileFormat {
    const val MAX_BYTES = 64 * 1024 * 1024

    data class Info(val kind: String, val size: Long)

    fun inspect(file: File): Info {
        require(file.isFile) { "Font must be a regular file" }
        return RandomAccessFile(file, "r").use { input ->
            val fileSize = input.length()
            val result = inspect(object : Source {
                override val size = fileSize
                override fun read(offset: Long, count: Int): ByteArray {
                    checkRange(offset, count.toLong(), size, "Font read")
                    return ByteArray(count).also {
                        input.seek(offset)
                        input.readFully(it)
                    }
                }
            })
            require(input.length() == fileSize) { "Font changed during inspection" }
            result
        }
    }

    fun inspect(bytes: ByteArray): Info = inspect(object : Source {
        override val size = bytes.size.toLong()
        override fun read(offset: Long, count: Int): ByteArray {
            checkRange(offset, count.toLong(), size, "Font read")
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }
    })

    private interface Source {
        val size: Long
        fun read(offset: Long, count: Int): ByteArray
    }

    private data class Table(val tag: String, val offset: Long, val length: Long)

    private fun inspect(source: Source): Info {
        require(source.size <= MAX_BYTES) { "Font exceeds 64 MiB limit" }
        require(source.size >= 4) { "Font header is truncated" }
        val signature = source.read(0, 4).u32(0)
        val kind = when (signature) {
            0x00010000L -> "ttf"
            0x4f54544fL -> "otf"
            0x74746366L -> throw IllegalArgumentException("TTC/OTC font collections are unsupported; select a single TTF or OTF")
            0x774f4646L, 0x774f4632L -> throw IllegalArgumentException("WOFF/WOFF2 web fonts are unsupported; select a TTF or OTF")
            else -> throw IllegalArgumentException("Unsupported font signature; select a TTF or OTF")
        }
        require(source.size >= 12) { "Font sfnt header is truncated" }
        val header = source.read(0, 12)
        val count = header.u16(4)
        require(count > 0) { "Font table directory is empty" }
        val directoryEnd = 12L + count.toLong() * 16L
        checkRange(12, count.toLong() * 16L, source.size, "Font table directory")
        val directory = source.read(12, count * 16)
        val tables = LinkedHashMap<String, Table>()
        repeat(count) { index ->
            val at = index * 16
            val tagBytes = directory.copyOfRange(at, at + 4)
            require(tagBytes.all { (it.toInt() and 255) in 32..126 } && tagBytes.any { it != 32.toByte() }) {
                "Font table tag contains invalid characters"
            }
            val tag = String(tagBytes, Charsets.US_ASCII)
            require(!tables.containsKey(tag)) { "Duplicate font table: $tag" }
            val offset = directory.u32(at + 8)
            val length = directory.u32(at + 12)
            require(length == 0L || offset >= directoryEnd) { "Font table $tag overlaps the directory" }
            checkRange(offset, length, source.size, "Font table $tag")
            tables[tag] = Table(tag, offset, length)
        }
        var previousEnd = directoryEnd
        for (table in tables.values.filter { it.length > 0 }.sortedBy { it.offset }) {
            require(table.offset >= previousEnd) { "Font table ${table.tag} overlaps another table" }
            previousEnd = table.offset + table.length
        }
        fun required(tag: String, minimum: Long): Table {
            val table = requireNotNull(tables[tag]) { "Missing required font table: $tag" }
            require(table.length >= minimum) { "Font table $tag is truncated" }
            return table
        }
        val head = source.read(required("head", 54).offset, 54)
        require(head.u32(0) == 0x00010000L) { "Unsupported head table version" }
        require(head.u32(12) == 0x5f0f3cf5L) { "Invalid head table magic" }
        require(head.u16(18) in 16..16384) { "Invalid head unitsPerEm" }
        val hhea = source.read(required("hhea", 36).offset, 36)
        val maxpTable = required("maxp", 6)
        val maxp = source.read(maxpTable.offset, 6)
        when (maxp.u32(0)) {
            0x00005000L -> require(kind == "otf") { "TrueType requires maxp version 1.0" }
            0x00010000L -> require(maxpTable.length >= 32) { "Font table maxp is truncated" }
            else -> throw IllegalArgumentException("Unsupported maxp table version")
        }
        val glyphs = maxp.u16(4)
        require(glyphs > 0) { "Font has no glyphs" }
        val metrics = hhea.u16(34)
        require(metrics in 1..glyphs) { "Invalid hhea numberOfHMetrics" }
        required("hmtx", metrics.toLong() * 4 + (glyphs - metrics).toLong() * 2)
        inspectCmap(source, required("cmap", 4))
        inspectName(source, required("name", 6))
        if (kind == "ttf") {
            require(!tables.containsKey("CFF ") && !tables.containsKey("CFF2")) { "TrueType font contains incompatible CFF outlines" }
            val glyf = required("glyf", 0)
            val loca = required("loca", 0)
            val format = head.u16(50)
            require(format == 0 || format == 1) { "Invalid head indexToLocFormat" }
            val width = if (format == 0) 2 else 4
            val length = (glyphs + 1) * width
            require(loca.length >= length) { "Font table loca is truncated" }
            // At most 262144 bytes; never read the potentially large glyf payload.
            val offsets = source.read(loca.offset, length)
            var previous = 0L
            repeat(glyphs + 1) { index ->
                val offset = if (format == 0) offsets.u16(index * 2).toLong() * 2 else offsets.u32(index * 4)
                require(offset >= previous && offset <= glyf.length) { "Invalid loca glyph offset" }
                previous = offset
            }
        } else {
            require(!tables.containsKey("glyf") && !tables.containsKey("loca")) { "OpenType CFF font contains incompatible TrueType outlines" }
            val cff = tables["CFF "]
            val cff2 = tables["CFF2"]
            require((cff != null) != (cff2 != null)) { "OpenType requires exactly one CFF or CFF2 outline table" }
            val outlines = cff ?: cff2!!
            val minimum = if (cff != null) 4 else 5
            require(outlines.length >= minimum) { "Font outline table ${outlines.tag} is truncated" }
            val cffHeader = source.read(outlines.offset, minimum)
            require((cffHeader[0].toInt() and 255) == (if (cff != null) 1 else 2)) { "Invalid ${outlines.tag} header version" }
            val headerSize = cffHeader[2].toInt() and 255
            require(headerSize >= minimum && headerSize.toLong() <= outlines.length) { "Invalid ${outlines.tag} header size" }
            if (cff != null) {
                require((cffHeader[3].toInt() and 255) in 1..4) { "Invalid CFF offset size" }
            } else {
                require(cffHeader.u16(3).toLong() <= outlines.length - headerSize) { "CFF2 top dictionary is truncated" }
            }
        }
        return Info(kind, source.size)
    }

    private fun inspectCmap(source: Source, table: Table) {
        val header = source.read(table.offset, 4)
        require(header.u16(0) == 0) { "Unsupported cmap table version" }
        val count = header.u16(2)
        require(count > 0) { "Font cmap has no encoding records" }
        val recordEnd = 4L + count.toLong() * 8
        checkRange(4, count.toLong() * 8, table.length, "Font cmap records")
        val records = source.read(table.offset + 4, count * 8)
        repeat(count) { index ->
            val offset = records.u32(index * 8 + 4)
            require(offset >= recordEnd) { "Font cmap subtable overlaps encoding records" }
            checkRange(offset, 2, table.length, "Font cmap subtable")
        }
    }

    private fun inspectName(source: Source, table: Table) {
        val header = source.read(table.offset, 6)
        require(header.u16(0) in 0..1) { "Unsupported name table format" }
        val count = header.u16(2)
        val strings = header.u16(4).toLong()
        val recordEnd = 6L + count.toLong() * 12
        checkRange(6, count.toLong() * 12, table.length, "Font name records")
        var storageMinimum = recordEnd
        if (header.u16(0) == 1) {
            checkRange(recordEnd, 2, table.length, "Font name language tag count")
            val languageCount = source.read(table.offset + recordEnd, 2).u16(0)
            storageMinimum = recordEnd + 2 + languageCount.toLong() * 4
            checkRange(recordEnd + 2, languageCount.toLong() * 4, table.length, "Font name language records")
            val languages = source.read(table.offset + recordEnd + 2, languageCount * 4)
            repeat(languageCount) { index ->
                checkRange(strings + languages.u16(index * 4 + 2), languages.u16(index * 4).toLong(), table.length, "Font name language string")
            }
        }
        require(strings >= storageMinimum && strings <= table.length) { "Invalid name string storage offset" }
        val records = source.read(table.offset + 6, count * 12)
        repeat(count) { index ->
            val length = records.u16(index * 12 + 8).toLong()
            val offset = records.u16(index * 12 + 10).toLong()
            checkRange(strings + offset, length, table.length, "Font name string")
        }
    }

    private fun checkRange(offset: Long, length: Long, size: Long, label: String) {
        // Subtraction avoids overflow before an unsigned sfnt offset is validated.
        require(offset >= 0 && length >= 0 && offset <= size && length <= size - offset) { "$label is outside the font bounds or truncated" }
    }

    private fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 255) shl 8) or (this[at + 1].toInt() and 255)
    private fun ByteArray.u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
}
