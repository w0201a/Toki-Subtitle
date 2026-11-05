package io.github.meiyongai.toki.subtitle.model

/** Structurally sound synthetic envelope; it is deliberately not evidence of a renderable glyph font. */
internal object FontTestFixture {
    fun tables(outline: String = "glyf"): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "head" to ByteArray(54).apply {
            put32(this, 0, 0x10000)
            put32(this, 12, 0x5f0f3cf5)
            put16(this, 18, 1000)
        },
        "hhea" to ByteArray(36).apply { put32(this, 0, 0x10000); put16(this, 34, 1) },
        "maxp" to ByteArray(if (outline == "glyf") 32 else 6).apply {
            put32(this, 0, if (outline == "glyf") 0x10000 else 0x5000)
            put16(this, 4, 2)
        },
        "hmtx" to ByteArray(6),
        "cmap" to ByteArray(274).apply {
            put16(this, 2, 1)
            put16(this, 4, 3)
            put16(this, 6, 1)
            put32(this, 8, 12)
            put16(this, 14, 262)
        },
        "name" to ByteArray(20).apply {
            put16(this, 2, 1)
            put16(this, 4, 18)
            put16(this, 6, 3)
            put16(this, 8, 1)
            put16(this, 12, 1)
            put16(this, 14, 2)
            this[19] = 65
        },
    ).apply {
        when (outline) {
            "glyf" -> {
                put("glyf", ByteArray(4))
                put("loca", ByteArray(6).apply { put16(this, 2, 2); put16(this, 4, 2) })
            }
            "CFF " -> put(outline, byteArrayOf(1, 0, 4, 4))
            "CFF2" -> put(outline, byteArrayOf(2, 0, 5, 0, 0))
        }
    }

    fun font(tables: LinkedHashMap<String, ByteArray> = tables(), signature: String? = null): ByteArray {
        var size = 12 + tables.size * 16
        val offsets = tables.mapValues { (_, bytes) ->
            size = (size + 3) and -4
            size.also { size += bytes.size }
        }
        return ByteArray(size).apply {
            if (signature == null) put32(this, 0, 0x10000)
            else signature.toByteArray(Charsets.US_ASCII).copyInto(this, 0)
            put16(this, 4, tables.size)
            tables.entries.forEachIndexed { index, (tag, bytes) ->
                val record = 12 + index * 16
                tag.toByteArray(Charsets.US_ASCII).copyInto(this, record)
                put32(this, record + 8, offsets.getValue(tag).toLong())
                put32(this, record + 12, bytes.size.toLong())
                bytes.copyInto(this, offsets.getValue(tag))
            }
        }
    }

    fun put16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value ushr 8).toByte()
        bytes[at + 1] = value.toByte()
    }

    fun put32(bytes: ByteArray, at: Int, value: Long) {
        bytes[at] = (value ushr 24).toByte()
        bytes[at + 1] = (value ushr 16).toByte()
        bytes[at + 2] = (value ushr 8).toByte()
        bytes[at + 3] = value.toByte()
    }

    fun u32(bytes: ByteArray, at: Int): Long = (0..3).fold(0L) { result, index ->
        (result shl 8) or (bytes[at + index].toLong() and 255)
    }
}
