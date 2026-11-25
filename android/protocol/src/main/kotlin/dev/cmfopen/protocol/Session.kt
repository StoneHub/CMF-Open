package dev.cmfopen.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class AuthException(message: String) : Exception(message)

/**
 * An authenticated, encrypted session with one watch. Port of src/cmfkit/session.py and
 * web/src/lib/session.ts, including fixes from testing on a Watch 3 Pro (firmware 1.0.1.77):
 * empty-body acks, and unknown frames on connect that must be tolerated.
 */
class Session(
    val transport: Transport,
    private val timeoutMs: Long = 8_000,
    /** Diagnostic log hook: direction ("->", "<-", "!!", "..") and text. */
    var log: (String, String) -> Unit = { _, _ -> },
) {
    var authkey: ByteArray? = null
        private set

    private val lock = Any()
    private var key: ByteArray? = null
    private val decoder = FrameDecoder()
    private val waiters = ArrayList<Pair<Set<Op>, CompletableDeferred<Message>>>()
    private val listeners = ArrayList<(Message) -> Unit>()
    /** Claim messages with these ops before they reach [listeners] (used while a fetch is running). */
    private val interceptors = ArrayList<Pair<Set<Op>, (Message) -> Unit>>()
    /** Keeps the frames of one message together when several coroutines send at once. */
    private val writeMutex = Mutex()
    private val shellBuf = StringBuilder()
    private var shellWaiter: CompletableDeferred<String>? = null

    init {
        transport.setNotifyHandler(::onNotify)
    }

    private fun setKey(k: ByteArray?) = synchronized(lock) {
        key = k
        decoder.key = k
    }

    /** Subscribe to unsolicited watch -> phone messages (buttons, find phone, unknown frames). */
    fun onMessage(fn: (Message) -> Unit): () -> Unit {
        synchronized(lock) { listeners += fn }
        return { synchronized(lock) { listeners -= fn } }
    }

    /** Route messages with [ops] to [fn] instead of the listeners until the returned function is called. */
    fun intercept(ops: Set<Op>, fn: (Message) -> Unit): () -> Unit {
        val entry = ops to fn
        synchronized(lock) { interceptors += entry }
        return { synchronized(lock) { interceptors.remove(entry) } }
    }

    private fun onNotify(channel: Channel, data: ByteArray) {
        if (channel == Channel.SHELL) {
            val done = synchronized(lock) {
                shellBuf.append(String(data, Charsets.UTF_8))
                val w = shellWaiter
                if (w != null && Regex("(OK|ERROR)\\s*$").containsMatchIn(shellBuf)) {
                    val s = shellBuf.toString().trim()
                    shellBuf.clear()
                    shellWaiter = null
                    w to s
                } else null
            }
            done?.let { (w, s) -> w.complete(s) }
            return
        }
        val deliveries = ArrayList<() -> Unit>()
        synchronized(lock) {
            val messages = try {
                decoder.feed(data)
            } catch (e: FrameException) {
                log("!!", "dropping bad frame: ${e.message} (raw ${Bytes.hex(data)})")
                decoder.reset()
                return
            }
            for (msg in messages) {
                log("<-", msg.toString())
                val i = waiters.indexOfFirst { msg.op in it.first }
                if (i >= 0) {
                    val w = waiters.removeAt(i).second
                    deliveries += { w.complete(msg) }
                } else if (interceptors.any { msg.op in it.first }) {
                    val fn = interceptors.first { msg.op in it.first }.second
                    deliveries += { fn(msg) }
                } else {
                    val ls = listeners.toList()
                    deliveries += { ls.forEach { runCatching { it(msg) }.onFailure { e -> log("!!", "listener: $e") } } }
                }
            }
        }
        deliveries.forEach { it() }
    }

    private fun expect(wanted: Set<Op>): CompletableDeferred<Message> =
        CompletableDeferred<Message>().also { synchronized(lock) { waiters += wanted to it } }

    private fun forget(d: CompletableDeferred<Message>) = synchronized(lock) { waiters.removeAll { it.second === d } }

    suspend fun send(op: Op, payload: ByteArray = ByteArray(0), channel: Channel = Channel.COMMAND) {
        log("->", "${Ops.nameOf(op)} ${Bytes.hex(payload).take(64)}")
        writeMutex.withLock {
            val k = synchronized(lock) { key }
            for (f in Frame.encode(op, payload, k, transport.mtu)) transport.write(channel, f)
        }
    }

    suspend fun request(
        op: Op,
        payload: ByteArray,
        reply: Set<Op>,
        timeoutMs: Long = this.timeoutMs,
        channel: Channel = Channel.COMMAND,
    ): Message {
        val pending = expect(reply)
        try {
            send(op, payload, channel)
            return withTimeout(timeoutMs) { pending.await() }
        } catch (e: TimeoutCancellationException) {
            throw java.util.concurrent.TimeoutException("no ${reply.joinToString("/") { Ops.nameOf(it) }} reply to ${Ops.nameOf(op)}")
        } finally {
            forget(pending)
        }
    }

    /** Send a plain-text AT command on the factory shell channel and wait for OK/ERROR. */
    suspend fun shell(command: String, timeoutMs: Long = this.timeoutMs): String {
        val line = if (command.uppercase().startsWith("AT")) command else "AT $command"
        val w = CompletableDeferred<String>()
        synchronized(lock) {
            shellBuf.clear()
            shellWaiter = w
        }
        try {
            writeMutex.withLock { transport.write(Channel.SHELL, (line + "\r\n").toByteArray()) }
            return withTimeout(timeoutMs) { w.await() }
        } catch (e: TimeoutCancellationException) {
            throw java.util.concurrent.TimeoutException("no reply to $line")
        } finally {
            synchronized(lock) { if (shellWaiter === w) shellWaiter = null }
        }
    }

    // -- auth ---------------------------------------------------------------

    /**
     * First-time pairing via the factory AT shell secret. Returns the auth key to persist.
     * [onKey] fires as soon as the key is negotiated (the watch has stored it by then), and
     * [onPrompt] when the watch is showing its "accept pairing" prompt.
     */
    suspend fun pair(secret: ByteArray? = null, phoneName: String = DEFAULT_PHONE_NAME, onKey: (ByteArray) -> Unit = {}, onPrompt: () -> Unit = {}): ByteArray {
        val s = secret ?: run {
            val reply = shell("AT GETSECRET")
            val m = Regex("GETSECRET:([0-9A-Fa-f]{32})").find(reply) ?: throw AuthException("unexpected GETSECRET reply: $reply")
            Bytes.fromHex(m.groupValues[1])
        }
        setKey(null)
        val rnd1 = Crypto.random(16)
        val msg = request(Ops.AUTH_PAIR_REQUEST, Bytes.concat(rnd1, Crypto.sign(rnd1, s)), setOf(Ops.AUTH_PAIR_REPLY, Ops.AUTH_FAILED))
        if (msg.op == Ops.AUTH_FAILED || msg.payload.size < 48) throw AuthException("watch rejected pairing")
        val rnd2 = msg.payload.copyOfRange(0, 16)
        if (!msg.payload.copyOfRange(16, 48).contentEquals(Crypto.sign(rnd2, s))) throw AuthException("watch signature mismatch (wrong secret?)")
        val ak = Crypto.deriveAuthkey(rnd1, rnd2, s)
        onKey(ak)
        // The watch now shows a pairing prompt and only answers once the wearer accepts it.
        onPrompt()
        login(ak, phoneName, helloTimeoutMs = 60_000)
        return ak
    }

    /** Reconnect handshake with a stored auth key, then sync the time (the watch ignores queries until then). */
    suspend fun login(authkey: ByteArray, phoneName: String = DEFAULT_PHONE_NAME, helloTimeoutMs: Long = timeoutMs * 3 / 2) {
        this.authkey = authkey
        setKey(authkey)
        val hello = Bytes.concat(Payloads.POLL, phoneName.toByteArray())
        val helloReply = setOf(Ops.AUTH_WATCH_MAC, Ops.AUTH_FAILED)
        val mac = try {
            request(Ops.AUTH_PHONE_NAME, hello, helloReply, helloTimeoutMs)
        } catch (e: java.util.concurrent.TimeoutException) {
            log("!!", "no reply to AUTH_PHONE_NAME, retrying once")
            request(Ops.AUTH_PHONE_NAME, hello, helloReply, timeoutMs * 3 / 2)
        }
        if (mac.op == Ops.AUTH_FAILED) throw AuthException("watch rejected the auth key")
        val nonce = request(Ops.AUTH_NONCE_REQUEST, Payloads.POLL, setOf(Ops.AUTH_NONCE_REPLY, Ops.AUTH_FAILED))
        if (nonce.op == Ops.AUTH_FAILED) throw AuthException("watch rejected the stored auth key")
        setKey(Crypto.deriveSessionKey(nonce.payload, authkey))
        val ok = request(Ops.AUTH_CONFIRM_REQUEST, Payloads.POLL, setOf(Ops.AUTH_CONFIRM_REPLY, Ops.AUTH_FAILED))
        if (ok.op == Ops.AUTH_FAILED) throw AuthException("session confirmation failed")
        syncTime()
    }

    // -- commands -------------------------------------------------------------

    suspend fun syncTime() = send(Ops.TIME, Payloads.timeSync())

    suspend fun firmwareVersion() =
        Payloads.parseFirmwareVersion(request(Ops.FIRMWARE_VERSION_GET, Payloads.POLL, setOf(Ops.FIRMWARE_VERSION_RET)).payload)

    suspend fun serialNumber() = Payloads.parseSerial(request(Ops.SERIAL_NUMBER_GET, Payloads.POLL, setOf(Ops.SERIAL_NUMBER_RET)).payload)

    suspend fun battery() = Payloads.parseBattery(request(Ops.BATTERY, Payloads.POLL, setOf(Ops.BATTERY)).payload)

    suspend fun notify(title: String, body: String, icon: Int = Payloads.Icons.GENERIC) =
        send(Ops.APP_NOTIFICATION, Payloads.notification(title, body, icon))

    suspend fun findWatch() = send(Ops.FIND_WATCH, byteArrayOf(1))

    suspend fun setMusic(track: String, artist: String, state: Int = Payloads.MUSIC_PLAYING, volume: Int = 8, volumeMax: Int = 16) =
        send(Ops.MUSIC_INFO_SET, Payloads.musicInfo(track, artist, state, volume, volumeMax))

    suspend fun setWeather(payload: ByteArray) = send(Ops.WEATHER_SET, payload)

    suspend fun setContacts(entries: List<Payloads.Contact>) = send(Ops.CONTACTS_SET, Payloads.contacts(entries))

    /** Short vibration via the factory shell. SET* commands often execute without echoing OK. */
    suspend fun buzz() {
        runCatching { shell("AT SETMOTOR=1", 2_000) }
    }

    suspend fun listDials() =
        Payloads.parseDialList(request(Ops.DIAL_COMMAND, Payloads.dialList(), setOf(Ops.DIAL_COMMAND_REPLY)).payload)

    suspend fun deleteDial(id: Long) = request(Ops.DIAL_COMMAND, Payloads.dialDelete(id), setOf(Ops.DIAL_COMMAND_REPLY)).payload.firstOrNull()?.toInt() == 1

    /** Upload a photo watchface container (.bin from the web face editor). True if the watch activated it. */
    suspend fun uploadPhotoDial(blob: ByteArray, style: Int = 0, x: Int = 56, y: Int = 77, color565: Int = 0xFFFF, progress: (Int, Int) -> Unit = { _, _ -> }) =
        upload(blob, Ops.WF_INIT2_PHOTO_REQUEST, Payloads.photoDialInit(blob.size, style, x, y, color565), Ops.WF_INIT2_PHOTO_REPLY, progress)

    /** Upload a structured dial, replacing installed dial [oldDialId] (see [listDials]). */
    suspend fun uploadStructuredDial(blob: ByteArray, oldDialId: Long, progress: (Int, Int) -> Unit = { _, _ -> }) =
        upload(blob, Ops.WF_INIT2_STRUCTURED_REQUEST, Payloads.structuredDialInit(blob.size, oldDialId), Ops.WF_INIT2_STRUCTURED_REPLY, progress)

    private suspend fun upload(blob: ByteArray, initOp: Op, init: ByteArray, initReply: Op, progress: (Int, Int) -> Unit): Boolean {
        // Watchface transfer uses the data GATT service, not command.
        val data = Channel.DATA
        val init1 = request(Ops.WF_INIT1_REQUEST, Payloads.POLL, setOf(Ops.WF_INIT1_REPLY), timeoutMs * 2, data)
        if (init1.payload.firstOrNull()?.toInt() != 1) throw IllegalStateException("watch refused the watchface transfer")
        val stepOps = setOf(Ops.WF_CHUNK_REQUEST, Ops.WF_FINISH_REQUEST)
        var pending = expect(stepOps)
        try {
            val init2 = request(initOp, init, setOf(initReply), timeoutMs * 2, data)
            if (init2.payload.firstOrNull()?.toInt() != 1) throw IllegalStateException("watch rejected the watchface header")
            while (true) {
                val msg = withTimeout(timeoutMs * 4) { pending.await() }
                if (msg.op == Ops.WF_FINISH_REQUEST) {
                    send(Ops.WF_FINISH_ACK, Payloads.POLL, data)
                    return msg.payload.firstOrNull()?.toInt() == 1
                }
                val (offset, length) = Payloads.chunkRequest(msg.payload)
                pending = expect(stepOps)
                send(Ops.WF_CHUNK_WRITE, blob.copyOfRange(offset, minOf(offset + length, blob.size)), data)
                progress(minOf(offset + length, blob.size), blob.size)
            }
        } finally {
            forget(pending)
        }
    }

    // -- health ---------------------------------------------------------------

    /**
     * Download recorded health data (see [HealthOps] for the flow). Every data message is passed to
     * [onData] as it arrives, so the caller can persist it before parsing. Returns the parsed batch;
     * `complete` is false if the watch went quiet before saying it was done.
     */
    suspend fun fetchHealth(
        idleTimeoutMs: Long = 20_000,
        totalTimeoutMs: Long = 5 * 60_000,
        onData: (Message) -> Unit = {},
    ): HealthFetch {
        val inbox = kotlinx.coroutines.channels.Channel<Message>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val release = intercept(HealthOps.ALL) { inbox.trySend(it) }
        val batch = Health.Batch()
        var since: Long? = null
        var started = false

        /** True when the watch says it is done, false when it goes quiet mid-transfer. */
        suspend fun drain(): Boolean {
            while (true) {
                val m = withTimeoutOrNull(if (started) idleTimeoutMs else timeoutMs) { inbox.receive() }
                if (m == null) {
                    if (started) return false
                    throw java.util.concurrent.TimeoutException("no reply to HEALTH_FETCH_START")
                }
                when (m.op) {
                    HealthOps.FETCH_STATUS -> when (m.payload.firstOrNull()?.toInt()) {
                        1 -> if (!started) {
                            started = true
                            send(HealthOps.FETCH_CONTINUE, Payloads.POLL)
                        }
                        2 -> return true
                        else -> log("!!", "unknown health fetch status ${Bytes.hex(m.payload)}")
                    }
                    HealthOps.FETCH_SINCE -> {
                        started = true
                        if (m.payload.size >= 4) since = Health.parseReadings(m.payload.copyOf(8))?.first()?.tsS
                    }
                    else -> {
                        started = true
                        onData(m)
                        if (!batch.add(m)) log("!!", "health message did not parse: $m")
                    }
                }
            }
        }

        try {
            send(HealthOps.FETCH_START, Payloads.POLL)
            val complete = withTimeoutOrNull(totalTimeoutMs) { drain() } ?: false
            return HealthFetch(batch, since, complete)
        } finally {
            release()
            inbox.close()
        }
    }

    data class HealthFetch(val batch: Health.Batch, val sinceS: Long?, val complete: Boolean)

    companion object {
        /** Shown on the watch as the paired phone's name. */
        const val DEFAULT_PHONE_NAME = "CMF Companion"
    }
}
