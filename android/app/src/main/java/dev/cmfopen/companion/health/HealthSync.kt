package dev.cmfopen.companion.health

import android.content.Context
import androidx.health.connect.client.records.metadata.Device
import dev.cmfopen.companion.DebugLog
import dev.cmfopen.companion.Prefs
import dev.cmfopen.companion.WatchManager
import dev.cmfopen.protocol.Bytes
import dev.cmfopen.protocol.Health
import dev.cmfopen.protocol.HealthOps
import dev.cmfopen.protocol.Message
import dev.cmfopen.protocol.Op
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Pulls recorded health data off the watch and hands it to Health Connect.
 *
 * Power: no polling of its own. It rides on the connection's existing 15-minute housekeeping tick
 * and on reconnects, and only talks to the watch once the chosen interval (default an hour) has
 * passed. One fetch is a few seconds of BLE traffic.
 *
 * Safety: every data message is appended to a local file BEFORE it is parsed or written, and the
 * file is only cleared after Health Connect accepted the records. If Health Connect is missing,
 * a permission is off or a write fails, the data waits there for the next try.
 */
class HealthSync(private val context: Context, private val prefs: Prefs, private val watch: WatchManager) {
    private val sp = context.getSharedPreferences("health", Context.MODE_PRIVATE)
    val writer = HealthConnectWriter(context)
    private val mutex = Mutex()
    private val pendingFile = File(context.filesDir, "health-pending.txt")
    private var job: Job? = null

    data class Status(
        val running: Boolean = false,
        val lastFetchMs: Long = 0,
        val lastResult: String? = null,
        val pending: Int = 0,
        /** Latest running totals the watch reported for today (raw units). */
        val today: Health.ActivitySample? = null,
    )

    private val _status = MutableStateFlow(Status(lastFetchMs = sp.getLong("last_fetch_ms", 0), lastResult = sp.getString("last_result", null)))
    val status: StateFlow<Status> = _status.asStateFlow()

    init {
        _status.update { it.copy(pending = readPending().size, today = lastToday()) }
    }

    // -- settings ------------------------------------------------------------------

    /** Off until the wearer turns it on in the Health tab (which also asks for permissions). */
    var autoSync: Boolean
        get() = sp.getBoolean("auto_sync", false)
        set(v) { sp.edit().putBoolean("auto_sync", v).apply(); bump() }

    var intervalMin: Int
        get() = sp.getInt("interval_min", 60)
        set(v) { sp.edit().putInt("interval_min", v).apply(); bump() }

    /** The watch's distance and calorie units are not confirmed yet, so these are opt-in. */
    var distanceAndCalories: Boolean
        get() = sp.getBoolean("distance_calories", false)
        set(v) { sp.edit().putBoolean("distance_calories", v).apply(); bump() }

    /** Bumped on every settings change so the Health tab recomposes. */
    val settingsVersion = MutableStateFlow(0)
    private fun bump() { settingsVersion.value++ }

    // -- triggers --------------------------------------------------------------------

    /** Called after each (re)connect. Waits a little so the connect-time traffic settles first. */
    fun onConnected() {
        if (!due()) return
        launch(delayMs = 15_000)
    }

    /** Called from the connection's housekeeping tick. */
    fun tick() {
        if (due()) launch()
    }

    fun syncNow() = launch(force = true)

    /** Data the watch pushed outside a fetch: keep it, it goes out with the next write. */
    fun onUnsolicited(m: Message) {
        appendPending(m)
        _status.update { it.copy(pending = it.pending + 1) }
    }

    private fun due(): Boolean = autoSync && System.currentTimeMillis() - _status.value.lastFetchMs >= intervalMin * 60_000L

    private fun launch(force: Boolean = false, delayMs: Long = 0) {
        if (job?.isActive == true) return
        job = watch.scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (force || due()) sync()
        }
    }

    // -- the work --------------------------------------------------------------------

    suspend fun sync() = mutex.withLock {
        _status.update { it.copy(running = true) }
        try {
            val fetched = watch.withSession("health fetch") { s -> s.fetchHealth(onData = ::appendPending) }
            val fetchNote = when {
                fetched == null -> "Couldn't reach the watch."
                !fetched.complete -> "The watch stopped sending partway (${fetched.batch.summary()})."
                else -> null
            }
            if (fetched != null) {
                DebugLog.info("health fetch: ${fetched.batch.summary()}${if (fetched.complete) "" else " (incomplete)"}")
                val now = System.currentTimeMillis()
                sp.edit().putLong("last_fetch_ms", now).apply()
                _status.update { it.copy(lastFetchMs = now) }
            }
            val writeNote = flush()
            finish(listOfNotNull(fetchNote, writeNote).joinToString(" "))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.warn("health sync failed: ${e.message}")
            finish("Sync failed: ${e.message}")
        }
    }

    private fun finish(result: String) {
        sp.edit().putString("last_result", result).apply()
        _status.update { it.copy(running = false, lastResult = result, pending = readPending().size, today = lastToday()) }
    }

    /** Parse everything waiting and write it to Health Connect. Returns a line for the Health tab. */
    private suspend fun flush(): String {
        val (lineCount, messages) = readPendingCounted()
        if (messages.isEmpty()) {
            clearPending(lineCount)
            return "Up to date. Nothing new on the watch."
        }
        val batch = Health.Batch()
        messages.forEach { batch.add(it) }
        batch.activity.maxByOrNull { it.tsS }?.let(::saveToday)
        if (!writer.available) return "Health Connect isn't available on this phone. ${batch.summary()} kept on the phone."
        val needed = HealthConnectWriter.permissions(distanceAndCalories) - HealthConnectWriter.PERM_ROUTE
        val missing = needed - writer.granted()
        if (missing.isNotEmpty()) return "Health Connect permission needed. ${batch.summary()} kept until it's granted."
        val result = writer.write(batch, loadTotals(), device(), distanceAndCalories)
        saveTotals(result.totals)
        keepRejected(batch.rejected)
        clearPending(lineCount)
        DebugLog.info("health connect: wrote $result")
        return "Sent to Health Connect: $result."
    }

    private fun device() = Device(
        type = Device.TYPE_WATCH,
        manufacturer = "Nothing",
        model = prefs.deviceName ?: "CMF Watch",
    )

    // -- pending store: one "cmd1 cmd2 hex" line per message ----------------------

    @Synchronized private fun appendPending(m: Message) {
        if (m.op !in HealthOps.DATA) return
        pendingFile.appendText("%04x %04x %s\n".format(m.op.cmd1, m.op.cmd2, Bytes.hex(m.payload)))
        // Keep the file bounded if Health Connect stays unreachable for a long time.
        if (pendingFile.length() > MAX_PENDING_BYTES) {
            val lines = pendingFile.readLines()
            pendingFile.writeText(lines.takeLast(lines.size / 2).joinToString("\n", postfix = "\n"))
            DebugLog.warn("health: pending data too large, dropped the oldest half")
        }
    }

    @Synchronized private fun readPending(): List<Message> = readPendingCounted().second

    /** The pending messages, and how many lines they came from (to clear exactly those later). */
    @Synchronized private fun readPendingCounted(): Pair<Int, List<Message>> {
        if (!pendingFile.exists()) return 0 to emptyList()
        val lines = pendingFile.readLines().filter { it.isNotBlank() }
        return lines.size to lines.mapNotNull { line ->
            val parts = line.trim().split(" ")
            if (parts.size < 2) return@mapNotNull null
            runCatching { Message(Op(parts[0].toInt(16), parts[1].toInt(16)), Bytes.fromHex(parts.getOrElse(2) { "" })) }.getOrNull()
        }
    }

    /** Drop the first [count] messages (the ones just written); anything appended since stays. */
    @Synchronized private fun clearPending(count: Int) {
        val lines = if (pendingFile.exists()) pendingFile.readLines().filter { it.isNotBlank() } else emptyList()
        val rest = lines.drop(count)
        if (rest.isEmpty()) pendingFile.delete() else pendingFile.writeText(rest.joinToString("\n", postfix = "\n"))
    }

    /**
     * Messages whose layout didn't match go to health-rejected.txt (bounded) instead of being lost,
     * so they can be pulled with `adb shell run-as dev.cmfopen.companion cat files/health-rejected.txt`
     * and decoded later.
     */
    private fun keepRejected(rejected: List<Message>) {
        if (rejected.isEmpty()) return
        val f = File(context.filesDir, "health-rejected.txt")
        if (f.length() > 256 * 1024) f.delete()
        f.appendText(rejected.joinToString("") { "%04x %04x %s\n".format(it.op.cmd1, it.op.cmd2, Bytes.hex(it.payload)) })
        DebugLog.warn("health: ${rejected.size} message(s) in an unknown layout saved to health-rejected.txt")
    }

    // -- small persisted state ------------------------------------------------------

    private fun loadTotals() = HealthConnectWriter.DayTotals(
        sp.getLong("day", -1), sp.getLong("day_last_ts", 0), sp.getInt("day_steps", 0), sp.getInt("day_distance", 0), sp.getInt("day_calories", 0),
    )

    private fun saveTotals(t: HealthConnectWriter.DayTotals) = sp.edit()
        .putLong("day", t.epochDay).putLong("day_last_ts", t.lastTsS)
        .putInt("day_steps", t.steps).putInt("day_distance", t.distance).putInt("day_calories", t.calories).apply()

    private fun saveToday(a: Health.ActivitySample) = sp.edit()
        .putLong("today_ts", a.tsS).putInt("today_steps", a.stepsTotal).putInt("today_distance", a.distanceRaw).putInt("today_calories", a.caloriesRaw).apply()

    private fun lastToday(): Health.ActivitySample? {
        val ts = sp.getLong("today_ts", 0)
        if (ts == 0L) return null
        return Health.ActivitySample(ts, sp.getInt("today_steps", 0), sp.getInt("today_distance", 0), sp.getInt("today_calories", 0))
    }

    companion object {
        private const val MAX_PENDING_BYTES = 4L * 1024 * 1024
        val INTERVALS_MIN = listOf(30, 60, 180, 360)
    }
}
