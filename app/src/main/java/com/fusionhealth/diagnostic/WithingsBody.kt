package com.fusionhealth.diagnostic

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * BUILD-005/WP2 — Unified snapshot: Withings body composition. Pure, Android-independent logic for
 * classifying, pairing and formatting Withings-origin body measurements read through Health
 * Connect. Kept separate from the Activity so it is directly unit-testable.
 *
 * Provenance discipline: values are shown per record type with explicit availability states.
 * Derived values (fat mass, BMI) are only computed from records close enough in time to represent
 * the same measurement event, and are always labelled "Calculated". Lean body mass is never called
 * "muscle mass" — Health Connect does not supply a muscle-mass value.
 */

/** Withings' Health Connect writer package (verified on Warwick's device, PR2). */
internal const val WITHINGS_PACKAGE = "com.withings.wiscale2"

/** MyFitnessPal's Health Connect writer package (verified on Warwick's device, PR2). */
internal const val MFP_PACKAGE = "com.myfitnesspal.android"

/** Availability state of one queried source/record type. */
internal enum class SourceReadStatus { POPULATED, EMPTY, PERMISSION_DENIED, READ_ERROR, SDK_UNAVAILABLE }

/** Keeps only items whose observed writer package matches [pkg]. */
internal fun <T> retainByWriter(items: List<T>, pkg: String, writerPackage: (T) -> String): List<T> =
    items.filter { writerPackage(it) == pkg }

/** One numeric reading with its measurement time. */
internal data class MetricReading(val value: Double, val time: Instant)

/** Latest state of one body metric, ready for display. */
internal data class BodyMetricState(
    val label: String,
    val unit: String,
    val status: SourceReadStatus,
    val latest: MetricReading? = null,
    val previous: MetricReading? = null,
    val errorMessage: String? = null,
)

/**
 * Builds a [BodyMetricState] from a permission flag and the Withings-filtered readings (or a read
 * error). Permission denial is reported as PERMISSION_DENIED, never conflated with EMPTY.
 */
internal fun classifyBodyMetric(
    label: String,
    unit: String,
    permissionGranted: Boolean,
    readings: List<MetricReading>?,
    error: String? = null,
): BodyMetricState = when {
    !permissionGranted -> BodyMetricState(label, unit, SourceReadStatus.PERMISSION_DENIED)
    error != null -> BodyMetricState(label, unit, SourceReadStatus.READ_ERROR, errorMessage = error)
    readings.isNullOrEmpty() -> BodyMetricState(label, unit, SourceReadStatus.EMPTY)
    else -> {
        val sorted = readings.sortedByDescending { it.time }
        BodyMetricState(label, unit, SourceReadStatus.POPULATED, latest = sorted[0], previous = sorted.getOrNull(1))
    }
}

/**
 * Conservative pairing tolerance for derived values: two readings are treated as the same
 * measurement event only when their timestamps are within this window. Withings writes weight and
 * body-fat at the same instant for a scale measurement, so 5 minutes is deliberately generous for
 * genuine pairs while rejecting readings from different events.
 */
internal const val PAIRING_TOLERANCE_SECONDS = 300L

/** True when both readings exist and are close enough to be the same measurement event. */
internal fun pairedForCalculation(
    a: MetricReading?,
    b: MetricReading?,
    toleranceSeconds: Long = PAIRING_TOLERANCE_SECONDS,
): Boolean = a != null && b != null &&
    abs(Duration.between(a.time, b.time).seconds) <= toleranceSeconds

/** Calculated fat mass (kg) from safely paired weight (kg) and body-fat (%); null if unpaired. */
internal fun calculatedFatMassKg(weightKg: MetricReading?, bodyFatPct: MetricReading?): Double? =
    if (pairedForCalculation(weightKg, bodyFatPct)) weightKg!!.value * bodyFatPct!!.value / 100.0 else null

/** Calculated BMI from safely paired weight (kg) and height (m); null if unpaired or height<=0. */
internal fun calculatedBmi(weightKg: MetricReading?, heightM: MetricReading?): Double? =
    if (pairedForCalculation(weightKg, heightM) && heightM!!.value > 0.0) {
        weightKg!!.value / (heightM.value * heightM.value)
    } else null

/** Everything the Body Composition section needs. */
internal data class BodyCompositionData(
    val metrics: List<BodyMetricState>,
    val fatMassKg: Double?,
    val bmi: Double?,
)

private fun num(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

/** Formats the Withings Body Composition section — readable values, honest states. */
internal fun formatBodyComposition(
    data: BodyCompositionData?,
    timeFormatter: (Instant) -> String,
): String {
    val sb = StringBuilder()
    sb.appendLine("BODY COMPOSITION — Withings")
    if (data == null) {
        sb.appendLine("• Not available")
        return sb.toString()
    }
    for (m in data.metrics) {
        when (m.status) {
            SourceReadStatus.POPULATED -> {
                val l = m.latest!!
                sb.appendLine("• ${m.label}: ${num(l.value)} ${m.unit} (${timeFormatter(l.time)})")
            }
            SourceReadStatus.EMPTY -> sb.appendLine("• ${m.label}: Not available (no Withings data)")
            SourceReadStatus.PERMISSION_DENIED -> sb.appendLine("• ${m.label}: Permission not granted")
            SourceReadStatus.READ_ERROR -> sb.appendLine("• ${m.label}: Read error (${m.errorMessage ?: "unknown"})")
            SourceReadStatus.SDK_UNAVAILABLE -> sb.appendLine("• ${m.label}: Not supported by the current Health Connect SDK")
        }
    }
    if (data.fatMassKg != null) {
        sb.appendLine("• Fat mass: ${num(data.fatMassKg)} kg (Calculated from paired weight × body fat %)")
    } else {
        sb.appendLine("• Fat mass: Not calculated (no weight and body-fat readings from the same measurement)")
    }
    if (data.bmi != null) {
        sb.appendLine("• BMI: ${num(data.bmi)} (Calculated from paired weight and height)")
    } else {
        sb.appendLine("• BMI: Not calculated (no weight and height readings from the same measurement)")
    }
    return sb.toString()
}
