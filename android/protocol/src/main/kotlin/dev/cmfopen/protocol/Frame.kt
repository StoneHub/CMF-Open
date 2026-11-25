package dev.cmfopen.protocol

/**
 * The 0xF5 frame: `F5 | chunkLen u16 | cmd1 u16 | count u16 | index u16 | cmd2 u16 | chunk` (big-endian).
 * Each chunk is `piece || CRC32_LE(piece)`, AES-encrypted unless the opcode is plaintext.
 * Plaintext frames sent by the watch count the CRC in chunkLen but omit it on the wire.
 */
object Frame {
    const val MAGIC = 0xF5
    const val HEADER_LEN = 11
    internal const val MAX_CHUNK = 1024

    fun isEncrypted(op: Op, key: ByteArray?) = key != null && op !in Ops.PLAINTEXT

    fun chunkCapacity(mtu: Int, encrypted: Boolean): Int {
        val maxWrite = mtu - 3
        val cap = if (encrypted) ((maxWrite - HEADER_LEN) / 16) * 16 - 4 - 1 else maxWrite - HEADER_LEN - 4 - 2
        if (cap < 1) throw FrameException("MTU $mtu is too small; negotiate a larger MTU first")
        return cap
    }

    private fun header(chunkLen: Int, op: Op, count: Int, index: Int) = Bytes.concat(
        byteArrayOf(MAGIC.toByte()), Bytes.u16be(chunkLen), Bytes.u16be(op.cmd1),
        Bytes.u16be(count), Bytes.u16be(index), Bytes.u16be(op.cmd2),
    )

    /** Encode [payload] as frames, each meant for a single BLE write. */
    fun encode(op: Op, payload: ByteArray, key: ByteArray?, mtu: Int = 247, omitPlaintextCrc: Boolean = false): List<ByteArray> {
        val encrypted = isEncrypted(op, key)
        val cap = chunkCapacity(mtu, encrypted)
        val pieces = if (payload.isEmpty()) listOf(ByteArray(0))
        else (payload.indices step cap).map { payload.copyOfRange(it, minOf(it + cap, payload.size)) }
        return pieces.mapIndexed { i, piece ->
            var body = Bytes.concat(piece, Crypto.crc32le(piece))
            if (encrypted) body = Crypto.aesEncrypt(key!!, body)
            val wire = if (omitPlaintextCrc && !encrypted) piece else body
            Bytes.concat(header(body.size, op, pieces.size, i + 1), wire)
        }
    }
}

class FrameException(message: String) : Exception(message)

data class Message(val op: Op, val payload: ByteArray) {
    override fun equals(other: Any?) = other is Message && other.op == op && other.payload.contentEquals(payload)
    override fun hashCode() = op.hashCode() * 31 + payload.contentHashCode()
    override fun toString() = "${Ops.nameOf(op)} ${Bytes.hex(payload)}"
}

/**
 * Reassembles a notification byte stream into messages. Not thread-safe: callers serialise [feed].
 *
 * @param plaintextCrcOnWire describes the sender. The real watch omits the CRC on plaintext frames.
 */
class FrameDecoder(private val plaintextCrcOnWire: Boolean = false) {
    var key: ByteArray? = null
    private var buf = ByteArray(0)
    private val partial = HashMap<Op, MutableList<ByteArray>>()

    fun reset() {
        buf = ByteArray(0)
        partial.clear()
    }

    fun feed(data: ByteArray): List<Message> {
        buf = Bytes.concat(buf, data)
        val out = ArrayList<Message>()
        while (true) {
            val start = buf.indexOf(Frame.MAGIC.toByte())
            if (start < 0) {
                buf = ByteArray(0)
                return out
            }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < Frame.HEADER_LEN) return out
            val chunkLen = Bytes.readU16be(buf, 1)
            val op = Op(Bytes.readU16be(buf, 3), Bytes.readU16be(buf, 9))
            val count = Bytes.readU16be(buf, 5)
            val index = Bytes.readU16be(buf, 7)
            if (chunkLen > Frame.MAX_CHUNK || count == 0 || index == 0 || index > count) {
                // A stray 0xF5 that isn't a frame start; skipping it keeps the stream moving.
                buf = buf.copyOfRange(1, buf.size)
                continue
            }
            val encrypted = Frame.isEncrypted(op, key)
            val wireLen = if (!encrypted && !plaintextCrcOnWire) maxOf(chunkLen - 4, 0) else chunkLen
            if (buf.size < Frame.HEADER_LEN + wireLen) return out
            val body = buf.copyOfRange(Frame.HEADER_LEN, Frame.HEADER_LEN + wireLen)
            buf = buf.copyOfRange(Frame.HEADER_LEN + wireLen, buf.size)
            val piece = try {
                open(body, encrypted)
            } catch (e: FrameException) {
                // The watch may answer a wrong key with an AUTH_FAILED we can't decrypt.
                if (op != Ops.AUTH_FAILED) throw e
                ByteArray(0)
            }
            if (!encrypted && !plaintextCrcOnWire && buf.size >= 4 && piece.isNotEmpty()) {
                // Some firmware does put the CRC of plaintext chunks on the wire.
                val crc = Crypto.crc32le(piece)
                if ((0 until 4).all { crc[it] == buf[it] }) buf = buf.copyOfRange(4, buf.size)
            }
            if (count <= 1) {
                out += Message(op, piece)
                continue
            }
            val parts = if (index == 1) ArrayList() else partial.getOrPut(op) { ArrayList() }
            parts += piece
            partial[op] = parts
            if (index == count) {
                out += Message(op, Bytes.concat(*parts.toTypedArray()))
                partial.remove(op)
            }
        }
    }

    private fun open(body: ByteArray, encrypted: Boolean): ByteArray {
        // Bare acks (e.g. 0065:0003 after a notification) carry no body, even on encrypted ops.
        if (body.isEmpty()) return body
        var plain = body
        if (encrypted) {
            plain = try {
                Crypto.aesDecrypt(key!!, body)
            } catch (e: Exception) {
                throw FrameException("AES decryption failed (wrong key?)")
            }
        } else if (!plaintextCrcOnWire) {
            return body
        }
        if (plain.size < 4) throw FrameException("chunk shorter than its CRC")
        val piece = plain.copyOfRange(0, plain.size - 4)
        val crc = Crypto.crc32le(piece)
        if (!(0 until 4).all { crc[it] == plain[plain.size - 4 + it] }) throw FrameException("CRC mismatch")
        return piece
    }
}
