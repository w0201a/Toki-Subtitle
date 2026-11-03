package io.github.meiyongai.toki.subtitle.hook

/** Container metadata only. Pixels must be decoded separately; a filename or a preview frame is not animation. */
internal object AnimatedStickerData {
    enum class Disposal { KEEP, CLEAR, RESTORE }
    data class Frame(val x: Int, val y: Int, val width: Int, val height: Int, val durationMs: Int,
        val blend: Boolean = true, val disposal: Disposal = Disposal.KEEP, val clearColor: Int = 0)
    data class Sequence(val width: Int, val height: Int, val frames: List<Frame>, val plays: Int,
        val background: Int, val source: String)
    const val MAX_FRAMES = 300
    const val MAX_PIXELS = 1_048_576L
    const val MAX_WORK_PIXELS = 64_000_000L
    const val MAX_DURATION_MS = 120_000L

    private class Input(val bytes: ByteArray, var position: Int = 0) {
        fun need(count: Int) { require(count >= 0 && position <= bytes.size - count) { "Truncated animation" } }
        fun byte(): Int { need(1); return bytes[position++].toInt() and 255 }
        fun short(): Int = byte() or (byte() shl 8)
        fun three(): Int = short() or (byte() shl 16)
        fun long(): Long = short().toLong() or (short().toLong() shl 16)
        fun text(count: Int): String { need(count); return String(bytes, position, count, Charsets.US_ASCII).also { position += count } }
        fun skip(count: Int) { need(count); position += count }
        fun subBlocks(first: (ByteArray, Int, Int) -> Unit = { _, _, _ -> }): Int {
            var total = 0; var initial = true
            while (true) {
                val size = byte(); if (size == 0) return total
                need(size)
                if (initial) { first(bytes, position, size); initial = false }
                skip(size); total += size
            }
        }
    }
    private fun dimensions(width: Int, height: Int) {
        require(width in 1..2048 && height in 1..2048 && width.toLong() * height <= MAX_PIXELS) { "Animation canvas too large" }
    }
    private fun validFrame(frame: Frame, width: Int, height: Int) {
        require(frame.x >= 0 && frame.y >= 0 && frame.width > 0 && frame.height > 0 && frame.durationMs >= 0)
        require(frame.width <= width - frame.x && frame.height <= height - frame.y) { "Frame outside animation canvas" }
    }
    private fun finish(width: Int, height: Int, frames: List<Frame>, plays: Int, background: Int, format: String): Sequence {
        dimensions(width, height)
        require(frames.size in 1..MAX_FRAMES && plays in 0..65536)
        require(width.toLong() * height * frames.size <= MAX_WORK_PIXELS) { "Animation pixel budget exceeded" }
        require(frames.sumOf { it.durationMs.toLong() } <= MAX_DURATION_MS) { "Animation duration budget exceeded" }
        frames.forEach { validFrame(it, width, height) }
        return Sequence(width, height, frames.toList(), plays, background, format)
    }
    fun parse(bytes: ByteArray): Sequence {
        require(bytes.size in 1..CommentStickerMedia.MAX_BYTES)
        return when (CommentStickerMedia.format(bytes)?.extension) {
            "gif" -> gif(bytes)
            "webp" -> webp(bytes)
            else -> error("Animated conversion requires GIF or WebP source bytes")
        }
    }
    private fun gif(bytes: ByteArray): Sequence {
        val input = Input(bytes)
        require(input.text(6) in setOf("GIF87a", "GIF89a"))
        val width = input.short(); val height = input.short(); dimensions(width, height)
        val flags = input.byte(); val backgroundIndex = input.byte(); input.byte()
        val globalColors = if (flags and 128 != 0) 1 shl ((flags and 7) + 1) else 0
        var backgroundColor = 0
        repeat(globalColors) { index ->
            val color = (255 shl 24) or (input.byte() shl 16) or (input.byte() shl 8) or input.byte()
            if (index == backgroundIndex) backgroundColor = color
        }
        if (globalColors > 0) require(backgroundIndex < globalColors)
        var delay = 0; var disposal = Disposal.KEEP; var transparent = -1
        var plays = 1; var loopSeen = false
        val frames = mutableListOf<Frame>()
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "Animation conversion cancelled" }
            when (input.byte()) {
                0x3b -> {
                    // A complete trailer is required, not EOF guessed as successful decoding.
                    require(input.position == bytes.size)
                    val background = if (frames.firstOrNull()?.clearColor == 0) 0 else backgroundColor
                    return finish(width, height, frames, plays, background, "gif")
                }
                0x21 -> when (input.byte()) {
                    0xf9 -> {
                        require(input.byte() == 4)
                        val control = input.byte(); delay = input.short() * 10
                        transparent = input.byte().takeIf { control and 1 != 0 } ?: -1
                        require(input.byte() == 0)
                        disposal = when ((control ushr 2) and 7) {
                            0, 1 -> Disposal.KEEP
                            2 -> Disposal.CLEAR
                            3 -> Disposal.RESTORE
                            else -> error("Unsupported GIF disposal")
                        }
                    }
                    0xff -> {
                        val application = input.text(input.byte())
                        input.subBlocks { data, offset, size ->
                            if (application in setOf("NETSCAPE2.0", "ANIMEXTS1.0")) {
                                require(size == 3 && data[offset].toInt() and 255 == 1)
                                val repetitions = (data[offset + 1].toInt() and 255) or ((data[offset + 2].toInt() and 255) shl 8)
                                val total = if (repetitions == 0) 0 else repetitions + 1
                                require(!loopSeen || plays == total); plays = total; loopSeen = true
                            }
                        }
                    }
                    0x01 -> error("GIF plain-text rendering cannot be preserved by the inspected image decoder")
                    0xfe -> input.subBlocks()
                    else -> error("Unsupported GIF extension")
                }
                0x2c -> {
                    val x = input.short(); val y = input.short(); val w = input.short(); val h = input.short()
                    val localFlags = input.byte()
                    val colors = if (localFlags and 128 != 0) 1 shl ((localFlags and 7) + 1) else globalColors
                    require(colors > 0 && transparent < colors)
                    if (localFlags and 128 != 0) input.skip(colors * 3)
                    require(input.byte() in 2..8)
                    require(input.subBlocks() > 0)
                    val clear = if (transparent == backgroundIndex || globalColors == 0) 0 else backgroundColor
                    val frame = Frame(x, y, w, h, delay, true, disposal, clear)
                    validFrame(frame, width, height)
                    require(frames.size < MAX_FRAMES); frames += frame
                    delay = 0; disposal = Disposal.KEEP; transparent = -1
                }
                else -> error("Invalid GIF block")
            }
        }
    }
    private fun webp(bytes: ByteArray): Sequence {
        val input = Input(bytes)
        require(input.text(4) == "RIFF" && input.long() == bytes.size.toLong() - 8 && input.text(4) == "WEBP")
        var width = 0; var height = 0; var background = 0; var plays = 1; var extended = false; var animation = false
        val frames = mutableListOf<Frame>()
        while (input.position < bytes.size) {
            check(!Thread.currentThread().isInterrupted) { "Animation conversion cancelled" }
            val name = input.text(4); val size = input.long()
            require(size <= Int.MAX_VALUE && size <= bytes.size - input.position)
            val end = input.position + size.toInt()
            when (name) {
                "VP8X" -> {
                    require(!extended && !animation && frames.isEmpty() && size == 10L)
                    require(input.byte() and 2 != 0) { "WebP source is not animated" }
                    input.skip(3); width = input.three() + 1; height = input.three() + 1
                    dimensions(width, height); extended = true
                }
                "ANIM" -> {
                    require(extended && !animation && frames.isEmpty() && size == 6L)
                    background = input.long().toInt(); plays = input.short(); animation = true
                }
                "ANMF" -> {
                    require(animation && size >= 16)
                    val x = input.three() * 2; val y = input.three() * 2
                    val w = input.three() + 1; val h = input.three() + 1
                    val duration = input.three(); val flags = input.byte()
                    require(flags and 0xfc == 0)
                    val frame = Frame(x, y, w, h, duration, flags and 2 == 0,
                        if (flags and 1 != 0) Disposal.CLEAR else Disposal.KEEP, background)
                    validFrame(frame, width, height)
                    var bitstreams = 0; var alpha = false; var lossless = false
                    while (input.position < end) {
                        require(end - input.position >= 8)
                        val type = input.text(4); val count = input.long()
                        require(count > 0 && count <= end - input.position)
                        when (type) {
                            "ALPH" -> { require(!alpha && bitstreams == 0); alpha = true }
                            "VP8 ", "VP8L" -> { bitstreams++; lossless = type == "VP8L" }
                            else -> error("Unsupported animated WebP frame payload")
                        }
                        input.skip(count.toInt()); if (count and 1L != 0L) input.skip(1)
                        require(input.position <= end)
                    }
                    require(input.position == end && bitstreams == 1 && !(alpha && lossless))
                    require(frames.size < MAX_FRAMES); frames += frame
                }
            }
            require(input.position <= end); input.position = end
            if (size and 1L != 0L) input.skip(1)
        }
        require(extended && animation && input.position == bytes.size)
        return finish(width, height, frames, plays, background, "webp")
    }
}
