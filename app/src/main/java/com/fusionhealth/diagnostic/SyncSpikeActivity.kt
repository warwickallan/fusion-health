package com.fusionhealth.diagnostic

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.Change
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * BUILD-005/WP2/PR4b — incremental-sync capability spike. A standalone, separately-launched
 * diagnostic Activity (own LAUNCHER entry) so WP1/PR2's device-proven [MainActivity] flow is
 * never touched by this spike. Read-only, in-memory only: no persistence, no network, no
 * Withings OAuth, no Samsung SDK. Everything here dies with the process.
 *
 * Drives Health Connect's Changes API directly:
 *   - "Get changes token" calls [HealthConnectClient.getChangesToken] once and keeps the result
 *     only in memory.
 *   - "Check for changes" calls [HealthConnectClient.getChanges] with that token, classifies the
 *     upsertion IDs it sees against IDs already seen this session (see [SyncSpikeAnalysis]), and
 *     reports counts only -- never raw health values.
 *
 * Intended usage for the device test this spike exists to drive: tap "Get changes token", then
 * modify or delete a record in a source app (Samsung Health / MyFitnessPal / manual entry), then
 * tap "Check for changes" and read what comes back.
 */
class SyncSpikeActivity : AppCompatActivity() {

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

    // In-memory only -- never written to disk, never survives an app restart.
    private var currentToken: String? = null
    private var pullCount = 0
    private val seenUpsertionIds = mutableSetOf<String>()
    private val log = StringBuilder()

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (permissions.all { it in granted }) {
                appendLog("Permissions granted. Ready to get a changes token.")
            } else {
                appendLog("Not all requested permissions were granted; changes may be incomplete.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_spike)

        findViewById<Button>(R.id.getTokenButton).setOnClickListener { onGetTokenClicked() }
        findViewById<Button>(R.id.checkChangesButton).setOnClickListener { onCheckChangesClicked() }

        showLog(
            "Fusion Health — Incremental Sync Spike (WP2/PR4b)\n\n" +
                "Diagnostic spike only. No data is stored, uploaded, or retained beyond this " +
                "screen; everything here is lost when the app is closed.\n\n" +
                "1. Tap \"Get changes token\".\n" +
                "2. Modify or delete a health record in a source app.\n" +
                "3. Tap \"Check for changes\" and read the result below.\n"
        )
    }

    private fun onGetTokenClicked() {
        val availability = HealthConnectClient.getSdkStatus(this)
        if (availability != HealthConnectClient.SDK_AVAILABLE) {
            appendLog("Health Connect is not available on this device (SDK status $availability).")
            return
        }

        healthConnectClient = HealthConnectClient.getOrCreate(this)
        requestPermissions.launch(permissions)

        lifecycleScope.launch {
            try {
                val recordTypes = setOf(
                    StepsRecord::class,
                    SleepSessionRecord::class,
                    HeartRateRecord::class,
                    NutritionRecord::class,
                    WeightRecord::class,
                    BodyFatRecord::class,
                )
                val token = healthConnectClient.getChangesToken(
                    ChangesTokenRequest(recordTypes = recordTypes)
                )
                currentToken = token
                pullCount = 0
                seenUpsertionIds.clear()
                appendLog("Got a changes token (session-only, never persisted). Ready to check for changes.")
            } catch (e: Exception) {
                appendLog("Failed to get a changes token: ${e.message}")
            }
        }
    }

    private fun onCheckChangesClicked() {
        val token = currentToken
        if (token == null) {
            appendLog("No changes token yet -- tap \"Get changes token\" first.")
            return
        }

        lifecycleScope.launch {
            try {
                val response = healthConnectClient.getChanges(token)
                pullCount++

                val upsertionIds = mutableListOf<String>()
                var deletionCount = 0
                for (change: Change in response.changes) {
                    when (change) {
                        is UpsertionChange -> upsertionIds += change.record.metadata.id
                        is DeletionChange -> deletionCount++
                    }
                }

                val classification = classifyUpsertionIds(upsertionIds, seenUpsertionIds)
                seenUpsertionIds += upsertionIds

                val summary = ChangesPullSummary(
                    upsertionCount = upsertionIds.size,
                    deletionCount = deletionCount,
                    newRecordIdCount = classification.newCount,
                    updatedRecordIdCount = classification.updatedCount,
                    hasMore = response.hasMore,
                    changesTokenExpired = response.changesTokenExpired,
                )

                appendLog(formatChangesPullReport(pullCount, summary))

                currentToken = if (response.changesTokenExpired) null else response.nextChangesToken
            } catch (e: Exception) {
                appendLog("Failed to check for changes: ${e.message}")
            }
        }
    }

    private fun appendLog(line: String) {
        log.appendLine(line)
        log.appendLine()
        showLog(log.toString())
    }

    private fun showLog(text: String) {
        findViewById<TextView>(R.id.syncSpikeStatusText).text = text
    }
}
