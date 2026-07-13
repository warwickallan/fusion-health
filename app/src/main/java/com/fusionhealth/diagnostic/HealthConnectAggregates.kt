package com.fusionhealth.diagnostic

import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.ZoneId

/**
 * Result of asking Health Connect for the actual step total for the current local day, via the
 * aggregate API (`StepsRecord.COUNT_TOTAL`). This is a different number from the diagnostic's
 * `record_count`: `record_count` counts StepsRecord objects (one record can span many steps),
 * while this is Health Connect's own summed step count.
 */
internal data class StepsTotalToday(
    val total: Long? = null,
    val origins: Set<String> = emptySet(),
    val permissionDenied: Boolean = false,
    val error: String? = null,
)

/** Start of the current local day (local midnight) as an [Instant]. */
internal fun localDayStart(
    zone: ZoneId = ZoneId.systemDefault(),
    now: Instant = Instant.now(),
): Instant = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

/**
 * Builds the Health Connect aggregate request for the actual step total between [start] and
 * [end] using `StepsRecord.COUNT_TOTAL`. Constructed against the real SDK class so a unit test
 * exercises the same object the production path sends — raw StepsRecord objects are deliberately
 * NOT summed by this app, because overlapping origins (Samsung Health and Health Connect's own
 * phone origin) could double-count steps; the aggregate API is Health Connect's own answer.
 */
internal fun buildStepsTotalAggregateRequest(
    start: Instant,
    end: Instant,
): AggregateRequest = AggregateRequest(
    metrics = setOf(StepsRecord.COUNT_TOTAL),
    timeRangeFilter = TimeRangeFilter.between(start, end),
)

/**
 * Formats the "actual steps today" diagnostic section, keeping the record-count and step-total
 * numbers explicitly distinct so one can never be mistaken for the other. Pure function so the
 * wording contract is unit-testable without an Android runtime.
 */
internal fun formatStepsTotalSection(recordCount: Int, stepsTotal: StepsTotalToday): String {
    val sb = StringBuilder()
    sb.appendLine("== Steps total — today, local (Health Connect aggregate) ==")
    when {
        stepsTotal.permissionDenied ->
            sb.appendLine("aggregate_step_total=PERMISSION_DENIED")
        stepsTotal.error != null ->
            sb.appendLine("aggregate_step_total=ERROR (${stepsTotal.error})")
        else -> {
            sb.appendLine("aggregate_step_total=${stepsTotal.total ?: 0}")
            sb.appendLine("aggregate_origins=${stepsTotal.origins.joinToString(", ").ifEmpty { "(none)" }}")
        }
    }
    sb.appendLine(
        "This total comes from Health Connect's aggregate API (StepsRecord.COUNT_TOTAL, local " +
            "midnight to now). It is NOT the same number as record_count=$recordCount above: " +
            "record_count counts StepsRecord objects across all time, each of which can span " +
            "many steps."
    )
    sb.appendLine(
        "Source authority: UNRESOLVED. Raw step records were observed from overlapping origins " +
            "(Samsung Health and Health Connect's own phone origin), so this app deliberately " +
            "does not sum raw records — that could double-count steps. Health Connect's " +
            "aggregate is expected to deduplicate overlapping origins via its own data-priority " +
            "rules, but canonical source handling is not proven in PR2; the aggregate total, " +
            "the observed origins, and this unresolved status are reported as-is for later " +
            "source-authority analysis."
    )
    return sb.toString()
}
