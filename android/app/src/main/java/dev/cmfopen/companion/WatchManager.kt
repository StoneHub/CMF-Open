package dev.cmfopen.companion

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dev.cmfopen.companion.apps.AppRuntime
import dev.cmfopen.companion.ble.BleTransport
import dev.cmfopen.companion.media.MediaBridge
import dev.cmfopen.companion.weather.WeatherSync
import dev.cmfopen.protocol.AuthException
import dev.cmfopen.protocol.Message
import dev.cmfopen.protocol.Ops
import dev.cmfopen.protocol.Payloads
import dev.cmfopen.protocol.Session
import dev.cmfopen.protocol.WatchButton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the one connection to the watch: connect, pair or log in, keep it alive, reconnect with
 * backoff, and route everything the rest of the app wants to send or receive.
 */
class WatchManager(private val context: Context, private val prefs: Prefs) {
    enum class Phase { IDLE, CONNECTING, PAIRING, AWAITING_ACCEPT, CONNECTED, WAITING_RETRY, NEEDS_PAIRING }

    data class State(
        val phase: Phase = Phase.IDLE,
        val firmware: String? = null,
        val battery: Int? = null,
        val charging: Boolean = false,
        val error: String? = null,
        val lastButton: String? = null,
        val retryInS: Int? = null,
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val media = MediaBridge(context, prefs, this)
    val weather = WeatherSync(context, prefs, this)
    val contacts = ContactsSync(context, prefs, this)
    val apps = AppRuntime(context, prefs, this)
    val ringer = PhoneRinger(context)

    @Volatile private var session: Session? = null
    private var transport: BleTransport? = null
    private var loop: Job? = null
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    /** Pair on the next attempt even if a key is stored (user asked). */
    @Volatile private var forcePair = false

    private data class Outgoing(val title: String, val body: String, val icon: Int)
    private val notifyQueue = Channel<Outgoing>(32, BufferOverflow.DROP_OLDEST)

    val connected get() = session != null && _state.value.phase == Phase.CONNECTED

    init {
        scope.launch {
            // One notification at a time, spaced out, so bursts don't overrun the watch.
            for (n in notifyQueue) {
                withSession("notification") { it.notify(n.title, n.body, n.icon) }
                delay(400)
            }
        }
    }

    fun hasBluetoothPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    // -- lifecycle --------------------------------------------------------------

    /** Start (or nudge) the connection loop. Safe to call repeatedly. */
    fun start() {
        if (prefs.deviceAddress == null || !hasBluetoothPermission()) return
        if (loop?.isActive == true) {
            kicks.trySend(Unit)
            return
        }
        loop = scope.launch { connectionLoop() }
    }

    fun stop() {
        val l = loop
        loop = null
        scope.launch {
            // The loop closes its own transport when cancelled.
            l?.cancelAndJoin()
            _state.update { State(lastButton = it.lastButton) }
        }
    }

    /** Use this watch from now on. */
    fun select(device: BluetoothDevice, name: String) {
        if (prefs.deviceAddress != device.address) stop()
        prefs.deviceAddress = device.address
        prefs.deviceName = name
        WatchService.start(context)
        start()
    }

    /** Pair again (new key). The watch will show a prompt to accept. */
    fun pairAgain() {
        forcePair = true
        if (loop?.isActive == true) {
            transport?.close()
            kicks.trySend(Unit)
        } else start()
    }

    fun forget() {
        prefs.deviceAddress?.let { prefs.setAuthkey(it, null) }
        prefs.deviceAddress = null
        prefs.deviceName = null
        stop()
        WatchService.stop(context)
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectionLoop() {
        var failures = 0
        while (scope.isActive) {
            val address = prefs.deviceAddress ?: return
            val dropped = CompletableDeferred<Unit>()
            try {
                connectOnce(address, dropped)
                failures = 0
                dropped.await()
                DebugLog.warn("watch disconnected")
            } catch (e: kotlinx.coroutines.CancellationException) {
                teardown()
                throw e
            } catch (e: AuthException) {
                DebugLog.warn("auth failed: ${e.message}")
                teardown()
                _state.update { it.copy(phase = Phase.NEEDS_PAIRING, error = "The watch rejected the saved key. Tap Pair to pair again.") }
                // Never re-pair on our own: each attempt pops a prompt on the wrist.
                kicks.receive()
                continue
            } catch (e: Exception) {
                failures++
                DebugLog.warn("connect failed: ${e.message}")
                _state.update { it.copy(error = e.message) }
            }
            teardown()
            val backoffS = BACKOFF_S[minOf(failures, BACKOFF_S.size - 1)]
            _state.update { it.copy(phase = Phase.WAITING_RETRY, retryInS = backoffS) }
            withTimeoutOrNull(backoffS * 1000L) { kicks.receive() }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectOnce(address: String, dropped: CompletableDeferred<Unit>) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IllegalStateException("no Bluetooth adapter")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")
        _state.update { it.copy(phase = Phase.CONNECTING, error = null, retryInS = null, battery = null) }
        DebugLog.info("connecting to $address")
        val t = BleTransport(context, adapter.getRemoteDevice(address))
        t.onDisconnected = {
            session = null
            _state.update { it.copy(phase = Phase.WAITING_RETRY) }
            dropped.complete(Unit)
        }
        transport = t
        t.connect()
        val s = Session(t, log = DebugLog::add)
        s.onMessage(::onWatchMessage)
        val stored = prefs.authkey(address)
        if (stored != null && !forcePair) {
            DebugLog.info("logging in with stored key")
            s.login(stored, phoneName())
        } else {
            DebugLog.info("pairing via AT GETSECRET")
            _state.update { it.copy(phase = Phase.PAIRING) }
            s.pair(
                phoneName = phoneName(),
                onKey = { prefs.setAuthkey(address, it) },
                onPrompt = { _state.update { st -> st.copy(phase = Phase.AWAITING_ACCEPT) } },
            )
            forcePair = false
        }
        session = s
        val fw = runCatching { s.firmwareVersion() }.getOrNull()
        // The watch pushes BATTERY right after login (seen before FIRMWARE_VERSION_RET) and doesn't answer
        // the poll then, which would cost the full request timeout; poll only if nothing arrived.
        val bat = if (_state.value.battery != null) null else runCatching { s.battery() }.getOrNull()
        // Keep the pushed reading (handled in onWatchMessage) rather than clearing it.
        _state.update { it.copy(phase = Phase.CONNECTED, firmware = fw, battery = bat?.level ?: it.battery, charging = bat?.charging ?: it.charging, error = null) }
        DebugLog.info("connected: firmware $fw, battery ${_state.value.battery}%")
        onConnected()
        scope.launch { housekeeping(s, dropped) }
    }

    private fun phoneName() = (Build.MODEL ?: "Android").take(24)

    private fun teardown() {
        session = null
        transport?.close()
        transport = null
    }

    private fun onConnected() {
        when {
            apps.active -> apps.redraw()
            prefs.activeApp != null -> apps.restore()
            else -> media.push(force = true)
        }
        scope.launch { withSession("units") { it.setUnits(prefs.imperialUnits) } }
        weather.refreshSoon()
        contacts.syncSoon()
    }

    /** Re-send the unit setting, e.g. after the toggle changes. */
    fun pushUnits() {
        scope.launch { withSession("units") { it.setUnits(prefs.imperialUnits) } }
    }

    /** Clock and battery every 30 minutes while this session lives (the watch pushes battery changes itself). */
    private suspend fun housekeeping(s: Session, dropped: CompletableDeferred<Unit>) {
        while (!dropped.isCompleted && session === s) {
            withTimeoutOrNull(30 * 60_000L) { dropped.await() }
            if (session !== s) return
            withSession("housekeeping") {
                it.syncTime()
                val b = it.battery()
                _state.update { st -> st.copy(battery = b.level, charging = b.charging) }
            }
        }
    }

    // -- inbound ----------------------------------------------------------------

    private fun onWatchMessage(m: Message) {
        if (m.op == Ops.BATTERY && m.payload.isNotEmpty()) {
            val bat = Payloads.parseBattery(m.payload)
            _state.update { it.copy(battery = bat.level, charging = bat.charging) }
            return
        }
        if (m.op == Ops.MUSIC_CARD_OPEN) {
            // The first press on a freshly opened music card can be swallowed until the watch has
            // current music info, so answer right away.
            if (!apps.active) media.push(force = true)
            return
        }
        val b = WatchButton.from(m, prefs.buttonCodes)
        if (b == null) {
            if (m.op !in QUIET) DebugLog.info("unhandled ${m}")
            return
        }
        val code = if (m.op == Ops.MUSIC_BUTTON && m.payload.isNotEmpty()) " (code ${m.payload[0].toInt() and 0xFF})" else ""
        _state.update { it.copy(lastButton = b.id + code) }
        DebugLog.info("button ${b.id}$code")
        when {
            b == WatchButton.Named.FIND_PHONE -> ringer.toggle()
            apps.active -> apps.press(b.id)
            else -> media.handle(b)
        }
    }

    // -- outbound ---------------------------------------------------------------

    /** Run [block] against the live session. Returns null (and logs) if not connected or it fails. */
    suspend fun <T> withSession(what: String, block: suspend (Session) -> T): T? {
        val s = session ?: return null
        return try {
            block(s)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.warn("$what failed: ${e.message}")
            null
        }
    }

    fun launchWithSession(what: String, block: suspend (Session) -> Unit) {
        scope.launch { withSession(what, block) }
    }

    fun enqueueNotification(title: String, body: String, icon: Int = Payloads.Icons.GENERIC) {
        if (session == null) return
        notifyQueue.trySend(Outgoing(title, body, icon))
    }

    fun findWatch() = launchWithSession("find watch") { it.findWatch() }

    companion object {
        /**
         * Inbound messages that need no handling. The session already logs each one as it arrives,
         * so an extra "unhandled" line would only be noise. FIND_WATCH from the watch means the
         * user dismissed find-my-watch on the wrist; the rest arrive unprompted after login.
         */
        private val QUIET = setOf(
            Ops.APP_NOTIFICATION_ACK, Ops.MUSIC_INFO_ACK, Ops.FIND_WATCH_ACK, Ops.CONTACTS_ACK,
            Ops.FIND_WATCH, Ops.UNKNOWN_LANGUAGE, Ops.UNKNOWN_A06A, Ops.DIAL_COMMAND_REPLY,
            Ops.UNIT_LENGTH_ACK, Ops.UNIT_TEMPERATURE_ACK,
        )

        private val BACKOFF_S = intArrayOf(3, 5, 10, 20, 30, 60, 120)
    }
}
