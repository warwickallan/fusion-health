package com.fusionhealth.diagnostic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [SyncSpikeAnalysis]'s pure logic: classifying observed upsertion IDs as first-seen
 * vs repeat-seen this session, draining every change page (with the same has-more / expiry /
 * repeat-token guards as WP1/PR2 pagination), and formatting a drained-pull report. No Health
 * Connect SDK or device involved -- an in-memory fake page source is driven with `runBlocking`.
 *
 * Naming is deliberately evidence-accurate: first-seen is NOT "new record" and repeat-seen is NOT
 * "updated record" -- these tests verify ID-observation facts only, exactly as the spike reports
 * them, leaving causal interpretation to the controlled device test.
 */
class SyncSpikeAnalysisTest {

    // ---- classifyUpsertionIds ------------------------------------------------------------------

    @Test
    fun `an id never seen before this session is classified as first-seen`() {
        val result = classifyUpsertionIds(listOf("id-1", "id-2"), emptySet())
        assertEquals(2, result.firstSeenCount)
        assertEquals(0, result.repeatSeenCount)
    }

    @Test
    fun `an id already seen this session is classified as repeat-seen`() {
        val result = classifyUpsertionIds(listOf("id-1", "id-2"), setOf("id-1"))
        assertEquals(1, result.firstSeenCount)
        assertEquals(1, result.repeatSeenCount)
    }

    @Test
    fun `an empty observed batch classifies as zero first-seen and zero repeat-seen`() {
        val result = classifyUpsertionIds(emptyList(), setOf("id-1"))
        assertEquals(0, result.firstSeenCount)
        assertEquals(0, result.repeatSeenCount)
    }

    @Test
    fun `a repeated observed id in one batch is counted once per occurrence`() {
        val result = classifyUpsertionIds(listOf("id-1", "id-1"), emptySet())
        assertEquals(2, result.firstSeenCount)
        assertEquals(0, result.repeatSeenCount)
    }

    // ---- drainChanges --------------------------------------------------------------------------

    private fun page(
        ids: List<String>,
        deletions: Int = 0,
        next: String,
        hasMore: Boolean,
        expired: Boolean = false,
    ) = ChangesPage(ids, deletions, next, hasMore, expired)

    @Test
    fun `a single page with no more drains in one page and reports counts`() = runBlocking {
        val seen = mutableSetOf<String>()
        val summary = drainChanges("t0", seen) {
            page(listOf("a", "b"), deletions = 1, next = "t1", hasMore = false)
        }

        assertEquals(1, summary.pagesDrained)
        assertEquals(2, summary.upsertionCount)
        assertEquals(1, summary.deletionCount)
        assertEquals(2, summary.firstSeenIdCount)
        assertEquals(0, summary.repeatSeenIdCount)
        assertFalse(summary.finalHasMore)
        assertEquals("has_more=false", summary.stoppedReason)
        assertEquals("t1", summary.nextChangesToken)
    }

    @Test
    fun `drains multiple pages until has-more is false and accumulates across them`() = runBlocking {
        val pages = listOf(
            page(listOf("a"), next = "t1", hasMore = true),
            page(listOf("b", "c"), deletions = 2, next = "t2", hasMore = true),
            page(listOf("d"), next = "t3", hasMore = false),
        )
        var i = 0
        val seen = mutableSetOf<String>()

        val summary = drainChanges("t0", seen) { pages[i++] }

        assertEquals(3, summary.pagesDrained)
        assertEquals(4, summary.upsertionCount)
        assertEquals(2, summary.deletionCount)
        assertEquals(4, summary.firstSeenIdCount)
        assertEquals(setOf("a", "b", "c", "d"), seen)
        assertEquals("t3", summary.nextChangesToken)
    }

    @Test
    fun `an id seen on an earlier pull is repeat-seen on a later drain`() = runBlocking {
        val seen = mutableSetOf<String>()
        drainChanges("t0", seen) { page(listOf("x"), next = "t1", hasMore = false) }

        val summary = drainChanges("t1", seen) {
            page(listOf("x", "y"), next = "t2", hasMore = false)
        }

        assertEquals(1, summary.firstSeenIdCount) // y
        assertEquals(1, summary.repeatSeenIdCount) // x
    }

    @Test
    fun `an expired token stops the drain and yields a null next token`() = runBlocking {
        val seen = mutableSetOf<String>()
        val summary = drainChanges("t0", seen) {
            page(emptyList(), next = "t1", hasMore = false, expired = true)
        }

        assertTrue(summary.changesTokenExpired)
        assertEquals("changes_token_expired", summary.stoppedReason)
        assertNull(summary.nextChangesToken)
    }

    @Test
    fun `a repeated page token stops the drain defensively`() = runBlocking {
        val seen = mutableSetOf<String>()
        val summary = drainChanges("t0", seen) {
            // Always hands back the same next token while claiming more -- a looping provider.
            page(listOf("x"), next = "stuck", hasMore = true)
        }

        assertTrue(summary.stoppedReason.startsWith("repeated page token"))
        // t0 seeds usedTokens; page 1 adds "stuck"; page 2's repeat of "stuck" trips the guard.
        assertEquals(2, summary.pagesDrained)
    }

    @Test
    fun `the page guard stops an unbounded drain`() = runBlocking {
        var n = 0
        val seen = mutableSetOf<String>()
        val summary = drainChanges("t0", seen, maxPages = 5) {
            n++
            page(listOf("x-$n"), next = "t-$n", hasMore = true)
        }

        assertEquals("page guard hit (5 pages)", summary.stoppedReason)
        assertEquals(5, summary.pagesDrained)
    }

    // ---- formatChangesPullReport ---------------------------------------------------------------

    @Test
    fun `report includes drained counts and the evidence-accuracy note`() {
        val summary = ChangesPullSummary(
            pagesDrained = 2,
            upsertionCount = 3,
            deletionCount = 1,
            firstSeenIdCount = 2,
            repeatSeenIdCount = 1,
            finalHasMore = false,
            changesTokenExpired = false,
            stoppedReason = "has_more=false",
            nextChangesToken = "t9",
        )

        val report = formatChangesPullReport(pullNumber = 1, summary = summary)

        assertTrue(report.contains("Changes pull #1"))
        assertTrue(report.contains("pages_drained=2"))
        assertTrue(report.contains("upsertions=3 (first_seen_id=2, repeat_seen_id=1)"))
        assertTrue(report.contains("deletions=1"))
        assertTrue(report.contains("final_has_more=false"))
        assertTrue(report.contains("stopped_reason=has_more=false"))
        assertTrue(report.contains("NOT API claims"))
    }

    @Test
    fun `report calls out an expired token explicitly`() {
        val summary = ChangesPullSummary(
            pagesDrained = 1,
            upsertionCount = 0,
            deletionCount = 0,
            firstSeenIdCount = 0,
            repeatSeenIdCount = 0,
            finalHasMore = false,
            changesTokenExpired = true,
            stoppedReason = "changes_token_expired",
            nextChangesToken = null,
        )

        val report = formatChangesPullReport(pullNumber = 2, summary = summary)

        assertTrue(report.contains("changes_token_expired=true"))
        assertTrue(report.contains("Token expired"))
    }
}
