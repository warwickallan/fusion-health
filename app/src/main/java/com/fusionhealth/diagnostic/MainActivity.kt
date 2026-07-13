package com.fusionhealth.diagnostic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * WP1/PR2 — Health Connect diagnostic baseline. Read-only, local-only: reads Health Connect
 * records already written by other apps (Samsung Health, MyFitnessPal, Withings), reports what's
 * visible, and classifies each source app's readability. Nothing is written to Health Connect,
 * nothing is uploaded, nothing is stored beyond this activity's lifetime.
 */
class MainActivity : AppCompatActivity() {

    private enum class TypeState { POPULATED, EMPTY, PERMISSION_DENIED, READ_ERROR }

    private data class TypeResult(
        val label: String,
        val state: TypeState,
        val count: Int = 0,
        val pagesRead: Int = 0,
        val earliest: Instant? = null,
        val latest: Instant? = null,
        val sourcePackages: Set<String> = emptySet(),
        val truncated: Boolean = false,
        val truncationReason: String? = null,
        val errorMessage: String? = null,
    )

    private enum class AppState { POPULATED, EMPTY, PERMISSION_DENIED, READ_ERROR }

    // Package names for the three source apps this diagnostic classifies. Verified against
    // Warwick's real device (PR2 second device test, 2026-07-13): Health Connect returned
    // records with these exact dataOrigin.packageName values for Samsung Health, MyFitnessPal
    // and Withings respectively.
    private object SourceApps {
        const val SAMSUNG_HEALTH = "com.sec.android.app.shealth"
        const val MYFITNESSPAL = "com.myfitnesspal.android"
        const val WITHINGS = "com.withings.wiscale2"
    }

    private companion object {
        // Defensive upper bound on pages read per record type. Health Connect's own
        // pagination should terminate via a null pageToken long before this; this guard exists
        // only to prevent an unbounded loop if the API ever returns a token that never resolves
        // to null, or repeats a token it has already returned.
        const val MAX_PAGES_PER_TYPE = 200
    }

    private val permissions: Set<String> by lazy {
        setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SleepSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(NutritionRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
            HealthPermission.getReadPermission(BodyFatRecord::class),
        )
    }

    private lateinit var healthConnectClient: HealthConnectClient
    private var lastDiagnosticText: String = ""

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            runDiagnostic()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.runDiagnosticButton).setOnClickListener { onRunDiagnosticClicked() }
        findViewById<Button>(R.id.copyExportButton).setOnClickListener { onCopyExportClicked() }

        showStatus(
            "Fusion Health — Health Connect Diagnostic (WP1/PR2)\n\n" +
                "No health data has been read yet. Tap \"Run Health Connect Diagnostic\" to check " +
                "availability, request read-only permissions, and report what's visible.\n\n" +
                "Nothing is written to Health Connect. Nothing is uploaded. Nothing is stored " +
                "beyond this screen."
        )
    }

    private fun onRunDiagnosticClicked() {
        val availability = HealthConnectClient.getSdkStatus(this)
        if (availability != HealthConnectClient.SDK_AVAILABLE) {
            showStatus(
                "Health Connect is not available on this device.\n" +
                    "SDK status code: $availability\n\n" +
                    "Samsung Health, MyFitnessPal and Withings are all classified UNAVAILABLE " +
                    "because Health Connect itself is not usable here."
            )
            return
        }

        healthConnectClient = HealthConnectClient.getOrCreate(this)
        requestPermissions.launch(permissions)
    }

    private fun runDiagnostic() {
        lifecycleScope.launch {
            val granted = healthConnectClient.permissionController.getGrantedPermissions()

            val results = listOf(
                readType<StepsRecord>("Steps/Activity", granted),
                readType<SleepSessionRecord>("Sleep", granted),
                readType<HeartRateRecord>("Heart rate", granted),
                readType<NutritionRecord>("Nutrition", granted),
                readType<WeightRecord>("Weight", granted),
                readType<BodyFatRecord>("Body fat/composition", granted),
            )

            val diagnosticText = buildDiagnosticText(results)
            lastDiagnosticText = diagnosticText
            showStatus(diagnosticText)
            findViewById<Button>(R.id.copyExportButton).isEnabled = true
        }
    }

    private suspend inline fun <reified T : Record> readType(
        label: String,
        granted: Set<String>,
    ): TypeResult {
        val readPermission = HealthPermission.getReadPermission(T::class)
        if (readPermission !in granted) {
            return TypeResult(label, TypeState.PERMISSION_DENIED)
        }

        val pagination = try {
            accumulatePages<T>(MAX_PAGES_PER_TYPE) { pageToken ->
                val request = ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = TimeRangeFilter.between(Instant.EPOCH, Instant.now()),
                    pageToken = pageToken,
                )
                val response = healthConnectClient.readRecords(request)
                PageFetchResult(response.records, response.pageToken)
            }
        } catch (e: SecurityException) {
            return TypeResult(label, TypeState.PERMISSION_DENIED, errorMessage = e.message)
        } catch (e: Exception) {
            return TypeResult(
                label = label,
                state = TypeState.READ_ERROR,
                truncated = true,
                truncationReason = e.message ?: "read error",
                errorMessage = e.message,
            )
        }

        return if (pagination.records.isEmpty()) {
            TypeResult(
                label = label,
                state = TypeState.EMPTY,
                pagesRead = pagination.pagesRead,
                truncated = pagination.truncated,
                truncationReason = pagination.truncationReason,
            )
        } else {
            val times = pagination.records.map { recordTime(it) }
            val sources = pagination.records.map { it.metadata.dataOrigin.packageName }.toSet()
            TypeResult(
                label = label,
                state = TypeState.POPULATED,
                count = pagination.records.size,
                pagesRead = pagination.pagesRead,
                earliest = times.min(),
                latest = times.max(),
                sourcePackages = sources,
                truncated = pagination.truncated,
                truncationReason = pagination.truncationReason,
            )
        }
    }

    // Records don't share a common "time" property across types (sessions have start/end,
    // instantaneous records have `time`) — normalise to a single Instant per record for
    // earliest/latest reporting.
    private fun recordTime(record: Record): Instant = when (record) {
        is SleepSessionRecord -> record.startTime
        is StepsRecord -> record.startTime
        is HeartRateRecord -> record.startTime
        is NutritionRecord -> record.startTime
        is WeightRecord -> record.time
        is BodyFatRecord -> record.time
        else -> Instant.now()
    }

    private fun buildDiagnosticText(results: List<TypeResult>): String {
        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        val sb = StringBuilder()
        sb.appendLine("Fusion Health — Health Connect Diagnostic (WP1/PR2)")
        sb.appendLine()

        sb.appendLine("== Per record type ==")
        for (r in results) {
            sb.appendLine("- ${r.label}: ${r.state}")
            when (r.state) {
                TypeState.POPULATED -> {
                    sb.appendLine("    count=${r.count}")
                    sb.appendLine("    pages_read=${r.pagesRead}")
                    sb.appendLine("    earliest=${r.earliest?.let { dateFmt.format(it) }}")
                    sb.appendLine("    latest=${r.latest?.let { dateFmt.format(it) }}")
                    sb.appendLine("    source_apps=${r.sourcePackages.joinToString(", ")}")
                    sb.appendLine("    truncated=${r.truncated}${r.truncationReason?.let { " (reason=$it)" } ?: ""}")
                }
                TypeState.READ_ERROR -> {
                    sb.appendLine("    error=${r.errorMessage ?: "unknown"}")
                    sb.appendLine("    pages_read=${r.pagesRead}, partial_count=${r.count}")
                    sb.appendLine("    truncated=${r.truncated}${r.truncationReason?.let { " (reason=$it)" } ?: ""}")
                }
                TypeState.EMPTY, TypeState.PERMISSION_DENIED -> Unit
            }
        }

        sb.appendLine()
        sb.appendLine("== Source app classification ==")
        val allSourcePackages = results.flatMap { it.sourcePackages }.toSet()
        val anyReadError = results.any { it.state == TypeState.READ_ERROR }
        val anyEmpty = results.any { it.state == TypeState.EMPTY }
        val allPermissionDenied = results.all { it.state == TypeState.PERMISSION_DENIED }

        listOf(
            "Samsung Health" to SourceApps.SAMSUNG_HEALTH,
            "MyFitnessPal" to SourceApps.MYFITNESSPAL,
            "Withings" to SourceApps.WITHINGS,
        ).forEach { (name, pkg) ->
            val state = when {
                pkg in allSourcePackages -> AppState.POPULATED
                allPermissionDenied -> AppState.PERMISSION_DENIED
                anyReadError && !anyEmpty -> AppState.READ_ERROR
                else -> AppState.EMPTY
            }
            sb.appendLine("- $name ($pkg): $state")
        }

        sb.appendLine()
        sb.appendLine(
            "Package-name matches above are verified against Warwick's real-device Health " +
                "Connect source-app list (PR2 second device test, 2026-07-13)."
        )

        val secondaryOrigins = allSourcePackages.filter {
            it !in setOf(SourceApps.SAMSUNG_HEALTH, SourceApps.MYFITNESSPAL, SourceApps.WITHINGS)
        }
        if (secondaryOrigins.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("== Additional observed origins (not classified above) ==")
            secondaryOrigins.forEach { pkg ->
                sb.appendLine("- $pkg")
            }
            sb.appendLine(
                "Observed as a data-origin package alongside the three classified source apps " +
                    "(e.g. com.android.healthconnect.* — Health Connect's own on-device " +
                    "aggregation/phone origin). Reported here for visibility only; source-authority " +
                    "analysis (which origin is canonical for a given record) is deferred to a later " +
                    "work package, not decided in PR2."
            )
        }

        sb.appendLine()
        sb.appendLine("No health data is stored, uploaded, or retained beyond this screen.")
        return sb.toString()
    }

    private fun onCopyExportClicked() {
        if (lastDiagnosticText.isBlank()) {
            Toast.makeText(this, "Run the diagnostic first.", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Fusion Health diagnostic", lastDiagnosticText))
        Toast.makeText(this, "Copied to clipboard.", Toast.LENGTH_SHORT).show()

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, lastDiagnosticText)
            putExtra(Intent.EXTRA_SUBJECT, "Fusion Health — Health Connect Diagnostic")
        }
        startActivity(Intent.createChooser(shareIntent, "Export diagnostic"))
    }

    private fun showStatus(text: String) {
        findViewById<TextView>(R.id.statusText).text = text
    }
}
