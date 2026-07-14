package com.fusionhealth.diagnostic

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * BUILD-005 — Canonical Latest Weight Preview. Pure, Android/SDK-independent logic for selecting
 * the latest observed weight sample and mapping it into a minimal canonical structure for display.
 * Kept separate from [CanonicalWeightActivity] so selection, provenance inference and formatting
 * are directly unit-testable without a real `HealthConnectClient` or device.
 *
 * MVP scope only: read WeightRecord, pick the latest, show it. No storage, no cloud, no upload,
 * no deletion/correction engine, no generic multi-domain framework.
 *
 * Provenance discipline (per the merged WP2 canonical contract): OBSERVED facts -- the writer
 * package and source system Health Connect actually reports, the measurement time, the value and
 * unit -- are kept strictly distinct from INFERRED provenance (a human-friendly producer guess
 * plus a confidence level). The UI must never present an inference as an observed fact.
 */

/** One observed weight sample as read from Health Connect -- observed facts only. */
internal data class ObservedWeightSample(
    val weightKilograms: Double,
    val measurementTime: Instant,
    val observedWriterPackage: String,
)

/** An inferred (not observed) guess at the human producer behind a writer package. */
internal data class InferredProducer(val label: String, val confidence: String)

/** The minimal canonical weight reading shown in the preview. */
internal data class CanonicalWeightReading(
    val weightKilograms: Double,
    val unit: String,
    val measurementTime: Instant,
    val ingestionTime: Instant,
    val observedWriterPackage: String,
    val observedSourceSystem: String,
    val inferredProducer: InferredProducer,
)

/** Selects the latest sample by measurement time; null if there are none. */
internal fun selectLatestWeightSample(samples: List<ObservedWeightSample>): ObservedWeightSample? =
    samples.maxByOrNull { it.measurementTime }

/**
 * Infers a human-friendly producer from the observed writer package. This is an INFERENCE, never
 * an observed fact: the three source-app packages verified on Warwick's device (PR2) yield a
 * HIGH-confidence label, Health Connect's own package a MEDIUM one, everything else UNKNOWN.
 */
internal fun inferProducer(observedWriterPackage: String): InferredProducer =
    when (observedWriterPackage) {
        "com.withings.wiscale2" -> InferredProducer("Withings", "HIGH")
        "com.sec.android.app.shealth" -> InferredProducer("Samsung Health", "HIGH")
        "com.myfitnesspal.android" -> InferredProducer("MyFitnessPal", "HIGH")
        "com.google.android.apps.healthdata" -> InferredProducer("Health Connect", "MEDIUM")
        else -> InferredProducer("Unknown", "UNKNOWN")
    }

/** Maps one observed sample into the canonical structure, stamping the ingestion time. */
internal fun toCanonicalWeight(
    sample: ObservedWeightSample,
    ingestionTime: Instant,
): CanonicalWeightReading = CanonicalWeightReading(
    weightKilograms = sample.weightKilograms,
    unit = "kg",
    measurementTime = sample.measurementTime,
    ingestionTime = ingestionTime,
    observedWriterPackage = sample.observedWriterPackage,
    // What Health Connect reports as the writing app IS the observed source system identity; the
    // friendly name is a separate inference below, never conflated with this observed string.
    observedSourceSystem = sample.observedWriterPackage,
    inferredProducer = inferProducer(sample.observedWriterPackage),
)

/** Formats the canonical reading for display, labelling OBSERVED vs INFERRED explicitly. */
internal fun formatCanonicalWeight(
    reading: CanonicalWeightReading,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
    val sb = StringBuilder()
    sb.appendLine("== Canonical Latest Weight ==")
    sb.appendLine("weight: ${reading.weightKilograms} ${reading.unit}")
    sb.appendLine("measured_at: ${fmt.format(reading.measurementTime)} ($zone)")
    sb.appendLine("ingested_at: ${fmt.format(reading.ingestionTime)} ($zone)")
    sb.appendLine()
    sb.appendLine("-- observed facts (from Health Connect) --")
    sb.appendLine("observed_writer_package: ${reading.observedWriterPackage}")
    sb.appendLine("observed_source_system: ${reading.observedSourceSystem}")
    sb.appendLine()
    sb.appendLine("-- inferred (NOT an observed fact) --")
    sb.appendLine(
        "inferred_producer: ${reading.inferredProducer.label} " +
            "(confidence=${reading.inferredProducer.confidence})"
    )
    return sb.toString()
}
