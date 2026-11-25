package dev.cmfopen.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Minimal watch-side simulator for session tests (mirrors web/src/lib/transport/sim.ts). */
class SimWatch(private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)) : Transport {
    override val mtu = 247
    val secret = Crypto.random(16)
    val notifications = ArrayList<Payloads.Notification>()
    var music: Payloads.MusicInfo? = null
    var timeSynced = false
    /** Mimic firmware 1.0.1.77: chatter with unmapped frames right after login. */
    var chatterOnLogin = true
    private var handler: ((Channel, ByteArray) -> Unit)? = null
    private var authkey: ByteArray? = null
    private var key: ByteArray? = null
    private val dec = FrameDecoder(plaintextCrcOnWire = true)
    /** Outgoing notifications, delivered one at a time in order, off the caller's thread (like BLE). */
    private val outbox = kotlinx.coroutines.channels.Channel<Pair<Channel, ByteArray>>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    init {
        scope.launch { for ((ch, data) in outbox) handler?.invoke(ch, data) }
    }

    override fun setNotifyHandler(handler: (Channel, ByteArray) -> Unit) { this.handler = handler }

    private fun reply(op: Op, payload: ByteArray = ByteArray(0), bare: Boolean = false) {
        val k = key
        val frames = if (bare) listOf(Bytes.fromHex("f50000%04x00010001%04x".format(op.cmd1, op.cmd2)))
        else Frame.encode(op, payload, k, mtu, omitPlaintextCrc = true)
        frames.forEach { outbox.trySend(Channel.COMMAND to it) }
    }

    /** Recorded health messages served by the next fetch (then cleared, like the real watch). */
    val health = ArrayList<Message>()

    /** Send several messages strictly in order (separate [reply] calls may interleave). */
    private fun replyInOrder(messages: List<Message>) {
        val k = key
        val frames = messages.flatMap { Frame.encode(it.op, it.payload, k, mtu, omitPlaintextCrc = true) }
        frames.forEach { outbox.trySend(Channel.COMMAND to it) }
    }

    fun press(code: Int) = reply(Ops.MUSIC_BUTTON, byteArrayOf(code.toByte()))

    override suspend fun write(channel: Channel, data: ByteArray) {
        if (channel == Channel.SHELL) {
            val cmd = String(data).trim().removePrefix("AT").trim().uppercase()
            val out = if (cmd == "GETSECRET") "GETSECRET:${Bytes.hex(secret).uppercase()},OK" else "$cmd,ERROR"
            outbox.trySend(Channel.SHELL to out.toByteArray())
            return
        }
        dec.key = key
        for (msg in dec.feed(data)) dispatch(msg)
    }

    private fun setKey(k: ByteArray?) { key = k; dec.key = k }

    private fun dispatch(m: Message) {
        val p = m.payload
        when (m.op) {
            Ops.AUTH_PAIR_REQUEST -> {
                val rnd1 = p.copyOfRange(0, 16)
                if (!p.copyOfRange(16, 48).contentEquals(Crypto.sign(rnd1, secret))) return reply(Ops.AUTH_FAILED)
                val rnd2 = Crypto.random(16)
                reply(Ops.AUTH_PAIR_REPLY, Bytes.concat(rnd2, Crypto.sign(rnd2, secret)))
                authkey = Crypto.deriveAuthkey(rnd1, rnd2, secret)
                setKey(authkey)
            }
            Ops.AUTH_PHONE_NAME -> reply(Ops.AUTH_WATCH_MAC, Bytes.fromHex("D0C0FFEE5485"))
            Ops.AUTH_NONCE_REQUEST -> {
                val ak = authkey ?: return reply(Ops.AUTH_FAILED)
                val nonce = Crypto.random(16)
                reply(Ops.AUTH_NONCE_REPLY, nonce)
                setKey(Crypto.deriveSessionKey(nonce, ak))
            }
            Ops.AUTH_CONFIRM_REQUEST -> {
                reply(Ops.AUTH_CONFIRM_REPLY, byteArrayOf(1))
                if (chatterOnLogin) {
                    reply(Ops.UNKNOWN_LANGUAGE, "en".toByteArray())
                    reply(Ops.UNKNOWN_A06A, byteArrayOf(0))
                }
            }
            Ops.TIME -> timeSynced = true
            Ops.FIRMWARE_VERSION_GET -> reply(Ops.FIRMWARE_VERSION_RET, byteArrayOf(1, 0, 1, 77))
            Ops.BATTERY -> if (timeSynced) reply(Ops.BATTERY, byteArrayOf(87, 0))
            Ops.APP_NOTIFICATION -> {
                notifications += Payloads.parseNotification(p)
                reply(Ops.APP_NOTIFICATION_ACK, bare = true)
            }
            HealthOps.FETCH_START -> reply(HealthOps.FETCH_STATUS, byteArrayOf(1))
            HealthOps.FETCH_CONTINUE -> {
                val out = listOf(Message(HealthOps.FETCH_SINCE, Bytes.concat(Bytes.u32le(1_764_000_000), ByteArray(4)))) +
                    health + Message(HealthOps.FETCH_STATUS, byteArrayOf(2))
                health.clear()
                replyInOrder(out)
            }
            Ops.MUSIC_INFO_SET -> {
                music = Payloads.parseMusicInfo(p)
                reply(Ops.MUSIC_INFO_ACK)
            }
        }
    }

    /** Forget the session key, as a real watch does on disconnect. */
    fun disconnect() { setKey(authkey) }
}
