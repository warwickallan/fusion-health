package com.fusionhealth.diagnostic

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression coverage for two PR2 pagination defects. First: Fusion Health originally read only
 * Health Connect's first page (1,000 records), silently discarding everything beyond it. Second,
 * found only after the first fix shipped: the first request passed an empty string ("") as its
 * page token instead of null/omitting it, which Health Connect's SDK rejects with
 * `NumberFormatException: For input string: ""` before reading any record at all -- every record
 * type failed identically with pages_read=0. These tests exercise [accumulatePages] directly with
 * a fake, in-memory paged source so the page-chain logic -- including the exact null-vs-blank
 * token contract -- is verified without a real Health Connect client or an Android runtime.
 */
class HealthConnectPaginationTest {

    private data class TestRecord(val source: String)

    @Test
    fun `first fetch receives null, never an empty string`() = runBlocking {
        var receivedFirstToken: String? = "not yet called"

        accumulatePages<TestRecord>(maxPages = 200) { pageToken ->
            if (receivedFirstToken == "not yet called") {
                receivedFirstToken = pageToken
            }
            PageFetchResult(listOf(TestRecord("x")), null)
        }

        assertNull("first page token must be null, not \"\"", receivedFirstToken)
    }

    @Test
    fun `subsequent fetch receives the exact token returned by the previous page`() = runBlocking {
        val receivedTokens = mutableListOf<String?>()

        accumulatePages<TestRecord>(maxPages = 200) { pageToken ->
            receivedTokens += pageToken
            when (receivedTokens.size) {
                1 -> PageFetchResult(listOf(TestRecord("a")), "token-1")
                2 -> PageFetchResult(listOf(TestRecord("b")), "token-2")
                else -> PageFetchResult(listOf(TestRecord("c")), null)
            }
        }

        assertEquals(listOf(null, "token-1", "token-2"), receivedTokens)
    }

    @Test
    fun `a null next-page token terminates pagination normally`() = runBlocking {
        val result = accumulatePages<TestRecord>(maxPages = 200) {
            PageFetchResult(listOf(TestRecord("only")), null)
        }

        assertEquals(1, result.pagesRead)
        assertEquals(1, result.records.size)
        assertFalse(result.truncated)
        assertNull(result.truncationReason)
    }

    @Test
    fun `a blank returned next-page token terminates pagination normally, not as a real token`() = runBlocking {
        var callCount = 0

        val result = accumulatePages<TestRecord>(maxPages = 200) {
            callCount++
            PageFetchResult(listOf(TestRecord("only")), "")
        }

        assertEquals(1, callCount) // the blank token must never be fed back into fetchPage
        assertEquals(1, result.pagesRead)
        assertFalse(result.truncated)
    }

    @Test
    fun `follows multiple pages until a null token and accumulates every record`() = runBlocking {
        val pages = listOf(
            PageFetchResult(listOf(TestRecord("a"), TestRecord("a")), "token-1"),
            PageFetchResult(listOf(TestRecord("b")), "token-2"),
            PageFetchResult(listOf(TestRecord("a"), TestRecord("c")), null),
        )
        var callIndex = 0

        val result = accumulatePages<TestRecord>(maxPages = 200) { pageToken ->
            val expectedToken = if (callIndex == 0) null else pages[callIndex - 1].nextPageToken
            assertEquals(expectedToken, pageToken)
            pages[callIndex++]
        }

        assertEquals(3, result.pagesRead)
        assertEquals(5, result.records.size)
        assertEquals(setOf("a", "b", "c"), result.records.map { it.source }.toSet())
        assertFalse(result.truncated)
        assertNull(result.truncationReason)
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

    @Test
    fun `a failure on the first page propagates directly with no partial result`() = runBlocking {
        val boom = RuntimeException("boom")
        try {
            accumulatePages<TestRecord>(maxPages = 200) { throw boom }
            fail("expected an exception")
        } catch (e: Exception) {
            assertSame(boom, e)
        }
    }

    @Test
    fun `a failure after earlier pages succeeded preserves the partial result`() = runBlocking {
        var callCount = 0
        val boom = RuntimeException("page 2 exploded")

        try {
            accumulatePages<TestRecord>(maxPages = 200) { pageToken ->
                callCount++
                if (callCount == 1) {
                    PageFetchResult(listOf(TestRecord("a"), TestRecord("b")), "token-1")
                } else {
                    throw boom
                }
            }
            fail("expected a PaginationFailure")
        } catch (e: PaginationFailure) {
            assertSame(boom, e.cause)
            val partial = e.partialResult
            assertEquals(1, partial.pagesRead)
            assertEquals(2, partial.records.size)
            assertTrue(partial.truncated)
            assertEquals("page 2 exploded", partial.truncationReason)
        }
    }
}
