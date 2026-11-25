package dev.cmfopen.protocol

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthTest {
    @Test fun parsesHeartRateCapturedFromAWatch3Pro() {
        // Gadgetbridge issue #5662: HEART_RATE_MANUAL_AUTO from a CMF Watch 3 Pro.
        val p = Bytes.fromHex("8FDE5569510000008FDE556951000000")
        val r = Health.parseReadings(p)!!
        assertEquals(2, r.size)
        assertEquals(Health.Reading(0x6955DE8FL, 0x51), r[0])
    }

    @Test fun roundTripsEveryRecordType() {
        val b = Health.Batch()
        val sleep = Health.SleepSession(1_000, 9_000, listOf(Health.SleepStage(1_000, 3_600, Health.STAGE_LIGHT), Health.SleepStage(4_600, 1_800, Health.STAGE_DEEP)))
        val workout = Health.Workout(2_000_000_000L, 2_000_001_800L, 1_750, 0x02, hasGps = true)
        assertTrue(b.add(Message(HealthOps.ACTIVITY, Bytes.concat(Health.activityRecord(100, 1200, 900, 55), Health.activityRecord(160, 1300, 980, 60)))))
        assertTrue(b.add(Message(HealthOps.SPO2, Health.readingRecord(200, 97))))
        assertTrue(b.add(Message(HealthOps.SLEEP, Health.sleepRecord(sleep))))
        assertTrue(b.add(Message(HealthOps.WORKOUT_SUMMARY, Health.workoutRecord(workout))))
        assertTrue(b.add(Message(HealthOps.WORKOUT_GPS, Bytes.concat(Bytes.u32le(5), Bytes.u32le((-1220840000L) and 0xFFFFFFFFL), Bytes.u32le(374220000)))))
        assertEquals(Health.ActivitySample(160, 1300, 980, 60), b.activity[1])
        assertEquals(97, b.spo2.single().value)
        assertEquals(sleep, b.sleep.single())
        assertEquals(workout, b.workouts.single())
        assertEquals(37.422, b.gps.single().latitude, 1e-9)
        assertEquals(-122.084, b.gps.single().longitude, 1e-9)
    }

    @Test fun keepsMessagesThatDoNotFit() {
        val b = Health.Batch()
        assertTrue(!b.add(Message(HealthOps.HEART_RATE, ByteArray(7))))
        assertEquals(1, b.rejected.size)
        assertNull(Health.parseSleep(ByteArray(5)))
    }

    @Test fun fetchRunsTheTwoStepHandshake() = runBlocking {
        val sim = SimWatch()
        val s = Session(sim, timeoutMs = 2_000)
        s.pair()
        sim.health += Message(HealthOps.HEART_RATE, Bytes.concat(Health.readingRecord(1_000, 72), Health.readingRecord(1_060, 75)))
        sim.health += Message(HealthOps.ACTIVITY, Health.activityRecord(1_000, 500, 400, 20))
        val seen = ArrayList<Message>()
        val f = s.fetchHealth(idleTimeoutMs = 2_000) { seen += it }
        assertTrue(f.complete)
        assertEquals(1_764_000_000L, f.sinceS)
        assertEquals(listOf(72, 75), f.batch.heartRate.map { it.value })
        assertEquals(500, f.batch.activity.single().stepsTotal)
        assertEquals(2, seen.size)
        // The session is still usable afterwards.
        assertEquals(87, s.battery().level)
        assertTrue(s.fetchHealth(idleTimeoutMs = 2_000).batch.isEmpty)
    }
}
