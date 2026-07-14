package com.fusionhealth.diagnostic

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.WeightRecord
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * BUILD-005 — Canonical Latest Weight Preview. A standalone, separately-launched Activity (own
 * LAUNCHER entry) so WP1/PR2's device-proven [MainActivity] flow is untouched. Reads WeightRecord
 * from Health Connect, selects the latest reading, maps it into the minimal canonical structure
 * (see [CanonicalWeight]) and displays it -- distinguishing OBSERVED facts from INFERRED
 * provenance.
 *
 * MVP scope only, all in memory: no database, no cloud, no upload, no Samsung SDK, no Withings
 * OAuth, no deletion/correction engine, no generic multi-domain framework.
 */
class CanonicalWeightActivity : AppCompatActivity() {

    private val permissions: Set<String> by lazy {
        setOf(HealthPermission.getReadPermission(WeightRecord::class))
    }

    private lateinit var healthConnectClient: HealthConnectClient

    private val requestPermissions =
        registerForActivityResult(
            androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            if (permissions.all { it in granted }) {
                appendStatus("Permission granted. Loading latest weight...")
                loadLatestWeight()
            } else {
                appendStatus("WeightRecord read permission was not granted; cannot show a reading.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_canonical_weight)

        findViewById<Button>(R.id.refreshWeightButton).setOnClickListener { onRefreshClicked() }

        showStatus(
            "Fusion Health — Canonical Latest Weight Preview\n\n" +
                "Shows your latest weight from Health Connect as a normalised canonical record. " +
                "Nothing is stored or uploaded; everything is read live and held only in memory.\n\n" +
                "Stand on your Withings scale (or record a weight in a source app), then tap " +
                "\"Refresh latest weight\".\n"
        )
    }

    private fun onRefreshClicked() {
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
                    loadLatestWeight()
                } else {
                    appendStatus("Requesting WeightRecord read permission...")
                    requestPermissions.launch(permissions)
                }
            } catch (e: SecurityException) {
                appendStatus("SecurityException reading granted permissions: ${e.message}")
            } catch (e: Exception) {
                appendStatus("Failed to read granted permissions: ${e.message}")
            }
        }
    }

    private fun loadLatestWeight() {
        lifecycleScope.launch {
            try {
                // Read every WeightRecord page, then pick the latest via the pure selector. Reuses
                // the exact paginated read path proven in WP1/PR2 (null-not-empty first token).
                val pagination = accumulatePages<WeightRecord>(MAX_PAGES) { pageToken ->
                    val request = buildReadRecordsRequest(WeightRecord::class, pageToken)
                    val response = healthConnectClient.readRecords(request)
                    PageFetchResult(response.records, response.pageToken)
                }

                val samples = pagination.records.map {
                    ObservedWeightSample(
                        weightKilograms = it.weight.inKilograms,
                        measurementTime = it.time,
                        observedWriterPackage = it.metadata.dataOrigin.packageName,
                    )
                }

                val latest = selectLatestWeightSample(samples)
                if (latest == null) {
                    showStatus("No weight records found in Health Connect yet.")
                    return@launch
                }

                val canonical = toCanonicalWeight(latest, ingestionTime = Instant.now())
                showStatus(formatCanonicalWeight(canonical))
            } catch (e: SecurityException) {
                appendStatus("Reading weight refused with SecurityException: ${e.message}")
            } catch (e: Exception) {
                appendStatus("Failed to read latest weight: ${e.message}")
            }
        }
    }

    private fun appendStatus(line: String) {
        val current = findViewById<TextView>(R.id.canonicalWeightStatusText).text
        showStatus("$current\n$line")
    }

    private fun showStatus(text: String) {
        findViewById<TextView>(R.id.canonicalWeightStatusText).text = text
    }

    private companion object {
        // Defensive page bound, mirroring MainActivity's MAX_PAGES_PER_TYPE.
        const val MAX_PAGES = 200
    }
}
