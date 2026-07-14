package com.fusionhealth.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Coverage for [SamsungCapabilitySweep]'s pure logic: source-package filtering, status
 * classification, compact duration formatting, and report formatting. No Health Connect SDK or
 * device involved. No real health values -- synthetic inputs only.
 */
class SamsungCapabilitySweepTest {

    @Test
    fun `only the Samsung Health package is recognised as Samsung`() {
        assertTrue(isSamsungPackage("com.sec.android.app.shealth"))
        assertFalse(isSamsungPackage("com.withings.wiscale2"))
        assertFalse(isSamsungPackage("com.myfitnesspal.android"))
    }

    @Test
    fun `retainSamsung keeps only Samsung-origin items`() {
        val items = listOf(
            "a" to "com.sec.android.app.shealth",
            "b" to "com.withings.wiscale2",
            "c" to "com.sec.android.app.shealth",
        )
        val kept = retainSamsung(items) { it.second }
        assertEquals(listOf("a", "c"), kept.map { it.first })
    }

    @Test
    fun `status is POPULATED when Samsung records exist, EMPTY otherwise`() {
        assertEquals(SweepStatus.POPULATED, populatedOrEmpty(3))
        assertEquals(SweepStatus.EMPTY, populatedOrEmpty(0))
    }

    @Test
    fun `duration formatting is compact across ranges`() {
        assertEquals("2h 05m", formatDuration(2 * 3600 + 5 * 60L))
        assertEquals("45m 00s", formatDuration(45 * 60L))
        assertEquals("30s", formatDuration(30L))
        assertEquals("0s", formatDuration(-1L))
    }

    @Test
    fun `report lists every type with status, counts and summaries but no raw dump`() {
        val results = listOf(
            TypeSweepResult(
                label = "Steps",
                status = SweepStatus.POPULATED,
                count = 12,
                pagesRead = 1,
                earliest = Instant.parse("2026-07-14T06:00:00Z"),
                latest = Instant.parse("2026-07-14T07:00:00Z"),
                writerPackages = setOf("com.sec.android.app.shealth"),
                samsungFound = true,
                valueSummary = "latest_count=500",
            ),
            TypeSweepResult(
                label = "Blood glucose",
                status = SweepStatus.PERMISSION_DENIED,
            ),
        )

        val report = formatSweepReport(results, "2026-07-14 08:00:00 (UTC)")

        assertTrue(report.contains("Samsung Health Capability Sweep"))
        assertTrue(report.contains("types with Samsung data: 1 / 2"))
        assertTrue(report.contains("-- Steps --"))
        assertTrue(report.contains("status=POPULATED"))
        assertTrue(report.contains("samsung_found=true"))
        assertTrue(report.contains("count=12, pages_read=1"))
        assertTrue(report.contains("summary: latest_count=500"))
        assertTrue(report.contains("-- Blood glucose --"))
        assertTrue(report.contains("status=PERMISSION_DENIED"))
    }
}
