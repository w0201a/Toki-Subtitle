package io.github.meiyongai.toki.subtitle.hook

import java.io.ByteArrayOutputStream

/** Real container writers. GIF is indexed/quantized; WebP frame payloads come from actual Bitmap compression. */
internal object AnimatedStickerContainers {
    private class Sink {
        private val output = ByteArrayOutputStream()
        fun byte(value: Int) { check(output.size() < CommentStickerMedia.MAX_BYTES); output.write(value and 255) }
        fun short(value: Int) { require(value in 0..65535); byte(value); byte(value ushr 8) }
        fun three(value: Int) { require(value in 0..0xffffff); byte(value); byte(value ushr 8); byte(value ushr 16) }
        fun long(value: Int) { byte(value); byte(value ushr 8); byte(value ushr 16); byte(value ushr 24) }
        fun text(value: String) { value.forEach { byte(it.code) } }
        fun data(bytes: ByteArray, start: Int = 0, count: Int = bytes.size) {
            require(start >= 0 && count >= 0 && start <= bytes.size - count)
            check(output.size().toLong() + count <= CommentStickerMedia.MAX_BYTES)
            check(!Thread.currentThread().isInterrupted) { "Animation conversion cancelled" }
            output.write(bytes, start, count)
        }
        fun bytes(): ByteArray = output.toByteArray()
    }
    class Gif(private val sequence: AnimatedStickerData.Sequence) {
        private val output = Sink()
        private var frames = 0
        private var sourceMs = 0L
        private var encodedCs = 0L
        private var closed = false
        init {
            output.text("GIF89a"); output.short(sequence.width); output.short(sequence.height)
            output.byte(0xf7); output.byte(0); output.byte(0)
            // One transparent entry and 216 web-safe RGB colors. Remaining palette entries are unused.
            repeat(256) { index ->
                val value = index - 1
                if (index in 1..216) {
                    output.byte((value / 36) * 51); output.byte(((value / 6) % 6) * 51); output.byte((value % 6) * 51)
                } else { output.byte(0); output.byte(0); output.byte(0) }
            }
            if (sequence.plays != 1) {
                output.byte(0x21); output.byte(0xff); output.byte(11); output.text("NETSCAPE2.0")
                output.byte(3); output.byte(1); output.short(if (sequence.plays == 0) 0 else sequence.plays - 1); output.byte(0)
            }
        }
        fun frame(pixels: IntArray, durationMs: Int) {
            check(!closed && frames < sequence.frames.size)
            require(pixels.size == sequence.width * sequence.height && durationMs >= 0)
            sourceMs += durationMs
            val totalCs = (sourceMs + 5) / 10
            val delay = (totalCs - encodedCs).toInt(); encodedCs = totalCs
            // Every output frame is a fully composed canvas. Clearing before the next one preserves transparent changes.
            output.byte(0x21); output.byte(0xf9); output.byte(4); output.byte(9)
            output.short(delay); output.byte(0); output.byte(0)
            output.byte(0x2c); output.short(0); output.short(0)
            output.short(sequence.width); output.short(sequence.height); output.byte(0); output.byte(8)
            val block = ByteArray(255); var used = 0; var bits = 0; var bitCount = 0
            fun packedByte(value: Int) {
                block[used++] = value.toByte()
                if (used == 255) { output.byte(used); output.data(block, 0, used); used = 0 }
            }
            fun code(value: Int) {
                require(value in 0..511)
                bits = bits or (value shl bitCount); bitCount += 9
                while (bitCount >= 8) { packedByte(bits and 255); bits = bits ushr 8; bitCount -= 8 }
            }
            fun color(pixel: Int): Int {
                if ((pixel ushr 24) < 128) return 0
                fun channel(shift: Int): Int = (((pixel ushr shift) and 255) + 25) / 51
                return 1 + channel(16) * 36 + channel(8) * 6 + channel(0)
            }
            // Reset below 512 entries: all emitted codes stay 9-bit, including the decoder's one-entry lag.
            // Unlike a literal-only stream, short repeated runs still use dictionary codes.
            val dictionary = HashMap<Int, Int>(256)
            var next = 258; var prefix = color(pixels[0]); code(256)
            for (index in 1 until pixels.size) {
                if (index and 4095 == 0) check(!Thread.currentThread().isInterrupted)
                val current = color(pixels[index]); val key = (prefix shl 8) or current
                val existing = dictionary[key]
                if (existing != null) prefix = existing
                else {
                    code(prefix)
                    if (next < 500) dictionary[key] = next++
                    else { code(256); dictionary.clear(); next = 258 }
                    prefix = current
                }
            }
            code(prefix); code(257)
            if (bitCount > 0) packedByte(bits and 255)
            if (used > 0) { output.byte(used); output.data(block, 0, used) }
            output.byte(0); frames++
        }
        fun finish(): ByteArray {
            check(!closed && frames == sequence.frames.size); closed = true
            output.byte(0x3b); return output.bytes()
        }
    }
    class Webp(private val sequence: AnimatedStickerData.Sequence) {
        private val output = Sink()
        private var frames = 0
        private var alpha = false
        private var closed = false
        init {
            require(sequence.plays in 0..65535) { "WebP cannot represent this GIF repetition count" }
            output.text("RIFF"); output.long(0); output.text("WEBP")
            output.text("VP8X"); output.long(10); output.byte(2); repeat(3) { output.byte(0) }
            output.three(sequence.width - 1); output.three(sequence.height - 1)
            output.text("ANIM"); output.long(6); output.long(sequence.background); output.short(sequence.plays)
        }
        private fun number(bytes: ByteArray, offset: Int): Long {
            require(offset >= 0 && offset <= bytes.size - 4)
            return (0..3).fold(0L) { result, index -> result or ((bytes[offset + index].toLong() and 255) shl (8 * index)) }
        }
        fun frame(encodedStaticWebp: ByteArray, durationMs: Int) {
            check(!closed && frames < sequence.frames.size)
            require(durationMs in 0..0xffffff)
            val bytes = encodedStaticWebp
            require(bytes.size in 20..CommentStickerMedia.MAX_BYTES && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" && number(bytes, 4) == bytes.size.toLong() - 8)
            var offset = 12; var bitstreams = 0; var alphaChunk = false; var lossless = false
            val chunks = mutableListOf<Pair<Int, Int>>()
            while (offset < bytes.size) {
                require(offset <= bytes.size - 8)
                val name = String(bytes, offset, 4, Charsets.US_ASCII); val size = number(bytes, offset + 4)
                require(size <= bytes.size.toLong() - offset - 8)
                val count = 8L + size + (size and 1L)
                require(count <= bytes.size - offset)
                when (name) {
                    "ALPH" -> { require(!alphaChunk && bitstreams == 0 && size > 0); alphaChunk = true; alpha = true; chunks += offset to count.toInt() }
                    "VP8 ", "VP8L" -> {
                        require(size > 0); bitstreams++; lossless = name == "VP8L"
                        if (lossless) {
                            require(size >= 5 && bytes[offset + 8].toInt() and 255 == 0x2f)
                            if (bytes[offset + 12].toInt() and 16 != 0) alpha = true
                        }
                        chunks += offset to count.toInt()
                    }
                    "VP8X" -> require(size == 10L && bytes[offset + 8].toInt() and 2 == 0)
                    else -> error("Unexpected payload in encoded static WebP")
                }
                offset += count.toInt()
            }
            require(offset == bytes.size && bitstreams == 1 && !(alphaChunk && lossless))
            val payloadSize = chunks.sumOf { it.second }
            output.text("ANMF"); output.long(16 + payloadSize)
            output.three(0); output.three(0); output.three(sequence.width - 1); output.three(sequence.height - 1)
            output.three(durationMs); output.byte(2) // Full canvas replacement, no blending with the preceding output frame.
            chunks.forEach { (start, count) -> output.data(bytes, start, count) }
            frames++
        }
        fun finish(): ByteArray {
            check(!closed && frames == sequence.frames.size); closed = true
            return output.bytes().also { bytes ->
                val size = bytes.size - 8
                repeat(4) { index -> bytes[4 + index] = (size ushr (8 * index)).toByte() }
                if (alpha) bytes[20] = (bytes[20].toInt() or 16).toByte()
            }
        }
    }
}
