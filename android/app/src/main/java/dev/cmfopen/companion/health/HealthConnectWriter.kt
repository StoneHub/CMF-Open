package dev.cmfopen.companion.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import dev.cmfopen.protocol.Health
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Writes watch data to Health Connect, which Google Health (the app that replaced Fitbit and
 * Google Fit) reads from. Every record carries a stable clientRecordId, so writing the same data
 * twice updates it in place instead of double counting.
 */
class HealthConnectWriter(private val context: Context) {

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    val available get() = sdkStatus() == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun granted(): Set<String> = if (available) client.permissionController.getGrantedPermissions() else emptySet()

    /** Running totals carried between fetches, so cumulative watch counters become intervals. */
    data class DayTotals(val epochDay: Long = -1, val lastTsS: Long = 0, val steps: Int = 0, val distance: Int = 0, val calories: Int = 0)

    data class Result(val written: Map<String, Int>, val totals: DayTotals) {
        override fun toString() = written.filter { it.value > 0 }.entries.joinToString(", ") { "${it.value} ${it.key}" }.ifEmpty { "nothing new" }
    }

    /**
     * Turn [batch] into records and insert them. Throws if Health Connect refuses them; the caller
     * keeps the raw data and retries later.
     */
    suspend fun write(batch: Health.Batch, totals: DayTotals, device: Device, distanceAndCalories: Boolean): Result {
        val granted = granted()
        fun allowed(p: String) = p in granted
        val out = LinkedHashMap<String, MutableList<Record>>()
        fun add(kind: String, r: Record) { out.getOrPut(kind) { ArrayList() } += r }

        // Steps (and optionally distance and calories) are daily running totals on the watch.
        var t = totals
        for (s in batch.activity.sortedBy { it.tsS }) {
            val day = Instant.ofEpochSecond(s.tsS).atZone(zone).toLocalDate().toEpochDay()
            if (day != t.epochDay) {
                val midnight = LocalDate.ofEpochDay(day).atStartOfDay(zone).toEpochSecond()
                t = DayTotals(day, midnight)
            }
            if (s.tsS <= t.lastTsS) continue // already written in an earlier fetch
            val start = t.lastTsS
            val end = s.tsS
            // A total that went down means the watch reset its counter; count from zero.
            val steps = if (s.stepsTotal >= t.steps) s.stepsTotal - t.steps else s.stepsTotal
            val dist = if (s.distanceRaw >= t.distance) s.distanceRaw - t.distance else s.distanceRaw
            val kcal = if (s.caloriesRaw >= t.calories) s.caloriesRaw - t.calories else s.caloriesRaw
            if (steps > 0 && allowed(PERM_STEPS)) {
                add("steps", StepsRecord(
                    startTime = inst(start), startZoneOffset = off(start), endTime = inst(end), endZoneOffset = off(end),
                    count = steps.toLong(), metadata = meta(device, "steps-$end"),
                ))
            }
            if (distanceAndCalories && dist > 0 && allowed(PERM_DISTANCE)) {
                add("distance", DistanceRecord(
                    startTime = inst(start), startZoneOffset = off(start), endTime = inst(end), endZoneOffset = off(end),
                    distance = Length.meters(dist.toDouble()), metadata = meta(device, "dist-$end"),
                ))
            }
            if (distanceAndCalories && kcal > 0 && allowed(PERM_CALORIES)) {
                add("calories", ActiveCaloriesBurnedRecord(
                    startTime = inst(start), startZoneOffset = off(start), endTime = inst(end), endZoneOffset = off(end),
                    energy = Energy.kilocalories(kcal.toDouble()), metadata = meta(device, "kcal-$end"),
                ))
            }
            t = DayTotals(day, end, s.stepsTotal, s.distanceRaw, s.caloriesRaw)
        }

        // Heart rate: one record per run of samples spanning at most an hour.
        if (allowed(PERM_HEART_RATE)) {
            val hr = batch.heartRate.filter { it.value in 25..250 }.distinctBy { it.tsS }.sortedBy { it.tsS }
            var group = ArrayList<Health.Reading>()
            fun flush() {
                if (group.isEmpty()) return
                val first = group.first().tsS
                val last = group.last().tsS
                add("heart rate", HeartRateRecord(
                    startTime = inst(first), startZoneOffset = off(first), endTime = inst(last + 1), endZoneOffset = off(last + 1),
                    samples = group.map { HeartRateRecord.Sample(inst(it.tsS), it.value.toLong()) },
                    metadata = meta(device, "hr-$first"),
                ))
                group = ArrayList()
            }
            for (r in hr) {
                if (group.isNotEmpty() && r.tsS - group.first().tsS >= 3600) flush()
                group += r
            }
            flush()
        }

        if (allowed(PERM_RESTING_HR)) {
            for (r in batch.restingHeartRate.filter { it.value in 25..150 }.distinctBy { it.tsS }) {
                add("resting heart rate", RestingHeartRateRecord(
                    time = inst(r.tsS), zoneOffset = off(r.tsS), beatsPerMinute = r.value.toLong(), metadata = meta(device, "rhr-${r.tsS}"),
                ))
            }
        }

        if (allowed(PERM_SPO2)) {
            for (r in batch.spo2.filter { it.value in 50..100 }.distinctBy { it.tsS }) {
                add("SpO2", OxygenSaturationRecord(
                    time = inst(r.tsS), zoneOffset = off(r.tsS), percentage = Percentage(r.value.toDouble()), metadata = meta(device, "spo2-${r.tsS}"),
                ))
            }
        }

        if (allowed(PERM_SLEEP)) {
            for (s in batch.sleep.filter { it.endS > it.startS }.distinctBy { it.startS }) {
                val stages = s.stages.mapNotNull { st ->
                    val a = maxOf(st.startS, s.startS)
                    val b = minOf(st.startS + st.durationS, s.endS)
                    if (b <= a) null else SleepSessionRecord.Stage(inst(a), inst(b), sleepStage(st.stage))
                }.sortedBy { it.startTime }
                    // Health Connect rejects overlapping stages; drop any that start inside the previous one.
                    .fold(ArrayList<SleepSessionRecord.Stage>()) { acc, st -> if (acc.isEmpty() || !st.startTime.isBefore(acc.last().endTime)) acc += st; acc }
                add("sleep", SleepSessionRecord(
                    startTime = inst(s.startS), startZoneOffset = off(s.startS), endTime = inst(s.endS), endZoneOffset = off(s.endS),
                    stages = stages, metadata = meta(device, "sleep-${s.startS}"),
                ))
            }
        }

        if (allowed(PERM_EXERCISE)) {
            val routeOk = allowed(PERM_ROUTE)
            for (w in batch.workouts.filter { it.endS > it.startS }.distinctBy { it.startS }) {
                val points = if (routeOk && w.hasGps) {
                    batch.gps.filter { it.tsS in w.startS..w.endS && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
                        .distinctBy { it.tsS }.sortedBy { it.tsS }
                } else emptyList()
                val (type, title) = exerciseType(w.type)
                add("workouts", ExerciseSessionRecord(
                    startTime = inst(w.startS), startZoneOffset = off(w.startS), endTime = inst(w.endS), endZoneOffset = off(w.endS),
                    exerciseType = type, title = title,
                    exerciseRoute = if (points.isEmpty()) null else ExerciseRoute(points.map { ExerciseRoute.Location(time = inst(it.tsS), latitude = it.latitude, longitude = it.longitude) }),
                    metadata = meta(device, "ex-${w.startS}"),
                ))
            }
        }

        for ((_, records) in out) records.chunked(500).forEach { client.insertRecords(it) }
        return Result(out.mapValues { it.value.size }, t)
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun inst(s: Long) = Instant.ofEpochSecond(s)
    private fun off(s: Long): ZoneOffset = zone.rules.getOffset(inst(s))
    private fun meta(device: Device, id: String) = Metadata.autoRecorded(device = device, clientRecordId = "cmf-$id")

    private fun sleepStage(code: Int) = when (code) {
        Health.STAGE_DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
        Health.STAGE_LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
        Health.STAGE_REM -> SleepSessionRecord.STAGE_TYPE_REM
        else -> SleepSessionRecord.STAGE_TYPE_SLEEPING
    }

    companion object {
        val PERM_STEPS = HealthPermission.getWritePermission(StepsRecord::class)
        val PERM_HEART_RATE = HealthPermission.getWritePermission(HeartRateRecord::class)
        val PERM_RESTING_HR = HealthPermission.getWritePermission(RestingHeartRateRecord::class)
        val PERM_SPO2 = HealthPermission.getWritePermission(OxygenSaturationRecord::class)
        val PERM_SLEEP = HealthPermission.getWritePermission(SleepSessionRecord::class)
        val PERM_EXERCISE = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
        val PERM_ROUTE = HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE
        val PERM_DISTANCE = HealthPermission.getWritePermission(DistanceRecord::class)
        val PERM_CALORIES = HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class)

        /** What the Health tab asks for. Distance and calories only when that switch is on. */
        fun permissions(distanceAndCalories: Boolean): Set<String> =
            setOf(PERM_STEPS, PERM_HEART_RATE, PERM_RESTING_HR, PERM_SPO2, PERM_SLEEP, PERM_EXERCISE, PERM_ROUTE) +
                if (distanceAndCalories) setOf(PERM_DISTANCE, PERM_CALORIES) else emptySet()

        /** Watch sport code -> Health Connect exercise type, plus a title for the ones folded into a broader type. */
        fun exerciseType(code: Int): Pair<Int, String?> = when (code) {
            0x01, 0x19 -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING to null
            0x02 -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to null
            0x1B -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "Cross-country run"
            0x03, 0x26 -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to null
            0x05 -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING to null
            0x72, 0x0D -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to null
            0x04, 0x1A -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING to null
            0x18 -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to null
            0x13, 0x9F -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to null
            0x0F -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA to null
            0x21, 0x35 -> ExerciseSessionRecord.EXERCISE_TYPE_BOXING to null
            0x0E -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to null
            0x73 -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to null
            0x15 -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to null
            0x5C -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to null
            0x2C -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES to null
            0x38, 0x29, 0x25 -> ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to null
            0x78, 0x33, 0x34, 0x36, 0x76, 0x77, 0x9B -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING to null
            0x11, 0x66, 0x67, 0x68 -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to null
            0x09 -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to null
            0x0A -> ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to null
            0x0C -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to null
            0x49 -> ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to null
            0x08 -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to null
            0x07 -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to null
            0x45 -> ExerciseSessionRecord.EXERCISE_TYPE_GOLF to null
            0x0B -> ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to null
            0x3A -> ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to null
            0x55 -> ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL to null
            0x39 -> ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL to null
            0x3C -> ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to null
            0x44 -> ExerciseSessionRecord.EXERCISE_TYPE_RUGBY to null
            0x23 -> ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to null
            0x54 -> ExerciseSessionRecord.EXERCISE_TYPE_FENCING to null
            0x10 -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Free training"
            0x2E -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Functional training"
            0x4B -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Core training"
            0x06 -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Jump rope"
            else -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "Workout (watch code 0x%02X)".format(code)
        }
    }
}
