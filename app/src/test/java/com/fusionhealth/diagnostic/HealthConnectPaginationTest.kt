package com.fusionhealth.diagnostic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the PR2 pagination defect: Fusion Health originally read only Health
 * Connect's first page (1,000 records), silently discarding everything beyond it. These tests
 * exercise [accumulatePages] directly with a fake, in-memory paged source so the page-chain
 * logic is verified without a real Health Connect client or an Android runtime.
 */
class HealthConnectPaginationTest {

    private data class TestRecord(val source: String)

    @Test
    fun `follows multiple pages until a null token and accumulates every record`() = runBlocking {
        val pages = listOf(
            PageFetchResult(listOf(TestRecord("a"), TestRecord("a")), "token-1"),
            PageFetchResult(listOf(TestRecord("b")), "token-2"),
            PageFetchResult(listOf(TestRecord("a"), TestRecord("c")), null),
        )
        var callIndex = 0

        val result = accumulatePages<TestRecord>(maxPages = 200) { pageToken ->
            val expectedToken = if (callIndex == 0) "" else pages[callIndex - 1].nextPageToken
            assertEquals(expectedToken, pageToken)
            pages[callIndex++]
        }

        assertEquals(3, result.pagesRead)
        assertEquals(5, result.records.size)
        assertEquals(setOf("a", "b", "c"), result.records.map { it.source }.toSet())
        assertFalse(result.truncated)
        assertEquals(null, result.truncationReason)
    }

    @Test
    fun `single page with a null token straight away is not truncated`() = runBlocking {
        val result = accumulatePages<TestRecord>(maxPages = 200) {
            PageFetchResult(listOf(TestRecord("only")), null)
        }

        assertEquals(1, result.pagesRead)
        assertEquals(1, result.records.size)
        assertFalse(result.truncated)
    }

    @Test
    fun `empty next-page token string is treated the same as null`() = runBlocking {
        val result = accumulatePages<TestRecord>(maxPages = 200) {
            PageFetchResult(listOf(TestRecord("only")), "")
        }

        assertEquals(1, result.pagesRead)
        assertFalse(result.truncated)
    }

    @Test
    fun `a repeated page token stops iteration and marks the result truncated`() = runBlocking {
        var callCount = 0

        val result = accumulatePages<TestRecord>(maxPages = 200) {
            callCount++
            // Always hands back the same token — a misbehaving/looping API.
            PageFetchResult(listOf(TestRecord("x")), "stuck-token")
        }

        assertTrue(result.truncated)
        assertEquals("repeated page token after 2 pages", result.truncationReason)
        assertEquals(2, callCount) // stops after the repeat is detected, not before
        assertEquals(2, result.records.size)
    }

    @Test
    fun `the page guard stops iteration before an unbounded loop`() = runBlocking {
        var callCount = 0

        val result = accumulatePages<TestRecord>(maxPages = 5) {
            callCount++
            // A distinct token every time: pagination would never naturally terminate.
            PageFetchResult(listOf(TestRecord("x")), "token-$callCount")
        }

        assertTrue(result.truncated)
        assertEquals("page guard hit (5 pages)", result.truncationReason)
        assertEquals(5, result.pagesRead)
        assertEquals(5, callCount)
    }
}
