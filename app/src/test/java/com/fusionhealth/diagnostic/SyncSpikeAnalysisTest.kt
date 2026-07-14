package com.fusionhealth.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [SyncSpikeAnalysis]'s pure logic: classifying observed upsertion IDs against a
 * prior session's IDs, and formatting a changes-pull report. No Health Connect SDK or device
 * involved -- these tests only verify the classification/formatting rules the spike depends on.
 */
class SyncSpikeAnalysisTest {

    @Test
    fun `an id never seen before this session is classified as new`() {
        val result = classifyUpsertionIds(
            observedIds = listOf("id-1", "id-2"),
            previouslySeenIds = emptySet(),
        )

        assertEquals(2, result.newCount)
        assertEquals(0, result.updatedCount)
    }

    @Test
    fun `an id already seen this session is classified as updated`() {
        val result = classifyUpsertionIds(
            observedIds = listOf("id-1", "id-2"),
            previouslySeenIds = setOf("id-1"),
        )

        assertEquals(1, result.newCount)
        assertEquals(1, result.updatedCount)
    }

    @Test
    fun `an empty observed batch classifies as zero new and zero updated`() {
        val result = classifyUpsertionIds(
            observedIds = emptyList(),
            previouslySeenIds = setOf("id-1"),
        )

        assertEquals(0, result.newCount)
        assertEquals(0, result.updatedCount)
    }

    @Test
    fun `a repeated observed id is counted once per occurrence, not deduplicated`() {
        val result = classifyUpsertionIds(
            observedIds = listOf("id-1", "id-1"),
            previouslySeenIds = emptySet(),
        )

        assertEquals(2, result.newCount)
        assertEquals(0, result.updatedCount)
    }

    @Test
    fun `report includes upsertion, deletion and pagination fields`() {
        val summary = ChangesPullSummary(
            upsertionCount = 3,
            deletionCount = 1,
            newRecordIdCount = 2,
            updatedRecordIdCount = 1,
            hasMore = true,
            changesTokenExpired = false,
        )

        val report = formatChangesPullReport(pullNumber = 1, summary = summary)

        assertTrue(report.contains("Changes pull #1"))
        assertTrue(report.contains("upsertions=3 (new=2, updated=1)"))
        assertTrue(report.contains("deletions=1"))
        assertTrue(report.contains("has_more=true"))
        assertTrue(report.contains("changes_token_expired=false"))
    }

    @Test
    fun `report calls out an expired token explicitly`() {
        val summary = ChangesPullSummary(
            upsertionCount = 0,
            deletionCount = 0,
            newRecordIdCount = 0,
            updatedRecordIdCount = 0,
            hasMore = false,
            changesTokenExpired = true,
        )

        val report = formatChangesPullReport(pullNumber = 2, summary = summary)

        assertTrue(report.contains("changes_token_expired=true"))
        assertTrue(report.contains("Token expired"))
    }
}
