package com.fusionhealth.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BasalMetabolicRateRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyWaterMassRecord
import androidx.health.connect.client.records.BoneMassRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.LeanBodyMassRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * BUILD-005/WP2 — Fusion247 Health Snapshot. One readable page combining:
 *   - Samsung Health activity, exercise, sleep, heart rate and oxygen (device-proven behaviour,
 *     unchanged from the accepted Samsung Health Snapshot);
 *   - Withings body composition (every populated body-measurement type in the current stable SDK);
 *   - MyFitnessPal nutrition (every populated NutritionRecord field);
 *   - Fusion's own manually-logged chest/waist measurements.
 *
 * External sources are Health Connect-only and read-only, held in memory. The only durable storage
 * is the Fusion manual body log (see [BodyLogStore]). Permission state is reported per record type
 * — a denied permission reads PERMISSION_DENIED, never EMPTY.
 */
class SamsungSnapshotActivity : AppCompatActivity() {

    private val samsungTypes = listOf(
        StepsRecord::class, DistanceRecord::class, TotalCaloriesBurnedRecord::class,
        ExerciseSessionRecord::class, SpeedRecord::class, SleepSessionRecord::class,
        HeartRateRecord::class, OxygenSaturationRecord::class,
    )

    private val withingsTypes = listOf(
        WeightRecord::class, BodyFatRecord::class, LeanBodyMassRecord::class,
        BodyWaterMassRecord::class, BoneMassRecord::class, BasalMetabolicRateRecord::class,
        HeightRecord::class,
    )

    private val permissions: Set<String> by lazy {
        (samsungTypes + withingsTypes + listOf(NutritionRecord::class))
            .map { HealthPermission.getReadPermission(it) }.toSet()
    }

    private lateinit var healthConnectClient: HealthConnectClient
    private lateinit var bodyLogStore: BodyLogStore
    private var lastSummary: String = ""
    private var requestedOnce = false

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            // Proceed with whatever was granted — per-type state reports anything still denied.
            loadSnapshot(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_samsung_snapshot)
        bodyLogStore = BodyLogStore(File(filesDir, "body_measurements.txt"))

        findViewById<Button>(R.id.refreshSnapshotButton).setOnClickListener { onRefreshClicked() }
        findViewById<Button>(R.id.copySummaryButton).setOnClickListener { onCopyClicked() }
        findViewById<Button>(R.id.logBodyButton).setOnClickListener {
            startActivity(Intent(this, BodyLogActivity::class.java))
        }

        showState(
            "Fusion247 Health Snapshot\n\n" +
                "One page for your Samsung activity, Withings body composition, MyFitnessPal " +
                "nutrition, and your own chest/waist log.\n\n" +
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
                if (permissions.all { it in granted } || requestedOnce) {
                    loadSnapshot(granted)
                } else {
                    requestedOnce = true
                    requestPermissions.launch(permissions)
                }
            } catch (e: SecurityException) {
                showState("Couldn't check permissions: ${e.message}")
            } catch (e: Exception) {
                showState("Couldn't load your snapshot: ${e.message}")
            }
        }
    }

    private fun loadSnapshot(granted: Set<String>) {
        lifecycleScope.launch {
            try {
                val now = Instant.now()
                val zone = ZoneId.systemDefault()
                val dayStart = localDayStart(zone, now)

                val today = readTodayTotals(dayStart, now)
                val exercise = readLatestExercise()
                val sleep = readLastSleep()
                val heartOxygen = readHeartAndOxygen(dayStart, sleep)
                val bodyComposition = readBodyComposition(granted)
                val nutrition = readNutrition(granted, dayStart, zone, now)
                val bodyLog = buildBodyLogDisplay(bodyLogStore.loadAll())

                val latestSource = listOfNotNull(
                    exercise?.start, sleep?.end, heartOxygen?.latestBpmTime,
                    heartOxygen?.latestSpo2Time, nutrition.latestRecordTime,
                    bodyComposition.metrics.mapNotNull { it.latest?.time }.maxOrNull(),
                ).maxOrNull()

                val data = SnapshotData(
                    today = today,
                    exercise = exercise,
                    sleep = sleep,
                    heartOxygen = heartOxygen,
                    latestSourceTimestamp = latestSource,
                    generatedAt = now,
                    bodyComposition = bodyComposition,
                    nutrition = nutrition,
                    bodyLog = bodyLog,
                )

                lastSummary = formatSnapshot(data)
                showState(lastSummary)
                findViewById<Button>(R.id.copySummaryButton).isEnabled = true
            } catch (e: SecurityException) {
                showState("Permission was refused while reading: ${e.message}")
            } catch (e: Exception) {
                showState("Something went wrong building your snapshot: ${e.message}")
            }
        }
    }

    // ---- Withings body composition -------------------------------------------------------------

    private suspend fun readBodyComposition(granted: Set<String>): BodyCompositionData {
        val weight = bodyMetric<WeightRecord>("Weight", "kg", granted) {
            MetricReading(it.weight.inKilograms, it.time)
        }
        val bodyFat = bodyMetric<BodyFatRecord>("Body fat", "%", granted) {
            MetricReading(it.percentage.value, it.time)
        }
        val leanMass = bodyMetric<LeanBodyMassRecord>("Lean body mass", "kg", granted) {
            MetricReading(it.mass.inKilograms, it.time)
        }
        val bodyWater = bodyMetric<BodyWaterMassRecord>("Body water mass", "kg", granted) {
            MetricReading(it.mass.inKilograms, it.time)
        }
        val boneMass = bodyMetric<BoneMassRecord>("Bone mass", "kg", granted) {
            MetricReading(it.mass.inKilograms, it.time)
        }
        val bmr = bodyMetric<BasalMetabolicRateRecord>("Basal metabolic rate", "kcal/day", granted) {
            MetricReading(it.basalMetabolicRate.inKilocaloriesPerDay, it.time)
        }
        val height = bodyMetric<HeightRecord>("Height", "cm", granted) {
            MetricReading(it.height.inMeters * 100.0, it.time)
        }
        // WaistCircumferenceRecord does not exist in connect-client 1.1.0 (the current stable
        // dependency), so a Withings-sourced waist cannot be read — reported honestly here;
        // Fusion's manual waist log is the authoritative waist measurement for now.
        val waist = BodyMetricState("Waist circumference (Withings)", "cm", SourceReadStatus.SDK_UNAVAILABLE)

        // Derived values use metres for BMI; height state displays cm.
        val heightMetres = height.latest?.let { MetricReading(it.value / 100.0, it.time) }
        return BodyCompositionData(
            metrics = listOf(weight, bodyFat, leanMass, bodyWater, boneMass, bmr, height, waist),
            fatMassKg = calculatedFatMassKg(weight.latest, bodyFat.latest),
            bmi = calculatedBmi(weight.latest, heightMetres),
        )
    }

    private suspend inline fun <reified T : Record> bodyMetric(
        label: String,
        unit: String,
        granted: Set<String>,
        crossinline reading: (T) -> MetricReading,
    ): BodyMetricState {
        val permitted = HealthPermission.getReadPermission(T::class) in granted
        if (!permitted) return classifyBodyMetric(label, unit, false, null)
        return try {
            val all = readAllPages<T>()
            val withings = retainByWriter(all, WITHINGS_PACKAGE) { it.metadata.dataOrigin.packageName }
            classifyBodyMetric(label, unit, true, withings.map { reading(it) })
        } catch (e: SecurityException) {
            classifyBodyMetric(label, unit, false, null)
        } catch (e: Exception) {
            classifyBodyMetric(label, unit, true, null, error = e.message ?: "read error")
        }
    }

    // ---- MyFitnessPal nutrition ------------------------------------------------------------------

    private suspend fun readNutrition(
        granted: Set<String>,
        dayStart: Instant,
        zone: ZoneId,
        now: Instant,
    ): NutritionData {
        if (HealthPermission.getReadPermission(NutritionRecord::class) !in granted) {
            return NutritionData(SourceReadStatus.PERMISSION_DENIED, null, null, emptyList(), emptyList(), null)
        }
        val entries = try {
            val all = readAllPages<NutritionRecord>()
            retainByWriter(all, MFP_PACKAGE) { it.metadata.dataOrigin.packageName }
                .map { toNutritionEntry(it) }
        } catch (e: Exception) {
            return NutritionData(
                SourceReadStatus.READ_ERROR, null, null, emptyList(), emptyList(), null,
                errorMessage = e.message ?: "read error",
            )
        }
        if (entries.isEmpty()) {
            return NutritionData(SourceReadStatus.EMPTY, null, null, emptyList(), emptyList(), null)
        }
        val yesterdayStart = dayStart.atZone(zone).minusDays(1).toInstant()
        val todayEntries = entriesInDay(entries, dayStart, now.plusSeconds(1))
        val yesterdayEntries = entriesInDay(entries, yesterdayStart, dayStart)
        return NutritionData(
            status = SourceReadStatus.POPULATED,
            todayTotals = macroTotals(todayEntries),
            yesterdayTotals = macroTotals(yesterdayEntries),
            todayMeals = todayEntries,
            todayOtherNutrients = sumOtherNutrients(todayEntries),
            latestRecordTime = entries.maxOf { it.start },
        )
    }

    // ---- Samsung sections (device-proven behaviour, unchanged) ----------------------------------

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

    // ---- Shared read helpers ---------------------------------------------------------------------

    private suspend inline fun <reified T : Record> readAllPages(): List<T> {
        val pagination = accumulatePages<T>(MAX_PAGES) { pageToken ->
            val response = healthConnectClient.readRecords(buildReadRecordsRequest(T::class, pageToken))
            PageFetchResult(response.records, response.pageToken)
        }
        return pagination.records
    }

    private suspend inline fun <reified T : Record> readSamsung(): List<T> =
        retainByWriter(readAllPages<T>(), SAMSUNG_SNAPSHOT_PACKAGE) { it.metadata.dataOrigin.packageName }

    private fun onCopyClicked() {
        if (lastSummary.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Fusion247 Health Snapshot", lastSummary))
        showState("$lastSummary\n\n(Summary copied to clipboard.)")
    }

    private fun showState(text: String) {
        findViewById<TextView>(R.id.snapshotText).text = text
    }

    private companion object {
        const val MAX_PAGES = 200
    }
}
