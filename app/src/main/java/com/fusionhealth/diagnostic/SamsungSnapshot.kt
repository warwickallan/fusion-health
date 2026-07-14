package com.fusionhealth.diagnostic

import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * BUILD-005/WP2 — Samsung Health Snapshot. Pure, Android-independent logic for the first
 * user-facing summary: daily aggregation, exercise/sleep label mapping, heart-rate and oxygen
 * summaries, and readable formatting. Kept separate from [SamsungSnapshotActivity] so all of this
 * is directly unit-testable without a real `HealthConnectClient` or device.
 *
 * Read-only, Samsung Health only. No raw record counts are ever presented as metric totals; codes
 * are mapped to human-readable labels (never raw numbers in the normal view); unavailable data is
 * shown as "Not available" rather than guessed.
 */

/** Samsung Health's Health Connect writer package (verified on Warwick's device). */
internal const val SAMSUNG_SNAPSHOT_PACKAGE = "com.sec.android.app.shealth"

// ---- Label mapping (references the SDK's own constants, so the codes can never be wrong) --------

/** Maps a Health Connect exercise type code to a readable label; unknown -> "Other workout". */
internal fun exerciseTypeLabel(code: Int): String = when (code) {
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Running"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Treadmill run"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Cycling"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Stationary cycling"
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "Rowing"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "Rowing machine"
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING -> "Stair climbing"
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE -> "Stair machine"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "Pool swimming"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Open-water swimming"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "Strength training"
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Weightlifting"
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "Calisthenics"
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "Pilates"
    ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> "Stretching"
    ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "Dancing"
    ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> "Golf"
    ExerciseSessionRecord.EXERCISE_TYPE_TENNIS -> "Tennis"
    ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL -> "Basketball"
    ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Football"
    else -> "Other workout"
}

/** Maps a Health Connect sleep stage code to a readable label. */
internal fun sleepStageLabel(stageType: Int): String = when (stageType) {
    SleepSessionRecord.STAGE_TYPE_AWAKE -> "Awake"
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "Awake in bed"
    SleepSessionRecord.STAGE_TYPE_SLEEPING -> "Sleeping"
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "Out of bed"
    SleepSessionRecord.STAGE_TYPE_LIGHT -> "Light"
    SleepSessionRecord.STAGE_TYPE_DEEP -> "Deep"
    SleepSessionRecord.STAGE_TYPE_REM -> "REM"
    else -> "Unknown"
}

// ---- Summaries ---------------------------------------------------------------------------------

/** Today's Samsung-origin totals (from Health Connect's aggregate API, not raw record counts). */
internal data class TodayTotals(
    val steps: Long?,
    val distanceMeters: Double?,
    val caloriesKcal: Double?,
)

/** Latest Samsung workout, with metrics attributed by time-overlap with the session. */
internal data class ExerciseSummary(
    val typeLabel: String,
    val start: Instant,
    val durationSeconds: Long,
    val distanceMeters: Double?,
    val avgSpeedMps: Double?,
    val maxSpeedMps: Double?,
    val caloriesKcal: Double?,
)

/** Latest Samsung sleep session broken into stage durations, plus overlapping SpO2. */
internal data class SleepSummary(
    val start: Instant,
    val end: Instant,
    val totalSeconds: Long,
    val awakeSeconds: Long,
    val lightSeconds: Long,
    val deepSeconds: Long,
    val remSeconds: Long,
    val spo2MinPct: Double?,
    val spo2AvgPct: Double?,
)

internal data class HeartOxygenSummary(
    val latestBpm: Long?,
    val latestBpmTime: Instant?,
    val todayMinBpm: Long?,
    val todayMaxBpm: Long?,
    val latestSpo2Pct: Double?,
    val latestSpo2Time: Instant?,
)

/**
 * Builds a Health Connect aggregate request restricted to Samsung Health's origin, so "today" and
 * per-exercise totals come from Health Connect's own aggregation (never a sum of raw records).
 */
internal fun buildSamsungAggregateRequest(
    metrics: Set<AggregateMetric<*>>,
    start: Instant,
    end: Instant,
): AggregateRequest = AggregateRequest(
    metrics = metrics,
    timeRangeFilter = TimeRangeFilter.between(start, end),
    dataOriginFilter = setOf(DataOrigin(SAMSUNG_SNAPSHOT_PACKAGE)),
)

/** Sums stage durations by Health Connect stage type. Input: (stageType, durationSeconds). */
internal fun summariseStageDurations(stageDurations: List<Pair<Int, Long>>): SleepStageTotals {
    var awake = 0L
    var light = 0L
    var deep = 0L
    var rem = 0L
    for ((type, seconds) in stageDurations) {
        when (type) {
            SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> awake += seconds
            SleepSessionRecord.STAGE_TYPE_LIGHT -> light += seconds
            SleepSessionRecord.STAGE_TYPE_DEEP -> deep += seconds
            SleepSessionRecord.STAGE_TYPE_REM -> rem += seconds
        }
    }
    return SleepStageTotals(awakeSeconds = awake, lightSeconds = light, deepSeconds = deep, remSeconds = rem)
}

internal data class SleepStageTotals(
    val awakeSeconds: Long,
    val lightSeconds: Long,
    val deepSeconds: Long,
    val remSeconds: Long,
)

/** Latest overall heart-rate sample plus today's min/max, from (bpm, time) samples. */
internal fun summariseHeartRate(
    samples: List<Pair<Long, Instant>>,
    dayStart: Instant,
): HeartOxygenSummary {
    if (samples.isEmpty()) {
        return HeartOxygenSummary(null, null, null, null, null, null)
    }
    val latest = samples.maxByOrNull { it.second }!!
    val today = samples.filter { !it.second.isBefore(dayStart) }.map { it.first }
    return HeartOxygenSummary(
        latestBpm = latest.first,
        latestBpmTime = latest.second,
        todayMinBpm = today.minOrNull(),
        todayMaxBpm = today.maxOrNull(),
        latestSpo2Pct = null,
        latestSpo2Time = null,
    )
}

/** Latest SpO2 sample, plus min/avg restricted to an optional window (e.g. the sleep period). */
internal fun summariseOxygen(
    samples: List<Pair<Double, Instant>>,
    windowStart: Instant?,
    windowEnd: Instant?,
): OxygenSummary {
    if (samples.isEmpty()) return OxygenSummary(null, null, null, null)
    val latest = samples.maxByOrNull { it.second }!!
    val inWindow = if (windowStart != null && windowEnd != null) {
        samples.filter { !it.second.isBefore(windowStart) && !it.second.isAfter(windowEnd) }.map { it.first }
    } else {
        emptyList()
    }
    val avg = if (inWindow.isEmpty()) null else inWindow.average()
    return OxygenSummary(
        latestPct = latest.first,
        latestTime = latest.second,
        windowMinPct = inWindow.minOrNull(),
        windowAvgPct = avg,
    )
}

internal data class OxygenSummary(
    val latestPct: Double?,
    val latestTime: Instant?,
    val windowMinPct: Double?,
    val windowAvgPct: Double?,
)

/** Average and maximum speed from a list of m/s samples, or null if empty. */
internal fun speedAvgMax(speedsMps: List<Double>): Pair<Double, Double>? {
    if (speedsMps.isEmpty()) return null
    return speedsMps.average() to speedsMps.max()
}

// ---- Formatting --------------------------------------------------------------------------------

/** Compact duration: "2h 05m", "45m", "30s". */
internal fun formatSnapshotDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return "0m"
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return when {
        hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        minutes > 0 -> "${minutes}m"
        else -> "${totalSeconds}s"
    }
}

private fun km(meters: Double): String = String.format(java.util.Locale.US, "%.2f km", meters / 1000.0)
private fun kcal(v: Double): String = String.format(java.util.Locale.US, "%.0f kcal", v)
private fun mps(v: Double): String = String.format(java.util.Locale.US, "%.1f m/s", v)
private fun pct(v: Double): String = String.format(java.util.Locale.US, "%.0f%%", v)

/** Everything the snapshot screen needs, already reduced to display-ready values. */
internal data class SnapshotData(
    val today: TodayTotals?,
    val exercise: ExerciseSummary?,
    val sleep: SleepSummary?,
    val heartOxygen: HeartOxygenSummary?,
    val latestSourceTimestamp: Instant?,
    val generatedAt: Instant,
)

/**
 * Formats the snapshot as a readable summary for Warwick -- plain language, mapped labels, units,
 * and "Not available" for anything missing. Not a developer log.
 */
internal fun formatSnapshot(data: SnapshotData, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    val dateTime = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(zone)
    val na = "Not available"
    val sb = StringBuilder()

    sb.appendLine("SAMSUNG HEALTH SNAPSHOT")
    sb.appendLine()

    sb.appendLine("TODAY")
    val t = data.today
    sb.appendLine("• Steps: ${t?.steps?.let { String.format(java.util.Locale.US, "%,d", it) } ?: na}")
    sb.appendLine("• Distance: ${t?.distanceMeters?.let { km(it) } ?: na}")
    // TotalCaloriesBurnedRecord is energy EXPENDED (resting metabolic rate + activity), not food
    // eaten -- labelled "burned" so it can't be misread as calorie intake.
    sb.appendLine("• Calories burned: ${t?.caloriesKcal?.let { kcal(it) } ?: na}")
    sb.appendLine()

    sb.appendLine("LATEST EXERCISE")
    val e = data.exercise
    if (e == null) {
        sb.appendLine("• $na")
    } else {
        sb.appendLine("• ${e.typeLabel} — ${dateTime.format(e.start)}")
        sb.appendLine("• Duration: ${formatSnapshotDuration(e.durationSeconds)}")
        sb.appendLine("• Distance: ${e.distanceMeters?.let { km(it) } ?: na}")
        val speed = if (e.avgSpeedMps != null && e.maxSpeedMps != null) {
            "${mps(e.avgSpeedMps)} avg (max ${mps(e.maxSpeedMps)})"
        } else na
        sb.appendLine("• Speed: $speed")
        sb.appendLine("• Calories burned: ${e.caloriesKcal?.let { kcal(it) } ?: na}")
    }
    sb.appendLine()

    sb.appendLine("LAST SLEEP")
    val s = data.sleep
    if (s == null) {
        sb.appendLine("• $na")
    } else {
        sb.appendLine("• ${dateTime.format(s.start)} → ${time.format(s.end)}")
        sb.appendLine("• Total: ${formatSnapshotDuration(s.totalSeconds)}")
        sb.appendLine("• Awake: ${formatSnapshotDuration(s.awakeSeconds)}")
        sb.appendLine("• Light: ${stageOrNa(s.lightSeconds)}")
        sb.appendLine("• Deep: ${stageOrNa(s.deepSeconds)}")
        sb.appendLine("• REM: ${stageOrNa(s.remSeconds)}")
        val spo2 = if (s.spo2MinPct != null && s.spo2AvgPct != null) {
            "min ${pct(s.spo2MinPct)}, avg ${pct(s.spo2AvgPct)}"
        } else na
        sb.appendLine("• Oxygen during sleep: $spo2")
    }
    sb.appendLine()

    sb.appendLine("HEART & OXYGEN")
    val h = data.heartOxygen
    if (h == null) {
        sb.appendLine("• $na")
    } else {
        sb.appendLine("• Latest heart rate: ${h.latestBpm?.let { "$it bpm at ${time.format(h.latestBpmTime)}" } ?: na}")
        val range = if (h.todayMinBpm != null && h.todayMaxBpm != null) {
            "${h.todayMinBpm}–${h.todayMaxBpm} bpm"
        } else na
        sb.appendLine("• Today's heart rate range: $range")
        sb.appendLine("• Latest oxygen: ${h.latestSpo2Pct?.let { "${pct(it)} at ${time.format(h.latestSpo2Time)}" } ?: na}")
    }
    sb.appendLine()

    sb.appendLine("DETAILS")
    sb.appendLine("• Source: Samsung Health via Health Connect ($SAMSUNG_SNAPSHOT_PACKAGE)")
    sb.appendLine("• Latest source data: ${data.latestSourceTimestamp?.let { dateTime.format(it) } ?: na}")
    sb.appendLine("• Refreshed: ${dateTime.format(data.generatedAt)}")
    sb.appendLine("Read-only; nothing is stored or uploaded.")
    return sb.toString()
}

private fun stageOrNa(seconds: Long): String =
    if (seconds > 0) formatSnapshotDuration(seconds) else "Not available"
