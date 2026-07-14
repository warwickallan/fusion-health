package com.fusionhealth.diagnostic

/**
 * BUILD-005/WP2/PR4b — incremental-sync capability spike. Pure, Android-independent logic for
 * interpreting Health Connect's Changes API (`getChangesToken` / `getChanges`), kept separate
 * from [SyncSpikeActivity] so the classification and report-formatting logic is directly
 * unit-testable without a real `HealthConnectClient` or device.
 *
 * This exists to answer three questions from the WP2 design doc (§5/§8/§9), and nothing else:
 *   1. Does an official Health Connect change-token/delta mechanism exist and work at all?
 *   2. Does it distinguish record updates (upsertions of an already-seen ID) from genuinely new
 *      records, and from deletions?
 *   3. What does a real device actually return -- this file only classifies and formats what
 *      [SyncSpikeActivity] observes; it does not assume an answer in advance.
 */

/** One pull of `getChanges(token)`, reduced to counts -- no raw health values anywhere. */
internal data class ChangesPullSummary(
    val upsertionCount: Int,
    val deletionCount: Int,
    val newRecordIdCount: Int,
    val updatedRecordIdCount: Int,
    val hasMore: Boolean,
    val changesTokenExpired: Boolean,
)

/** Result of classifying one batch of observed upsertion IDs against a prior session's IDs. */
internal data class UpsertionClassification(val newCount: Int, val updatedCount: Int)

/**
 * Classifies a batch of observed upsertion record IDs against IDs already seen earlier in the
 * same in-memory spike session (never persisted across app restarts -- dies with the process,
 * consistent with the no-persistent-storage guardrail). A "new" ID has not been seen this
 * session; an "updated" ID has -- this is how the spike distinguishes an update from a fresh
 * insert without needing Health Connect to label it explicitly.
 */
internal fun classifyUpsertionIds(
    observedIds: List<String>,
    previouslySeenIds: Set<String>,
): UpsertionClassification {
    var newCount = 0
    var updatedCount = 0
    for (id in observedIds) {
        if (id in previouslySeenIds) updatedCount++ else newCount++
    }
    return UpsertionClassification(newCount, updatedCount)
}

/** Formats one spike pull's results as plain diagnostic text -- metadata/counts only. */
internal fun formatChangesPullReport(pullNumber: Int, summary: ChangesPullSummary): String {
    val sb = StringBuilder()
    sb.appendLine("== Changes pull #$pullNumber ==")
    sb.appendLine("upsertions=${summary.upsertionCount} (new=${summary.newRecordIdCount}, updated=${summary.updatedRecordIdCount})")
    sb.appendLine("deletions=${summary.deletionCount}")
    sb.appendLine("has_more=${summary.hasMore}")
    sb.appendLine("changes_token_expired=${summary.changesTokenExpired}")
    if (summary.changesTokenExpired) {
        sb.appendLine(
            "Token expired -- per the API contract a fresh token must be obtained; this spike " +
                "does that automatically on the next \"Get changes token\" tap, but the prior " +
                "token's continuity is broken at this point."
        )
    }
    return sb.toString()
}
