package com.fusionhealth.diagnostic

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
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
 * Deliberately narrowed to a SINGLE low-noise record type ([WeightRecord]) for the controlled
 * test: steps and heart-rate change automatically in the background and would contaminate the
 * counts, making it impossible to attribute an observed change to Warwick's controlled action.
 * Weight is manually create/edit/delete-able in common source apps and does not tick on its own.
 * The broader six-domain production question is deliberately kept out of this experiment.
 *
 * Drives Health Connect's Changes API directly:
 *   - "Get changes token" waits for permission confirmation first (no race), then calls
 *     [HealthConnectClient.getChangesToken] once and keeps the result only in memory.
 *   - "Check for changes" calls [HealthConnectClient.getChanges] and DRAINS every page to
 *     `hasMore=false` before returning, so one experimental pull is complete before the next
 *     controlled action. It classifies upsertion IDs as first-seen / repeat-seen this session
 *     (see [SyncSpikeAnalysis]) -- evidence-accurate observation, never an API "insert vs update"
 *     claim -- and reports counts only, never raw health values.
 */
class SyncSpikeActivity : AppCompatActivity() {

    // Single low-noise record type -- see the class KDoc for why this is not the full WP1 six.
    private val permissions: Set<String> by lazy {
        setOf(HealthPermission.getReadPermission(WeightRecord::class))
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
                appendLog("Permission granted. Acquiring a changes token...")
                acquireChangesToken()
            } else {
                appendLog("Changes token NOT requested: WeightRecord read permission was not granted.")
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
                "Scoped to WeightRecord ONLY (a low-noise, manually-controllable type) so " +
                "background step/heart-rate traffic cannot contaminate the controlled test.\n\n" +
                "1. Tap \"Get changes token\".\n" +
                "2. Create / edit / delete a synthetic weight record in your source app.\n" +
                "3. Tap \"Check for changes\" (it drains all pages) and read the result below.\n"
        )
    }

    private fun onGetTokenClicked() {
        val availability = HealthConnectClient.getSdkStatus(this)
        if (availability != HealthConnectClient.SDK_AVAILABLE) {
            appendLog("Health Connect is not available on this device (SDK status $availability).")
            return
        }

        healthConnectClient = HealthConnectClient.getOrCreate(this)

        // No race: check granted permissions first; only launch the permission UI if needed, and
        // only acquire the token once permission is confirmed (here or in the result callback).
        lifecycleScope.launch {
            try {
                val granted = healthConnectClient.permissionController.getGrantedPermissions()
                if (permissions.all { it in granted }) {
                    acquireChangesToken()
                } else {
                    appendLog("Requesting WeightRecord read permission...")
                    requestPermissions.launch(permissions)
                }
            } catch (e: SecurityException) {
                appendLog("SecurityException reading granted permissions: ${e.message}")
            } catch (e: Exception) {
                appendLog("Failed to read granted permissions: ${e.message}")
            }
        }
    }

    /** Acquires a changes token for WeightRecord only. Called only after permission is confirmed. */
    private fun acquireChangesToken() {
        lifecycleScope.launch {
            try {
                val token = healthConnectClient.getChangesToken(
                    ChangesTokenRequest(recordTypes = setOf(WeightRecord::class))
                )
                currentToken = token
                pullCount = 0
                seenUpsertionIds.clear()
                appendLog("Got a changes token (session-only, never persisted). Ready to check for changes.")
            } catch (e: SecurityException) {
                appendLog("getChangesToken refused with SecurityException (permission incomplete): ${e.message}")
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
                // Drain every page for this pull before returning, so one experimental pull is
                // complete before the next controlled action. The fetch lambda is the only place
                // that touches the real SDK; all drain/classify logic lives in SyncSpikeAnalysis.
                pullCount++
                val summary = drainChangesFromClient(token)
                appendLog(formatChangesPullReport(pullCount, summary))
                currentToken = summary.nextChangesToken
            } catch (e: SecurityException) {
                appendLog("getChanges refused with SecurityException (permission incomplete): ${e.message}")
            } catch (e: Exception) {
                appendLog("Failed to check for changes: ${e.message}")
            }
        }
    }

    /**
     * Bridges the pure [drainChanges] logic to the real client: the only SDK touch-point is
     * fetching one page and reducing it to a [ChangesPage] (IDs + counts only). All accumulation
     * and the has-more / expiry / repeat-token guards live in [drainChanges].
     */
    private suspend fun drainChangesFromClient(startToken: String): ChangesPullSummary =
        drainChanges(startToken, seenUpsertionIds) { token ->
            val response = healthConnectClient.getChanges(token)
            val upsertionIds = mutableListOf<String>()
            var pageDeletions = 0
            for (change in response.changes) {
                when (change) {
                    is UpsertionChange -> upsertionIds += change.record.metadata.id
                    is DeletionChange -> pageDeletions++
                }
            }
            ChangesPage(
                upsertionIds = upsertionIds,
                deletionCount = pageDeletions,
                nextChangesToken = response.nextChangesToken,
                hasMore = response.hasMore,
                changesTokenExpired = response.changesTokenExpired,
            )
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
