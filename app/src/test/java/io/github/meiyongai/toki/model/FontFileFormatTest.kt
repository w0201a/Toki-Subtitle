package io.github.meiyongai.toki.subtitle.model

import io.github.meiyongai.toki.subtitle.model.FontTestFixture.font
import io.github.meiyongai.toki.subtitle.model.FontTestFixture.tables
import io.github.meiyongai.toki.subtitle.model.FontTestFixture.put16
import io.github.meiyongai.toki.subtitle.model.FontTestFixture.put32
import io.github.meiyongai.toki.subtitle.model.FontTestFixture.u32
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic sfnt envelopes exercise structural validation, not glyph rendering or Typeface loading. */
class FontFileFormatTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun acceptsStructuralTrueTypeWithShortLoca() {
        val font = font()
        assertEquals(FontFileFormat.Info("ttf", font.size.toLong()), FontFileFormat.inspect(font))
    }

    @Test fun acceptsStructuralTrueTypeWithLongLoca() {
        val tables = tables()
        put16(tables.getValue("head"), 50, 1)
        tables["loca"] = ByteArray(12).apply { put32(this, 4, 4); put32(this, 8, 4) }
        assertEquals("ttf", FontFileFormat.inspect(font(tables)).kind)
    }

    @Test fun acceptsStructuralOpenTypeCff() {
        assertEquals("otf", FontFileFormat.inspect(font(tables("CFF "), "OTTO")).kind)
    }

    @Test fun acceptsStructuralOpenTypeCff2() {
        assertEquals("otf", FontFileFormat.inspect(font(tables("CFF2"), "OTTO")).kind)
    }

    @Test fun acceptsEmptyGlyfWhenAllGlyphOffsetsAreEmpty() {
        val tables = tables()
        tables["glyf"] = byteArrayOf()
        tables["loca"] = ByteArray(6)
        assertEquals("ttf", FontFileFormat.inspect(font(tables)).kind)
    }

    @Test fun acceptsZeroLengthGlyfAtOffsetZero() {
        val tables = tables()
        tables["glyf"] = byteArrayOf()
        tables["loca"] = ByteArray(6)
        val bytes = font(tables)
        val glyfRecord = 12 + tables.keys.indexOf("glyf") * 16
        put32(bytes, glyfRecord + 8, 0)
        assertEquals("ttf", FontFileFormat.inspect(bytes).kind)
    }

    @Test fun acceptsNameFormatOneWithLanguageTag() {
        val tables = tables()
        tables["name"] = ByteArray(18).apply {
            put16(this, 0, 1)
            put16(this, 4, 12)
            put16(this, 6, 1)
            put16(this, 8, 6)
            byteArrayOf(0, 101, 0, 110, 0, 103).copyInto(this, 12)
        }
        assertEquals("ttf", FontFileFormat.inspect(font(tables)).kind)
        put16(tables.getValue("name"), 10, 65535)
        rejectedBytes(font(tables), "language string")
    }

    @Test fun rejectsTruncatedNameFormatOneLanguageRecords() {
        rejectedBytes(font(tables().apply {
            this["name"] = ByteArray(6).apply { put16(this, 0, 1); put16(this, 4, 6) }
        }), "language tag count")
        rejectedBytes(font(tables().apply {
            this["name"] = ByteArray(8).apply {
                put16(this, 0, 1)
                put16(this, 4, 8)
                put16(this, 6, 65535)
            }
        }), "language records")
    }

    @Test fun acceptsAdditionalPrintableTableTagsAndTrailingFilePadding() {
        val tables = tables().apply { put("TEST", byteArrayOf(1, 2, 3)) }
        val bytes = font(tables).copyOf(font(tables).size + 7)
        assertEquals(bytes.size.toLong(), FontFileFormat.inspect(bytes).size)
    }

    @Test fun fileInspectionAgreesWithBytesAndDoesNotChangeInput() {
        val bytes = font()
        val file = temporary.newFile("selected-font.bin").apply { writeBytes(bytes) }
        assertEquals(FontFileFormat.inspect(bytes), FontFileFormat.inspect(file))
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test fun rejectsDirectoriesAndMissingFiles() {
        rejected("regular file") { FontFileFormat.inspect(temporary.root) }
        rejected("regular file") { FontFileFormat.inspect(File(temporary.root, "missing.ttf")) }
    }

    @Test fun rejectsOversizedFileBeforeReadingContents() {
        val file = temporary.newFile("oversized.ttf")
        RandomAccessFile(file, "rw").use { it.setLength(FontFileFormat.MAX_BYTES.toLong() + 1) }
        rejected("64 MiB") { FontFileFormat.inspect(file) }
        assertEquals(FontFileFormat.MAX_BYTES.toLong() + 1, file.length())
    }

    @Test fun rejectsOversizedBytesBeforeParsingSignature() {
        rejected("64 MiB") { FontFileFormat.inspect(ByteArray(FontFileFormat.MAX_BYTES + 1)) }
    }

    @Test fun rejectsCollectionsAndWebFontsWithSpecificErrors() {
        rejectedBytes("ttcf".toByteArray(Charsets.US_ASCII), "collections")
        for (signature in listOf("wOFF", "wOF2")) {
            rejectedBytes(signature.toByteArray(Charsets.US_ASCII), "web fonts")
        }
    }

    @Test fun rejectsUnknownSignatureAndTruncatedHeaders() {
        rejectedBytes(byteArrayOf(1, 2, 3, 4), "signature")
        for (size in 0..3) rejectedBytes(ByteArray(size), "truncated")
        for (size in 4..11) rejectedBytes(font().copyOf(size), "header")
    }

    @Test fun rejectsEmptyAndTruncatedDirectories() {
        rejectedBytes(font().apply { put16(this, 4, 0) }, "empty")
        rejectedBytes(font().copyOf(20), "directory")
        rejectedBytes(font().apply { put16(this, 4, 65535) }, "directory")
    }

    @Test fun rejectsNonPrintableBlankAndDuplicateTags() {
        rejectedBytes(font().apply { this[12] = 0 }, "tag")
        rejectedBytes(font().apply { repeat(4) { this[12 + it] = 32 } }, "tag")
        rejectedBytes(font().apply { copyInto(this, 28, 12, 16) }, "Duplicate")
    }

    @Test fun treatsOffsetsAndLengthsAsUnsignedWithoutOverflow() {
        rejectedBytes(font().apply { put32(this, 20, 0xfffffff0L) }, "bounds")
        rejectedBytes(font().apply { put32(this, 24, 0xffffffffL) }, "bounds")
        rejectedBytes(font().apply {
            put32(this, 20, 0xfffffff0L)
            put32(this, 24, 0xfffffff0L)
        }, "bounds")
    }

    @Test fun rejectsTableDirectoryOverlapAndTableOverlap() {
        rejectedBytes(font().apply { put32(this, 20, 12) }, "directory")
        rejectedBytes(font().apply { put32(this, 36, u32(this, 20)) }, "overlaps another")
        val bytes = font()
        rejectedBytes(bytes.copyOf(bytes.size - 1), "bounds")
    }

    @Test fun rejectsEachMissingRequiredTable() {
        for (tag in listOf("head", "hhea", "maxp", "hmtx", "cmap", "name", "glyf", "loca")) {
            rejectedBytes(font(tables().apply { remove(tag) }), tag)
        }
    }

    @Test fun rejectsShortFixedTables() {
        for ((tag, size) in listOf("head" to 53, "hhea" to 35, "maxp" to 5, "hmtx" to 5, "cmap" to 3, "name" to 5, "loca" to 5)) {
            rejectedBytes(font(tables().apply { this[tag] = getValue(tag).copyOf(size) }), tag)
        }
    }

    @Test fun rejectsInvalidHeadMagicVersionUnitsAndLocaFormat() {
        rejectedBytes(changed("head") { put32(it, 12, 0) }, "magic")
        rejectedBytes(changed("head") { put32(it, 0, 0) }, "version")
        for (units in listOf(0, 15, 16385, 65535)) {
            rejectedBytes(changed("head") { put16(it, 18, units) }, "unitsPerEm")
        }
        rejectedBytes(changed("head") { put16(it, 50, 2) }, "indexToLocFormat")
    }

    @Test fun acceptsUnitsPerEmBoundaries() {
        for (units in listOf(16, 16384)) {
            assertEquals("ttf", FontFileFormat.inspect(changed("head") { put16(it, 18, units) }).kind)
        }
    }

    @Test fun rejectsInvalidMaxpVersionAndMissingGlyphs() {
        rejectedBytes(changed("maxp") { put32(it, 0, 0x12345678) }, "version")
        rejectedBytes(changed("maxp") { put32(it, 0, 0x5000) }, "TrueType")
        rejectedBytes(changed("maxp") { put16(it, 4, 0) }, "no glyphs")
        rejectedBytes(font(tables().apply { this["maxp"] = getValue("maxp").copyOf(6) }), "maxp")
    }

    @Test fun rejectsInvalidMetricCountsAndTruncatedMetricData() {
        for (count in listOf(0, 3, 65535)) {
            rejectedBytes(changed("hhea") { put16(it, 34, count) }, "numberOfHMetrics")
        }
        // Two full metrics require eight bytes, rather than one metric plus a trailing bearing.
        rejectedBytes(changed("hhea") { put16(it, 34, 2) }, "hmtx")
    }

    @Test fun rejectsDecreasingOrOutOfRangeShortAndLongLoca() {
        rejectedBytes(changed("loca") { put16(it, 2, 3) }, "loca glyph")
        rejectedBytes(changed("loca") { put16(it, 4, 1) }, "loca glyph")
        val tables = tables()
        put16(tables.getValue("head"), 50, 1)
        tables["loca"] = ByteArray(12).apply { put32(this, 4, 0xffffffffL) }
        rejectedBytes(font(tables), "loca glyph")
    }

    @Test fun rejectsInvalidCmapVersionRecordCountAndSubtableOffsets() {
        rejectedBytes(changed("cmap") { put16(it, 0, 1) }, "cmap")
        rejectedBytes(changed("cmap") { put16(it, 2, 0) }, "encoding")
        rejectedBytes(changed("cmap") { put16(it, 2, 65535) }, "cmap records")
        rejectedBytes(changed("cmap") { put32(it, 8, 0) }, "overlaps")
        rejectedBytes(changed("cmap") { put32(it, 8, 0xffffffffL) }, "cmap subtable")
    }

    @Test fun rejectsInvalidNameFormatRecordsAndStringRanges() {
        rejectedBytes(changed("name") { put16(it, 0, 2) }, "name")
        rejectedBytes(changed("name") { put16(it, 2, 65535) }, "name records")
        rejectedBytes(changed("name") { put16(it, 4, 0) }, "storage")
        rejectedBytes(changed("name") { put16(it, 14, 65535) }, "name string")
        rejectedBytes(changed("name") { put16(it, 16, 65535) }, "name string")
    }

    @Test fun rejectsIncompatibleOrMissingOutlineTables() {
        rejectedBytes(font(tables().apply { put("CFF ", byteArrayOf(1, 0, 4, 4)) }), "incompatible")
        rejectedBytes(font(tables("CFF ").apply { put("glyf", byteArrayOf()) }, "OTTO"), "incompatible")
        rejectedBytes(font(tables("CFF ").apply { remove("CFF ") }, "OTTO"), "exactly one")
        rejectedBytes(font(tables("CFF ").apply { put("CFF2", byteArrayOf(2, 0, 5, 0, 0)) }, "OTTO"), "exactly one")
    }

    @Test fun rejectsMalformedCffAndCff2Headers() {
        fun cff(bytes: ByteArray) = font(tables("CFF ").apply { this["CFF "] = bytes }, "OTTO")
        rejectedBytes(cff(byteArrayOf(1, 0, 4)), "truncated")
        rejectedBytes(cff(byteArrayOf(2, 0, 4, 4)), "version")
        rejectedBytes(cff(byteArrayOf(1, 0, 3, 4)), "header size")
        rejectedBytes(cff(byteArrayOf(1, 0, 5, 4)), "header size")
        rejectedBytes(cff(byteArrayOf(1, 0, 4, 0)), "offset size")
        rejectedBytes(font(tables("CFF2").apply { this["CFF2"] = byteArrayOf(2, 0, 5, 0, 1) }, "OTTO"), "dictionary")
    }

    private fun changed(tag: String, change: (ByteArray) -> Unit): ByteArray =
        font(tables().also { change(it.getValue(tag)) })

    private fun rejectedBytes(bytes: ByteArray, message: String) = rejected(message) { FontFileFormat.inspect(bytes) }

    private fun rejected(message: String, block: () -> Unit) {
        try {
            block()
            fail("Expected font rejection containing: $message")
        } catch (error: IllegalArgumentException) {
            assertTrue("Expected '$message' in '${error.message}'", error.message.orEmpty().contains(message))
        }
    }

}
