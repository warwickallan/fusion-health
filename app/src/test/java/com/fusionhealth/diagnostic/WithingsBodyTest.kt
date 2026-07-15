package com.fusionhealth.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Coverage for [WithingsBody]'s pure logic: writer-package filtering, per-metric state
 * classification (permission vs empty vs error kept distinct), conservative same-event pairing,
 * calculated fat mass / BMI, and formatting with explicit "Calculated" labelling.
 */
class WithingsBodyTest {

    private val t0 = Instant.parse("2026-07-14T16:23:00Z")

    @Test
    fun `retains only records from the requested writer package`() {
        val items = listOf("a" to WITHINGS_PACKAGE, "b" to "com.sec.android.app.shealth", "c" to WITHINGS_PACKAGE)
        assertEquals(listOf("a", "c"), retainByWriter(items, WITHINGS_PACKAGE) { it.second }.map { it.first })
    }

    @Test
    fun `permission denial is reported as PERMISSION_DENIED, never EMPTY`() {
        val state = classifyBodyMetric("Basal metabolic rate", "kcal/day", permissionGranted = false, readings = null)
        assertEquals(SourceReadStatus.PERMISSION_DENIED, state.status)
    }

    @Test
    fun `no readings with permission granted is EMPTY`() {
        val state = classifyBodyMetric("Bone mass", "kg", permissionGranted = true, readings = emptyList())
        assertEquals(SourceReadStatus.EMPTY, state.status)
    }

    @Test
    fun `a read error is reported as READ_ERROR with the message`() {
        val state = classifyBodyMetric("Weight", "kg", permissionGranted = true, readings = null, error = "boom")
        assertEquals(SourceReadStatus.READ_ERROR, state.status)
        assertEquals("boom", state.errorMessage)
    }

    @Test
    fun `populated readings expose latest and previous by measurement time`() {
        val state = classifyBodyMetric(
            "Weight", "kg", permissionGranted = true,
            readings = listOf(
                MetricReading(80.0, t0.minusSeconds(86400)),
                MetricReading(79.5, t0),
            ),
        )
        assertEquals(SourceReadStatus.POPULATED, state.status)
        assertEquals(79.5, state.latest!!.value, 0.0)
        assertEquals(80.0, state.previous!!.value, 0.0)
    }

    @Test
    fun `readings within the tolerance pair for calculation`() {
        assertTrue(pairedForCalculation(MetricReading(80.0, t0), MetricReading(25.0, t0.plusSeconds(120))))
    }

    @Test
    fun `readings outside the tolerance do not pair`() {
        assertEquals(false, pairedForCalculation(MetricReading(80.0, t0), MetricReading(25.0, t0.plusSeconds(3600))))
    }

    @Test
    fun `fat mass is weight times body-fat percent when safely paired`() {
        val fatMass = calculatedFatMassKg(MetricReading(80.0, t0), MetricReading(25.0, t0))
        assertEquals(20.0, fatMass!!, 0.0001)
    }

    @Test
    fun `fat mass is null when the readings are from different events`() {
        assertNull(calculatedFatMassKg(MetricReading(80.0, t0), MetricReading(25.0, t0.plusSeconds(9999))))
    }

    @Test
    fun `bmi is weight over height squared when safely paired`() {
        val bmi = calculatedBmi(MetricReading(80.0, t0), MetricReading(1.80, t0.plusSeconds(10)))
        assertEquals(24.69, bmi!!, 0.01)
    }

    @Test
    fun `bmi is null when unpaired or height is zero`() {
        assertNull(calculatedBmi(MetricReading(80.0, t0), null))
        assertNull(calculatedBmi(MetricReading(80.0, t0), MetricReading(0.0, t0)))
    }

    @Test
    fun `formatting labels calculated values and reports every state honestly`() {
        val data = BodyCompositionData(
            metrics = listOf(
                classifyBodyMetric("Weight", "kg", true, listOf(MetricReading(80.0, t0))),
                classifyBodyMetric("Body fat", "%", true, listOf(MetricReading(25.0, t0))),
                classifyBodyMetric("Basal metabolic rate", "kcal/day", false, null),
                classifyBodyMetric("Bone mass", "kg", true, emptyList()),
                BodyMetricState("Waist circumference (Withings)", "cm", SourceReadStatus.SDK_UNAVAILABLE),
            ),
            fatMassKg = 20.0,
            bmi = null,
        )

        val text = formatBodyComposition(data) { "T" }

        assertTrue(text.contains("Weight: 80.0 kg"))
        assertTrue(text.contains("Body fat: 25.0 %"))
        assertTrue(text.contains("Basal metabolic rate: Permission not granted"))
        assertTrue(text.contains("Bone mass: Not available (no Withings data)"))
        assertTrue(text.contains("Waist circumference (Withings): Not supported"))
        assertTrue(text.contains("Fat mass: 20.0 kg (Calculated"))
        assertTrue(text.contains("BMI: Not calculated"))
    }
}
