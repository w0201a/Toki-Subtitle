package io.github.meiyongai.toki.subtitle.hook

/** Locale-free digest encoding: String.format on every byte can re-enter host locale hooks during bulk migration. */
internal object DigestHex {
    private const val DIGITS="0123456789abcdef"
    fun encode(bytes:ByteArray):String {
        val out=CharArray(bytes.size*2)
        bytes.forEachIndexed{index,byte->val value=byte.toInt() and 255;out[index*2]=DIGITS[value ushr 4];out[index*2+1]=DIGITS[value and 15]}
        return String(out)
    }
}
