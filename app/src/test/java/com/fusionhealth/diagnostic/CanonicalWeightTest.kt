package com.fusionhealth.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Coverage for [CanonicalWeight]'s pure logic: selecting the latest observed weight sample,
 * inferring a producer from the observed writer package, mapping a sample into the canonical
 * structure, and formatting it with OBSERVED vs INFERRED clearly separated. No Health Connect SDK
 * or device involved.
 */
class CanonicalWeightTest {

    private fun sample(kg: Double, at: String, pkg: String) =
        ObservedWeightSample(kg, Instant.parse(at), pkg)

    @Test
    fun `selects the sample with the latest measurement time`() {
        val latest = selectLatestWeightSample(
            listOf(
                sample(80.0, "2026-07-10T08:00:00Z", "com.withings.wiscale2"),
                sample(99.9, "2026-07-14T08:00:00Z", "com.withings.wiscale2"),
                sample(81.0, "2026-07-12T08:00:00Z", "com.withings.wiscale2"),
            )
        )

        assertEquals(99.9, latest!!.weightKilograms, 0.0)
        assertEquals(Instant.parse("2026-07-14T08:00:00Z"), latest.measurementTime)
    }

    @Test
    fun `an empty sample list yields no latest sample`() {
        assertNull(selectLatestWeightSample(emptyList()))
    }

    @Test
    fun `a recognised writer package infers a high-confidence producer`() {
        assertEquals(InferredProducer("Withings", "HIGH"), inferProducer("com.withings.wiscale2"))
        assertEquals(InferredProducer("Samsung Health", "HIGH"), inferProducer("com.sec.android.app.shealth"))
        assertEquals(InferredProducer("MyFitnessPal", "HIGH"), inferProducer("com.myfitnesspal.android"))
    }

    @Test
    fun `an unrecognised writer package infers an unknown producer`() {
        assertEquals(InferredProducer("Unknown", "UNKNOWN"), inferProducer("com.example.something"))
    }

    @Test
    fun `mapping preserves observed facts and stamps the ingestion time`() {
        val s = sample(99.9, "2026-07-14T08:00:00Z", "com.withings.wiscale2")
        val ingest = Instant.parse("2026-07-14T09:30:00Z")

        val canonical = toCanonicalWeight(s, ingest)

        assertEquals(99.9, canonical.weightKilograms, 0.0)
        assertEquals("kg", canonical.unit)
        assertEquals(Instant.parse("2026-07-14T08:00:00Z"), canonical.measurementTime)
        assertEquals(ingest, canonical.ingestionTime)
        // Observed source system is the raw writer package, never the friendly inferred label.
        assertEquals("com.withings.wiscale2", canonical.observedWriterPackage)
        assertEquals("com.withings.wiscale2", canonical.observedSourceSystem)
        assertEquals(InferredProducer("Withings", "HIGH"), canonical.inferredProducer)
    }

    @Test
    fun `formatting separates observed facts from inferred provenance`() {
        val s = sample(99.9, "2026-07-14T08:00:00Z", "com.withings.wiscale2")
        val canonical = toCanonicalWeight(s, Instant.parse("2026-07-14T09:30:00Z"))

        val text = formatCanonicalWeight(canonical, zone = ZoneId.of("UTC"))

        assertTrue(text.contains("weight: 99.9 kg"))
        assertTrue(text.contains("measured_at: 2026-07-14 08:00:00"))
        assertTrue(text.contains("ingested_at: 2026-07-14 09:30:00"))
        assertTrue(text.contains("observed facts"))
        assertTrue(text.contains("observed_writer_package: com.withings.wiscale2"))
        assertTrue(text.contains("inferred (NOT an observed fact)"))
        assertTrue(text.contains("inferred_producer: Withings (confidence=HIGH)"))
    }
}
