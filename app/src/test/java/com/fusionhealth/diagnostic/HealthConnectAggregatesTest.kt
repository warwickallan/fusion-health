package com.fusionhealth.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Covers the local-day step-total aggregation added after Warwick's fourth device test showed
 * the diagnostic's `count` (StepsRecord objects) being read as a step total. Verifies (a) the
 * local-midnight day-start computation, (b) that the aggregate request is constructed against
 * the real `androidx.health.connect.client.request.AggregateRequest` SDK class, and (c) the
 * diagnostic wording keeps record_count and aggregate_step_total explicitly distinct.
 */
class HealthConnectAggregatesTest {

    private val sydney: ZoneId = ZoneId.of("Australia/Sydney")

    @Test
    fun `localDayStart returns local midnight for the given zone, not UTC midnight`() {
        // 2026-07-13T21:36 local Sydney time (AEST, UTC+10) == 2026-07-13T11:36Z.
        val now = Instant.parse("2026-07-13T11:36:00Z")

        val dayStart = localDayStart(zone = sydney, now = now)

        // Local midnight 2026-07-13T00:00+10:00 == 2026-07-12T14:00Z.
        assertEquals(Instant.parse("2026-07-12T14:00:00Z"), dayStart)
    }

    @Test
    fun `localDayStart is never after now`() {
        val now = Instant.parse("2026-07-13T11:36:00Z")

        val dayStart = localDayStart(zone = sydney, now = now)

        assertTrue(dayStart <= now)
    }

    @Test
    fun `aggregate request constructs against the real SDK AggregateRequest class`() {
        val start = Instant.parse("2026-07-12T14:00:00Z")
        val end = Instant.parse("2026-07-13T11:36:00Z")

        // The real AggregateRequest constructor validates its inputs; constructing it here
        // exercises the same object the production path sends to Health Connect.
        val request = buildStepsTotalAggregateRequest(start, end)

        assertNotNull(request)
    }

    @Test
    fun `steps section reports record count and step total as explicitly distinct numbers`() {
        val section = formatStepsTotalSection(
            recordCount = 3041,
            stepsTotal = StepsTotalToday(
                total = 6437,
                origins = setOf("com.sec.android.app.shealth", "com.android.healthconnect.phone"),
            ),
        )

        assertTrue(section.contains("aggregate_step_total=6437"))
        assertTrue(section.contains("record_count=3041"))
        assertTrue(section.contains("NOT the same number"))
        assertTrue(section.contains("com.sec.android.app.shealth"))
        assertTrue(section.contains("com.android.healthconnect.phone"))
        assertTrue(section.contains("UNRESOLVED"))
    }

    @Test
    fun `steps section reports permission denial and errors without inventing a total`() {
        val denied = formatStepsTotalSection(recordCount = 0, stepsTotal = StepsTotalToday(permissionDenied = true))
        assertTrue(denied.contains("aggregate_step_total=PERMISSION_DENIED"))

        val errored = formatStepsTotalSection(recordCount = 0, stepsTotal = StepsTotalToday(error = "boom"))
        assertTrue(errored.contains("aggregate_step_total=ERROR (boom)"))
    }
}
