package com.fusionhealth.diagnostic

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Coverage for [SamsungSnapshot]'s pure logic: label mapping (against the SDK's own constants),
 * stage-duration summarisation, heart-rate/oxygen summaries, speed avg/max, duration formatting,
 * and readable snapshot formatting including "Not available" fallbacks. No SDK client or device.
 */
class SamsungSnapshotTest {

    @Test
    fun `exercise type codes map to readable labels via SDK constants`() {
        assertEquals("Walking", exerciseTypeLabel(ExerciseSessionRecord.EXERCISE_TYPE_WALKING))
        assertEquals("Running", exerciseTypeLabel(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING))
        assertEquals("Strength training", exerciseTypeLabel(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING))
    }

    @Test
    fun `an unknown exercise code falls back to a readable label, never a raw number`() {
        val label = exerciseTypeLabel(-999)
        assertEquals("Other workout", label)
        assertTrue(label.none { it.isDigit() })
    }

    @Test
    fun `sleep stage codes map to readable labels via SDK constants`() {
        assertEquals("Light", sleepStageLabel(SleepSessionRecord.STAGE_TYPE_LIGHT))
        assertEquals("Deep", sleepStageLabel(SleepSessionRecord.STAGE_TYPE_DEEP))
        assertEquals("REM", sleepStageLabel(SleepSessionRecord.STAGE_TYPE_REM))
        assertEquals("Awake", sleepStageLabel(SleepSessionRecord.STAGE_TYPE_AWAKE))
    }

    @Test
    fun `stage durations are summed per known stage type`() {
        val totals = summariseStageDurations(
            listOf(
                SleepSessionRecord.STAGE_TYPE_LIGHT to 600L,
                SleepSessionRecord.STAGE_TYPE_LIGHT to 300L,
                SleepSessionRecord.STAGE_TYPE_DEEP to 1200L,
                SleepSessionRecord.STAGE_TYPE_REM to 900L,
                SleepSessionRecord.STAGE_TYPE_AWAKE to 120L,
            )
        )
        assertEquals(900L, totals.lightSeconds)
        assertEquals(1200L, totals.deepSeconds)
        assertEquals(900L, totals.remSeconds)
        assertEquals(120L, totals.awakeSeconds)
    }

    @Test
    fun `heart-rate summary reports latest overall and today's min-max`() {
        val dayStart = Instant.parse("2026-07-14T00:00:00Z")
        val samples = listOf(
            60L to Instant.parse("2026-07-13T22:00:00Z"), // yesterday, excluded from min/max
            70L to Instant.parse("2026-07-14T08:00:00Z"),
            120L to Instant.parse("2026-07-14T09:00:00Z"),
            90L to Instant.parse("2026-07-14T18:00:00Z"),
        )
        val hr = summariseHeartRate(samples, dayStart)
        assertEquals(90L, hr.latestBpm)
        assertEquals(Instant.parse("2026-07-14T18:00:00Z"), hr.latestBpmTime)
        assertEquals(70L, hr.todayMinBpm)
        assertEquals(120L, hr.todayMaxBpm)
    }

    @Test
    fun `empty heart-rate samples summarise to nulls`() {
        val hr = summariseHeartRate(emptyList(), Instant.parse("2026-07-14T00:00:00Z"))
        assertNull(hr.latestBpm)
        assertNull(hr.todayMinBpm)
    }

    @Test
    fun `oxygen summary restricts min-avg to the window but latest is overall`() {
        val samples = listOf(
            98.0 to Instant.parse("2026-07-14T12:00:00Z"), // outside sleep window
            95.0 to Instant.parse("2026-07-14T01:00:00Z"),
            90.0 to Instant.parse("2026-07-14T02:00:00Z"),
        )
        val summary = summariseOxygen(
            samples,
            windowStart = Instant.parse("2026-07-14T00:00:00Z"),
            windowEnd = Instant.parse("2026-07-14T06:00:00Z"),
        )
        assertEquals(98.0, summary.latestPct!!, 0.0) // latest overall = the 12:00 reading
        assertEquals(90.0, summary.windowMinPct!!, 0.0)
        assertEquals(92.5, summary.windowAvgPct!!, 0.0001)
    }

    @Test
    fun `speed avg-max is null when there are no samples`() {
        assertNull(speedAvgMax(emptyList()))
        val avgMax = speedAvgMax(listOf(1.0, 2.0, 3.0))
        assertEquals(2.0, avgMax!!.first, 0.0)
        assertEquals(3.0, avgMax.second, 0.0)
    }

    @Test
    fun `duration formatting is compact`() {
        assertEquals("2h 05m", formatSnapshotDuration(2 * 3600 + 5 * 60L))
        assertEquals("45m", formatSnapshotDuration(45 * 60L))
        assertEquals("0m", formatSnapshotDuration(0))
    }

    @Test
    fun `formatting shows values with units and Not available for missing data`() {
        val data = SnapshotData(
            today = TodayTotals(steps = 7572, distanceMeters = 936.6, caloriesKcal = 94.0),
            exercise = ExerciseSummary(
                typeLabel = "Walking",
                start = Instant.parse("2026-07-14T16:18:00Z"),
                durationSeconds = 712,
                distanceMeters = 936.6,
                avgSpeedMps = 1.3,
                maxSpeedMps = 2.1,
                caloriesKcal = 94.0,
            ),
            sleep = SleepSummary(
                start = Instant.parse("2026-07-14T00:09:00Z"),
                end = Instant.parse("2026-07-14T03:00:00Z"),
                totalSeconds = 10260,
                awakeSeconds = 600,
                lightSeconds = 6000,
                deepSeconds = 2400,
                remSeconds = 0, // unavailable stage
                spo2MinPct = 92.0,
                spo2AvgPct = 95.0,
            ),
            heartOxygen = HeartOxygenSummary(
                latestBpm = 102,
                latestBpmTime = Instant.parse("2026-07-14T16:18:00Z"),
                todayMinBpm = 54,
                todayMaxBpm = 130,
                latestSpo2Pct = 95.0,
                latestSpo2Time = Instant.parse("2026-07-14T03:00:00Z"),
            ),
            latestSourceTimestamp = Instant.parse("2026-07-14T16:18:00Z"),
            generatedAt = Instant.parse("2026-07-14T17:30:00Z"),
        )

        val text = formatSnapshot(data, zone = ZoneId.of("UTC"))

        assertTrue(text.contains("Steps: 7,572"))
        assertTrue(text.contains("Distance: 0.94 km"))
        assertTrue(text.contains("Calories burned: 94 kcal"))
        assertTrue(text.contains("Walking"))
        assertTrue(text.contains("1.3 m/s avg (max 2.1 m/s)"))
        assertTrue(text.contains("Deep: 40m"))
        assertTrue(text.contains("REM: Not available")) // remSeconds = 0
        assertTrue(text.contains("102 bpm"))
        assertTrue(text.contains("54–130 bpm"))
        assertTrue(text.contains("Samsung Health via Health Connect"))
    }

    @Test
    fun `formatting shows Not available sections when data is missing`() {
        val data = SnapshotData(
            today = null,
            exercise = null,
            sleep = null,
            heartOxygen = null,
            latestSourceTimestamp = null,
            generatedAt = Instant.parse("2026-07-14T17:30:00Z"),
        )
        val text = formatSnapshot(data, zone = ZoneId.of("UTC"))
        assertTrue(text.contains("TODAY"))
        assertTrue(text.contains("Steps: Not available"))
        assertTrue(text.contains("LATEST EXERCISE"))
        assertTrue(text.contains("LAST SLEEP"))
        assertTrue(text.contains("HEART & OXYGEN"))
    }
}
