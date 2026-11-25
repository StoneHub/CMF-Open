package dev.cmfopen.protocol

/**
 * Recorded health data: steps, heart rate, SpO2, stress, sleep and workouts.
 *
 * Wire format as documented by Gadgetbridge's CMF Watch Pro 2 support (the Watch 3 Pro uses the
 * same commands per Gadgetbridge issue #5596; heart rate confirmed in #5662). Reimplemented here
 * from the format description, not copied. All multi-byte fields are LITTLE-endian, unlike the
 * frame header. Timestamps are UTC epoch seconds.
 *
 * Fetch flow:
 *  1. phone -> FETCH_START (A5)
 *  2. watch -> FETCH_STATUS 01 ("ready")
 *  3. phone -> FETCH_CONTINUE (A5)
 *  4. watch -> FETCH_SINCE (u32 ts of the oldest record it will send, 4 flag bytes)
 *  5. watch -> any number of data messages (ACTIVITY, HEART_RATE*, SLEEP, SPO2, STRESS, WORKOUT*)
 *  6. watch -> FETCH_STATUS 02 ("done")
 */
object HealthOps {
    val FETCH_START = Op(0xFFFF, 0x8005)
    val FETCH_STATUS = Op(0xFFFF, 0x0005)
    val FETCH_CONTINUE = Op(0xFFFF, 0x9057)
    val FETCH_SINCE = Op(0xFFFF, 0xA057)

    val ACTIVITY = Op(0x0056, 0x0001)
    val HEART_RATE = Op(0x0053, 0x0001)
    val HEART_RATE_WORKOUT = Op(0x00E0, 0x0001)
    val HEART_RATE_RESTING = Op(0x00DA, 0x0001)
    val SLEEP = Op(0x0058, 0x0001)
    val SPO2 = Op(0x0055, 0x0001)
    val STRESS = Op(0x009D, 0x0001)
    val WORKOUT_SUMMARY = Op(0x0057, 0x0001)
    val WORKOUT_GPS = Op(0xFFFF, 0xA05A)

    /** Data messages: kept (and persisted) even when they arrive outside a fetch. */
    val DATA: Set<Op> = setOf(ACTIVITY, HEART_RATE, HEART_RATE_WORKOUT, HEART_RATE_RESTING, SLEEP, SPO2, STRESS, WORKOUT_SUMMARY, WORKOUT_GPS)
    val ALL: Set<Op> = DATA + setOf(FETCH_STATUS, FETCH_SINCE)

    private val names = mapOf(
        FETCH_START to "HEALTH_FETCH_START", FETCH_STATUS to "HEALTH_FETCH_STATUS",
        FETCH_CONTINUE to "HEALTH_FETCH_CONTINUE", FETCH_SINCE to "HEALTH_FETCH_SINCE",
        ACTIVITY to "HEALTH_ACTIVITY", HEART_RATE to "HEALTH_HEART_RATE", HEART_RATE_WORKOUT to "HEALTH_HEART_RATE_WORKOUT",
        HEART_RATE_RESTING to "HEALTH_HEART_RATE_RESTING", SLEEP to "HEALTH_SLEEP", SPO2 to "HEALTH_SPO2",
        STRESS to "HEALTH_STRESS", WORKOUT_SUMMARY to "HEALTH_WORKOUT_SUMMARY", WORKOUT_GPS to "HEALTH_WORKOUT_GPS",
    )

    fun nameOf(op: Op): String? = names[op]
}

object Health {
    /** One activity record. Steps, distance and calories are running totals for the (local) day. */
    data class ActivitySample(val tsS: Long, val stepsTotal: Int, val distanceRaw: Int, val caloriesRaw: Int)

    /** A timestamped reading: bpm, SpO2 percent or stress score. */
    data class Reading(val tsS: Long, val value: Int)

    data class SleepStage(val startS: Long, val durationS: Int, val stage: Int)

    data class SleepSession(val startS: Long, val endS: Long, val stages: List<SleepStage>)

    data class Workout(val startS: Long, val endS: Long, val activeS: Int, val type: Int, val hasGps: Boolean)

    data class GpsPoint(val tsS: Long, val latitude: Double, val longitude: Double)

    /** Sleep stage codes. Only 1-3 are documented; others are kept raw and treated as plain sleep. */
    const val STAGE_DEEP = 1
    const val STAGE_LIGHT = 2
    const val STAGE_REM = 3

    /** Everything one fetch returned, parsed. Unparseable messages are kept in [rejected]. */
    class Batch {
        val activity = ArrayList<ActivitySample>()
        val heartRate = ArrayList<Reading>()
        val restingHeartRate = ArrayList<Reading>()
        val spo2 = ArrayList<Reading>()
        val stress = ArrayList<Reading>()
        val sleep = ArrayList<SleepSession>()
        val workouts = ArrayList<Workout>()
        val gps = ArrayList<GpsPoint>()
        val rejected = ArrayList<Message>()

        val isEmpty get() = activity.isEmpty() && heartRate.isEmpty() && restingHeartRate.isEmpty() && spo2.isEmpty() &&
            stress.isEmpty() && sleep.isEmpty() && workouts.isEmpty() && gps.isEmpty()

        /** Parse [m] into this batch. Returns false (and keeps it in [rejected]) if it doesn't fit the known layout. */
        fun add(m: Message): Boolean {
            val ok = runCatching {
                when (m.op) {
                    HealthOps.ACTIVITY -> parseActivity(m.payload)?.let { activity += it }
                    HealthOps.HEART_RATE, HealthOps.HEART_RATE_WORKOUT -> parseReadings(m.payload)?.let { heartRate += it }
                    HealthOps.HEART_RATE_RESTING -> parseReadings(m.payload)?.let { restingHeartRate += it }
                    HealthOps.SPO2 -> parseReadings(m.payload)?.let { spo2 += it }
                    HealthOps.STRESS -> parseReadings(m.payload)?.let { stress += it }
                    HealthOps.SLEEP -> parseSleep(m.payload)?.let { sleep += it }
                    HealthOps.WORKOUT_SUMMARY -> parseWorkouts(m.payload)?.let { workouts += it }
                    HealthOps.WORKOUT_GPS -> parseGps(m.payload)?.let { gps += it }
                    else -> null
                } != null
            }.getOrDefault(false)
            if (!ok) rejected += m
            return ok
        }

        fun summary(): String = listOf(
            "activity" to activity.size, "hr" to heartRate.size, "restingHr" to restingHeartRate.size, "spo2" to spo2.size,
            "stress" to stress.size, "sleep" to sleep.size, "workouts" to workouts.size, "gps" to gps.size, "rejected" to rejected.size,
        ).filter { it.second > 0 }.joinToString(", ") { "${it.second} ${it.first}" }.ifEmpty { "nothing new" }
    }

    // -- parsers ------------------------------------------------------------------

    /** 32-byte records: ts, steps, distance, calories (u32 each), then 16 bytes not yet understood. */
    fun parseActivity(p: ByteArray): List<ActivitySample>? {
        if (p.size % 32 != 0) return null
        return (p.indices step 32).map { ActivitySample(u32le(p, it), s32le(p, it + 4), s32le(p, it + 8), s32le(p, it + 12)) }
    }

    /** 8-byte records: ts u32, value u32. Used by heart rate, SpO2 and stress. */
    fun parseReadings(p: ByteArray): List<Reading>? {
        if (p.size % 8 != 0) return null
        return (p.indices step 8).map { Reading(u32le(p, it), s32le(p, it + 4)) }
    }

    /** Session start u32, wake-up u32, 10 metadata bytes, then 8-byte stages: start u32, duration-seconds u16, stage u16. */
    fun parseSleep(p: ByteArray): SleepSession? {
        if (p.size < 18 || (p.size - 18) % 8 != 0) return null
        val stages = (18 until p.size step 8).map { SleepStage(u32le(p, it), u16le(p, it + 4), u16le(p, it + 6)) }
        return SleepSession(u32le(p, 0), u32le(p, 4), stages)
    }

    /**
     * 32- or 54-byte summaries: start u32, active seconds u16, type u8, 19 unknown bytes,
     * end u32 at 26, has-GPS u8 at 30. The 54-byte form shares the first 32 bytes.
     */
    fun parseWorkouts(p: ByteArray): List<Workout>? {
        val size = when {
            p.isNotEmpty() && p.size % 32 == 0 -> 32
            p.isNotEmpty() && p.size % 54 == 0 -> 54
            else -> return null
        }
        return (p.indices step size).map { Workout(u32le(p, it), u32le(p, it + 26), u16le(p, it + 4), p.u8(it + 6), p.u8(it + 30) == 1) }
    }

    /** 12-byte points: ts u32, longitude s32, latitude s32 (degrees x 1e7). */
    fun parseGps(p: ByteArray): List<GpsPoint>? {
        if (p.size % 12 != 0) return null
        return (p.indices step 12).map { GpsPoint(u32le(p, it), s32le(p, it + 8) / 1e7, s32le(p, it + 4) / 1e7) }
    }

    // Builders, for the simulator and tests.

    fun activityRecord(tsS: Long, steps: Int, distance: Int, calories: Int) =
        Bytes.concat(Bytes.u32le(tsS), Bytes.u32le(steps.toLong()), Bytes.u32le(distance.toLong()), Bytes.u32le(calories.toLong()), ByteArray(16))

    fun readingRecord(tsS: Long, value: Int) = Bytes.concat(Bytes.u32le(tsS), Bytes.u32le(value.toLong()))

    fun sleepRecord(s: SleepSession) = Bytes.concat(
        Bytes.u32le(s.startS), Bytes.u32le(s.endS), ByteArray(10),
        *s.stages.map { Bytes.concat(Bytes.u32le(it.startS), u16le(it.durationS), u16le(it.stage)) }.toTypedArray(),
    )

    fun workoutRecord(w: Workout): ByteArray {
        val b = ByteArray(32)
        Bytes.u32le(w.startS).copyInto(b, 0)
        u16le(w.activeS).copyInto(b, 4)
        b[6] = w.type.toByte()
        Bytes.u32le(w.endS).copyInto(b, 26)
        b[30] = if (w.hasGps) 1 else 0
        return b
    }

    private fun u16le(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun u16le(b: ByteArray, at: Int) = b.u8(at) or (b.u8(at + 1) shl 8)
    private fun s32le(b: ByteArray, at: Int) = b.u8(at) or (b.u8(at + 1) shl 8) or (b.u8(at + 2) shl 16) or (b.u8(at + 3) shl 24)
    private fun u32le(b: ByteArray, at: Int) = s32le(b, at).toLong() and 0xFFFFFFFFL
}
