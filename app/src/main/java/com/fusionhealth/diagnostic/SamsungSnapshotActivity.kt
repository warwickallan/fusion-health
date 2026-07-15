package com.fusionhealth.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

/**
 * BUILD-005/WP2 — Samsung Health Snapshot. The first user-facing health summary: a readable view of
 * the Samsung Health data Fusion can already read through Health Connect. Standalone launcher (own
 * task, so its icon always opens this screen); MainActivity and the diagnostic/spike screens are
 * untouched.
 *
 * Read-only, in-memory only: no database, no network, no upload, no Samsung SDK, no Withings OAuth.
 * All figures are Samsung-origin only; daily totals use Health Connect's aggregate API (never raw
 * record counts); codes are shown as human-readable labels; missing data reads "Not available".
 */
class SamsungSnapshotActivity : AppCompatActivity() {

    private val recordTypes = listOf(
        StepsRecord::class,
        DistanceRecord::class,
        TotalCaloriesBurnedRecord::class,
        ExerciseSessionRecord::class,
        SpeedRecord::class,
        SleepSessionRecord::class,
        HeartRateRecord::class,
        OxygenSaturationRecord::class,
    )

    private val permissions: Set<String> by lazy {
        recordTypes.map { HealthPermission.getReadPermission(it) }.toSet()
    }

    private lateinit var healthConnectClient: HealthConnectClient
    private var lastSummary: String = ""

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (permissions.all { it in granted }) {
                loadSnapshot()
            } else {
                showState(
                    "Permission needed\n\n" +
                        "Fusion needs read access to your Samsung Health data in Health Connect to " +
                        "show this snapshot. Tap Refresh and allow the requested permissions."
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_samsung_snapshot)

        findViewById<Button>(R.id.refreshSnapshotButton).setOnClickListener { onRefreshClicked() }
        findViewById<Button>(R.id.copySummaryButton).setOnClickListener { onCopyClicked() }

        showState(
            "Samsung Health Snapshot\n\n" +
                "A quick summary of the Samsung Health data Fusion can read through Health Connect.\n\n" +
                "Tap Refresh to load your latest snapshot."
        )
    }

    private fun onRefreshClicked() {
        val availability = HealthConnectClient.getSdkStatus(this)
        if (availability != HealthConnectClient.SDK_AVAILABLE) {
            showState("Health Connect isn't available on this device (status $availability).")
            return
        }
        healthConnectClient = HealthConnectClient.getOrCreate(this)
        showState("Loading your snapshot…")

        lifecycleScope.launch {
            try {
                val granted = healthConnectClient.permissionController.getGrantedPermissions()
                if (permissions.all { it in granted }) {
                    loadSnapshot()
                } else {
                    requestPermissions.launch(permissions)
                }
            } catch (e: SecurityException) {
                showState("Couldn't check permissions: ${e.message}")
            } catch (e: Exception) {
                showState("Couldn't load your snapshot: ${e.message}")
            }
        }
    }

    private fun loadSnapshot() {
        lifecycleScope.launch {
            try {
                val now = Instant.now()
                val dayStart = localDayStart(now = now)

                val today = readTodayTotals(dayStart, now)
                val exercise = readLatestExercise()
                val sleep = readLastSleep()
                val heartOxygen = readHeartAndOxygen(dayStart, sleep)

                val latestSource = listOfNotNull(
                    exercise?.start,
                    sleep?.end,
                    heartOxygen?.latestBpmTime,
                    heartOxygen?.latestSpo2Time,
                ).maxOrNull()

                val data = SnapshotData(
                    today = today,
                    exercise = exercise,
                    sleep = sleep,
                    heartOxygen = heartOxygen,
                    latestSourceTimestamp = latestSource,
                    generatedAt = now,
                )

                val nothing = today == null && exercise == null && sleep == null && heartOxygen == null
                lastSummary = if (nothing) {
                    "Samsung Health Snapshot\n\nNo Samsung Health data was found in Health Connect yet."
                } else {
                    formatSnapshot(data)
                }
                showState(lastSummary)
                findViewById<Button>(R.id.copySummaryButton).isEnabled = !nothing
            } catch (e: SecurityException) {
                showState("Permission was refused while reading: ${e.message}")
            } catch (e: Exception) {
                showState("Something went wrong building your snapshot: ${e.message}")
            }
        }
    }

    private suspend fun readTodayTotals(dayStart: Instant, now: Instant): TodayTotals? {
        return try {
            val response = healthConnectClient.aggregate(
                buildSamsungAggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL,
                    ),
                    start = dayStart,
                    end = now,
                )
            )
            TodayTotals(
                steps = response[StepsRecord.COUNT_TOTAL],
                distanceMeters = response[DistanceRecord.DISTANCE_TOTAL]?.inMeters,
                caloriesKcal = response[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun readLatestExercise(): ExerciseSummary? {
        val latest = readSamsung<ExerciseSessionRecord>().maxByOrNull { it.startTime } ?: return null
        val windowAggregate = try {
            healthConnectClient.aggregate(
                buildSamsungAggregateRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    start = latest.startTime,
                    end = latest.endTime,
                )
            )
        } catch (e: Exception) {
            null
        }
        val speeds = readSamsung<SpeedRecord>()
            .flatMap { it.samples }
            .filter { !it.time.isBefore(latest.startTime) && !it.time.isAfter(latest.endTime) }
            .map { it.speed.inMetersPerSecond }
        val avgMax = speedAvgMax(speeds)

        return ExerciseSummary(
            typeLabel = exerciseTypeLabel(latest.exerciseType),
            start = latest.startTime,
            durationSeconds = Duration.between(latest.startTime, latest.endTime).seconds,
            distanceMeters = windowAggregate?.get(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            avgSpeedMps = avgMax?.first,
            maxSpeedMps = avgMax?.second,
            caloriesKcal = windowAggregate?.get(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
        )
    }

    private suspend fun readLastSleep(): SleepSummary? {
        val latest = readSamsung<SleepSessionRecord>().maxByOrNull { it.startTime } ?: return null
        val stageDurations = latest.stages.map {
            it.stage to Duration.between(it.startTime, it.endTime).seconds
        }
        val totals = summariseStageDurations(stageDurations)
        val oxygen = summariseOxygen(
            samples = readSamsung<OxygenSaturationRecord>().map { it.percentage.value to it.time },
            windowStart = latest.startTime,
            windowEnd = latest.endTime,
        )
        return SleepSummary(
            start = latest.startTime,
            end = latest.endTime,
            totalSeconds = Duration.between(latest.startTime, latest.endTime).seconds,
            awakeSeconds = totals.awakeSeconds,
            lightSeconds = totals.lightSeconds,
            deepSeconds = totals.deepSeconds,
            remSeconds = totals.remSeconds,
            spo2MinPct = oxygen.windowMinPct,
            spo2AvgPct = oxygen.windowAvgPct,
        )
    }

    private suspend fun readHeartAndOxygen(dayStart: Instant, sleep: SleepSummary?): HeartOxygenSummary? {
        val hrSamples = readSamsung<HeartRateRecord>()
            .flatMap { rec -> rec.samples.map { it.beatsPerMinute to it.time } }
        val oxygenSamples = readSamsung<OxygenSaturationRecord>().map { it.percentage.value to it.time }
        if (hrSamples.isEmpty() && oxygenSamples.isEmpty()) return null

        val hr = summariseHeartRate(hrSamples, dayStart)
        val oxygen = summariseOxygen(oxygenSamples, sleep?.start, sleep?.end)
        return hr.copy(
            latestSpo2Pct = oxygen.latestPct,
            latestSpo2Time = oxygen.latestTime,
        )
    }

    /** Reads all records of a type and keeps only Samsung Health-origin ones. */
    private suspend inline fun <reified T : Record> readSamsung(): List<T> {
        val pagination = accumulatePages<T>(MAX_PAGES) { pageToken ->
            val response = healthConnectClient.readRecords(buildReadRecordsRequest(T::class, pageToken))
            PageFetchResult(response.records, response.pageToken)
        }
        return pagination.records.filter {
            it.metadata.dataOrigin.packageName == SAMSUNG_SNAPSHOT_PACKAGE
        }
    }

    private fun onCopyClicked() {
        if (lastSummary.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Samsung Health Snapshot", lastSummary))
        showState("$lastSummary\n\n(Summary copied to clipboard.)")
    }

    private fun showState(text: String) {
        findViewById<TextView>(R.id.snapshotText).text = text
    }

    private companion object {
        const val MAX_PAGES = 200
    }
}
