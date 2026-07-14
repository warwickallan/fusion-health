package com.fusionhealth.diagnostic

import java.time.Instant

/**
 * BUILD-005/WP2 — Samsung Health Capability Sweep. Pure, Android/SDK-independent logic for the
 * inventory experiment: status classification, source-package filtering, compact value summaries,
 * and report formatting. Kept separate from [SamsungSweepActivity] so this logic is directly
 * unit-testable without a real `HealthConnectClient` or device.
 *
 * This is an inventory experiment, not a production pipeline: it discovers which Samsung
 * Health-originated data types Fusion Health can already read through Health Connect. Read-only,
 * in-memory only. No raw samples are dumped -- only counts, timestamps, observed writer packages
 * and compact latest-value summaries.
 */

/** Samsung Health's Health Connect writer package (verified on Warwick's device, PR2). */
internal const val SAMSUNG_HEALTH_PACKAGE = "com.sec.android.app.shealth"

internal enum class SweepStatus {
    POPULATED,
    EMPTY,
    PERMISSION_DENIED,
    READ_ERROR,
    SDK_UNAVAILABLE,
}

/** One record type's sweep result -- Samsung-filtered, counts/metadata only. */
internal data class TypeSweepResult(
    val label: String,
    val status: SweepStatus,
    val count: Int = 0,
    val pagesRead: Int = 0,
    val earliest: Instant? = null,
    val latest: Instant? = null,
    val writerPackages: Set<String> = emptySet(),
    val samsungFound: Boolean = false,
    val valueSummary: String? = null,
)

/** True when a Health Connect writer package is Samsung Health's. */
internal fun isSamsungPackage(packageName: String): Boolean = packageName == SAMSUNG_HEALTH_PACKAGE

/** Retains only items whose observed writer package is Samsung Health. */
internal fun <T> retainSamsung(items: List<T>, writerPackage: (T) -> String): List<T> =
    items.filter { isSamsungPackage(writerPackage(it)) }

/**
 * Derives the status for a type that was successfully read (permission granted, no read error):
 * POPULATED if any Samsung records were found this sweep, otherwise EMPTY. PERMISSION_DENIED,
 * READ_ERROR and SDK_UNAVAILABLE are set directly by the caller, not here.
 */
internal fun populatedOrEmpty(samsungCount: Int): SweepStatus =
    if (samsungCount > 0) SweepStatus.POPULATED else SweepStatus.EMPTY

/** Formats a duration in seconds compactly: "2h 05m", "45m", "30s". */
internal fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds < 0) return "0s"
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes.toString().padStart(2, '0')}m"
        minutes > 0 -> "${minutes}m ${seconds.toString().padStart(2, '0')}s"
        else -> "${seconds}s"
    }
}

/** Formats the full sweep inventory as plain text -- no raw samples, summaries only. */
internal fun formatSweepReport(results: List<TypeSweepResult>, generatedAtLabel: String): String {
    val sb = StringBuilder()
    sb.appendLine("== Samsung Health Capability Sweep ==")
    sb.appendLine("source filter: $SAMSUNG_HEALTH_PACKAGE (Samsung Health only)")
    sb.appendLine("generated_at: $generatedAtLabel")
    val withData = results.count { it.samsungFound }
    sb.appendLine("types with Samsung data: $withData / ${results.size}")
    sb.appendLine()

    for (r in results) {
        sb.appendLine("-- ${r.label} --")
        sb.appendLine("status=${r.status}")
        sb.appendLine("samsung_found=${r.samsungFound}")
        sb.appendLine("count=${r.count}, pages_read=${r.pagesRead}")
        sb.appendLine("earliest=${r.earliest ?: "-"}")
        sb.appendLine("latest=${r.latest ?: "-"}")
        sb.appendLine(
            "observed_writer_packages=${if (r.writerPackages.isEmpty()) "-" else r.writerPackages.sorted().joinToString(", ")}"
        )
        if (r.valueSummary != null) {
            sb.appendLine("summary: ${r.valueSummary}")
        }
        sb.appendLine()
    }
    return sb.toString()
}
