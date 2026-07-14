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
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.PowerRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * BUILD-005/WP2 — Samsung Health Capability Sweep. A standalone, separately-launched Activity
 * (own LAUNCHER entry, distinct name) so WP1/PR2's [MainActivity] and the PR4b [SyncSpikeActivity]
 * are both left untouched.
 *
 * Inventory experiment only: for each supported Health Connect record type it reads (read-only),
 * retains only records whose observed writer package is Samsung Health, and reports status, counts,
 * timestamps, observed writer packages and a compact latest-value summary. All in memory: no
 * database, no persistent token, no network, no upload, no Samsung SDK, no Withings OAuth.
 * Withings- and MyFitnessPal-originated records are never queried or reported here.
 */
class SamsungSweepActivity : AppCompatActivity() {

    // Every record type the sweep compiles against, in the current stable Health Connect
    // dependency (androidx.health.connect:connect-client:1.1.0). SamsungCapabilitySweepPermissions
    // (unit-tested) mirrors the exact read permissions these require against AndroidManifest.xml.
    private val sweptTypes = listOf(
        StepsRecord::class,
        DistanceRecord::class,
        FloorsClimbedRecord::class,
        ActiveCaloriesBurnedRecord::class,
        TotalCaloriesBurnedRecord::class,
        ExerciseSessionRecord::class,
        ElevationGainedRecord::class,
        SpeedRecord::class,
        // CyclingPedalingCadenceRecord is intentionally omitted: connect-client 1.1.0's
        // HealthPermission.getReadPermission does not expose a distinct READ permission string for
        // it (it collapses onto another permission), so it cannot be requested/declared cleanly.
        // Treated as SDK_UNAVAILABLE for this sweep rather than blocking the build.
        PowerRecord::class,
        SleepSessionRecord::class,
        HeartRateRecord::class,
        RestingHeartRateRecord::class,
        HeartRateVariabilityRmssdRecord::class,
        OxygenSaturationRecord::class,
        RespiratoryRateRecord::class,
        BloodPressureRecord::class,
        BloodGlucoseRecord::class,
        BodyTemperatureRecord::class,
    )

    private val permissions: Set<String> by lazy {
        sweptTypes.map { HealthPermission.getReadPermission(it) }.toSet()
    }

    private lateinit var healthConnectClient: HealthConnectClient
    private var lastReport: String = ""

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            appendStatus("Permission dialog returned; running sweep with the permissions granted...")
            runSweep(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_samsung_sweep)

        findViewById<Button>(R.id.runSweepButton).setOnClickListener { onRunClicked() }
        findViewById<Button>(R.id.copyReportButton).setOnClickListener { onCopyClicked() }

        showStatus(
            "Fusion Health — Samsung Capability Sweep\n\n" +
                "Inventory only: discovers which Samsung Health-originated data types Fusion can " +
                "already read through Health Connect. Read-only, nothing stored or uploaded.\n\n" +
                "Tap \"RUN SAMSUNG SWEEP\", grant the requested read permissions, then use " +
                "\"COPY REPORT\" to paste the full inventory back into chat.\n"
        )
    }

    private fun onRunClicked() {
        val availability = HealthConnectClient.getSdkStatus(this)
        if (availability != HealthConnectClient.SDK_AVAILABLE) {
            appendStatus("Health Connect is not available on this device (SDK status $availability).")
            return
        }
        healthConnectClient = HealthConnectClient.getOrCreate(this)

        lifecycleScope.launch {
            try {
                val granted = healthConnectClient.permissionController.getGrantedPermissions()
                if (permissions.all { it in granted }) {
                    runSweep(granted)
                } else {
                    appendStatus("Requesting ${permissions.size} Samsung-relevant read permissions...")
                    requestPermissions.launch(permissions)
                }
            } catch (e: SecurityException) {
                appendStatus("SecurityException reading granted permissions: ${e.message}")
            } catch (e: Exception) {
                appendStatus("Failed to read granted permissions: ${e.message}")
            }
        }
    }

    private fun runSweep(granted: Set<String>) {
        lifecycleScope.launch {
            try {
                val zone = ZoneId.systemDefault()
                val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()

                val results = listOf(
                    // ---- Activity & exercise ----
                    sweep<StepsRecord>("Steps", granted, { it.startTime }) { s ->
                        val latest = s.maxByOrNull { it.startTime }!!
                        val todaySum = s.filter { it.startTime >= dayStart }.sumOf { it.count }
                        "latest_count=${latest.count}; samsung_today_raw_sum=$todaySum " +
                            "(raw Samsung-origin sum for the local day, NOT a dedup aggregate)"
                    },
                    sweep<DistanceRecord>("Distance", granted, { it.startTime }) { s ->
                        "latest_distance_m=${fmt1(s.maxByOrNull { it.startTime }!!.distance.inMeters)}"
                    },
                    sweep<FloorsClimbedRecord>("Floors climbed", granted, { it.startTime }) { s ->
                        "latest_floors=${fmt1(s.maxByOrNull { it.startTime }!!.floors)}"
                    },
                    sweep<ActiveCaloriesBurnedRecord>("Active calories", granted, { it.startTime }) { s ->
                        "latest_kcal=${fmt1(s.maxByOrNull { it.startTime }!!.energy.inKilocalories)}"
                    },
                    sweep<TotalCaloriesBurnedRecord>("Total calories", granted, { it.startTime }) { s ->
                        "latest_kcal=${fmt1(s.maxByOrNull { it.startTime }!!.energy.inKilocalories)}"
                    },
                    sweep<ExerciseSessionRecord>("Exercise sessions", granted, { it.startTime }) { s ->
                        val l = s.maxByOrNull { it.startTime }!!
                        val dur = Duration.between(l.startTime, l.endTime).seconds
                        "latest_type_code=${l.exerciseType}; duration=${formatDuration(dur)}"
                    },
                    sweep<ElevationGainedRecord>("Elevation gained", granted, { it.startTime }) { s ->
                        "latest_elevation_m=${fmt1(s.maxByOrNull { it.startTime }!!.elevation.inMeters)}"
                    },
                    sweep<SpeedRecord>("Speed", granted, { it.startTime }) { null },
                    sweep<PowerRecord>("Power", granted, { it.startTime }) { null },
                    // ---- Sleep ----
                    sweep<SleepSessionRecord>("Sleep sessions", granted, { it.startTime }) { s ->
                        val l = s.maxByOrNull { it.startTime }!!
                        val dur = Duration.between(l.startTime, l.endTime).seconds
                        val stageCodes = l.stages.groupingBy { it.stage }.eachCount()
                        "latest_duration=${formatDuration(dur)}; stage_count=${l.stages.size}; " +
                            "stage_code_histogram=$stageCodes"
                    },
                    // ---- Heart & physiology ----
                    sweep<HeartRateRecord>("Heart rate", granted, { it.startTime }) { s ->
                        val latestRec = s.maxByOrNull { it.startTime }!!
                        val lastSample = latestRec.samples.maxByOrNull { it.time }
                        "latest_sample_bpm=${lastSample?.beatsPerMinute ?: "-"}"
                    },
                    sweep<RestingHeartRateRecord>("Resting heart rate", granted, { it.time }) { s ->
                        "latest_bpm=${s.maxByOrNull { it.time }!!.beatsPerMinute}"
                    },
                    sweep<HeartRateVariabilityRmssdRecord>("HRV (RMSSD)", granted, { it.time }) { s ->
                        "latest_rmssd_ms=${fmt1(s.maxByOrNull { it.time }!!.heartRateVariabilityMillis)}"
                    },
                    sweep<OxygenSaturationRecord>("Oxygen saturation", granted, { it.time }) { s ->
                        "latest_spo2_pct=${fmt1(s.maxByOrNull { it.time }!!.percentage.value)}"
                    },
                    sweep<RespiratoryRateRecord>("Respiratory rate", granted, { it.time }) { s ->
                        "latest_rate=${fmt1(s.maxByOrNull { it.time }!!.rate)}"
                    },
                    sweep<BloodPressureRecord>("Blood pressure", granted, { it.time }) { s ->
                        val l = s.maxByOrNull { it.time }!!
                        "latest=${fmt0(l.systolic.inMillimetersOfMercury)}/" +
                            "${fmt0(l.diastolic.inMillimetersOfMercury)} mmHg"
                    },
                    sweep<BloodGlucoseRecord>("Blood glucose", granted, { it.time }) { s ->
                        "latest_mg_dl=${fmt1(s.maxByOrNull { it.time }!!.level.inMilligramsPerDeciliter)}"
                    },
                    sweep<BodyTemperatureRecord>("Body temperature", granted, { it.time }) { s ->
                        "latest_celsius=${fmt1(s.maxByOrNull { it.time }!!.temperature.inCelsius)}"
                    },
                )

                val generatedAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(zone).format(Instant.now())
                lastReport = formatSweepReport(results, "$generatedAt ($zone)")
                showStatus(lastReport)
                findViewById<Button>(R.id.copyReportButton).isEnabled = true
            } catch (e: Exception) {
                appendStatus("Sweep failed: ${e.message}")
            }
        }
    }

    /**
     * Reads one record type (all pages), retains only Samsung Health-originated records, and builds
     * its [TypeSweepResult]. The value summary is computed only when Samsung records exist. The
     * only SDK touch-points are the paginated read and reading `metadata.dataOrigin.packageName`.
     */
    private suspend inline fun <reified T : Record> sweep(
        label: String,
        granted: Set<String>,
        crossinline timeOf: (T) -> Instant,
        crossinline summarise: (samsung: List<T>) -> String?,
    ): TypeSweepResult {
        val permission = HealthPermission.getReadPermission(T::class)
        if (permission !in granted) return TypeSweepResult(label, SweepStatus.PERMISSION_DENIED)

        val pagination = try {
            accumulatePages<T>(MAX_PAGES) { pageToken ->
                val response = healthConnectClient.readRecords(buildReadRecordsRequest(T::class, pageToken))
                PageFetchResult(response.records, response.pageToken)
            }
        } catch (e: SecurityException) {
            return TypeSweepResult(label, SweepStatus.PERMISSION_DENIED)
        } catch (e: Exception) {
            return TypeSweepResult(label, SweepStatus.READ_ERROR, valueSummary = e.message)
        }

        val all = pagination.records
        val samsung = retainSamsung(all) { it.metadata.dataOrigin.packageName }
        val times = samsung.map { timeOf(it) }
        return TypeSweepResult(
            label = label,
            status = populatedOrEmpty(samsung.size),
            count = samsung.size,
            pagesRead = pagination.pagesRead,
            earliest = times.minOrNull(),
            latest = times.maxOrNull(),
            writerPackages = all.map { it.metadata.dataOrigin.packageName }.toSet(),
            samsungFound = samsung.isNotEmpty(),
            valueSummary = if (samsung.isEmpty()) null else summarise(samsung),
        )
    }

    private fun onCopyClicked() {
        if (lastReport.isEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Samsung Capability Sweep", lastReport))
        appendStatus("Report copied to clipboard.")
    }

    private fun appendStatus(line: String) {
        val current = findViewById<TextView>(R.id.samsungSweepStatusText).text
        showStatus("$current\n$line")
    }

    private fun showStatus(text: String) {
        findViewById<TextView>(R.id.samsungSweepStatusText).text = text
    }

    private companion object {
        const val MAX_PAGES = 200
    }
}

/** One-decimal format helper, locale-stable (US) so reports are consistent across devices. */
private fun fmt1(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

/** Zero-decimal format helper, locale-stable (US). */
private fun fmt0(value: Double): String = String.format(java.util.Locale.US, "%.0f", value)
