package dev.cmfopen.protocol

/** The three GATT channels the watch exposes. */
enum class Channel { COMMAND, SHELL, DATA }

/** A link to one watch. Implemented by the Android BLE transport and by the test simulator. */
interface Transport {
    /** Negotiated ATT MTU. Frames are sized so each fits one write. */
    val mtu: Int

    /** Install the handler for incoming notifications. May be called on any thread. */
    fun setNotifyHandler(handler: (Channel, ByteArray) -> Unit)

    /** Write one frame (or one shell line). Implementations must serialise writes. */
    suspend fun write(channel: Channel, data: ByteArray)
}
