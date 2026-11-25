package dev.cmfopen.protocol

/** Small byte helpers shared by the protocol layer. */
object Bytes {
    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun fromHex(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() }
        require(clean.length % 2 == 0) { "odd-length hex" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** UTF-8, byte-truncated to [size] (may split a multi-byte character, like the firmware). */
    fun clip(text: String, size: Int): ByteArray = text.toByteArray(Charsets.UTF_8).let { if (it.size > size) it.copyOf(size) else it }

    /** UTF-8, byte-truncated and zero-padded to exactly [size]. */
    fun fixed(text: String, size: Int): ByteArray = clip(text, size).copyOf(size)

    /** Decode a zero-padded UTF-8 field. */
    fun str(b: ByteArray): String {
        val end = b.indexOf(0).let { if (it < 0) b.size else it }
        return String(b, 0, end, Charsets.UTF_8)
    }

    fun u16be(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
    fun u32be(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    fun u32be(v: Int) = u32be(v.toLong() and 0xFFFFFFFFL)
    fun u32le(v: Long) = byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    fun readU16be(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
    fun readU32be(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var i = 0
        for (p in parts) { p.copyInto(out, i); i += p.size }
        return out
    }
}

internal fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
