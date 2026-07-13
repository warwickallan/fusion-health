package com.fusionhealth.diagnostic

/** One Health Connect page: the records it returned plus the token for the next page, if any. */
internal data class PageFetchResult<T>(val records: List<T>, val nextPageToken: String?)

/** Accumulated result of following every page for a single Health Connect record type. */
internal data class PaginationResult<T>(
    val records: List<T>,
    val pagesRead: Int,
    val truncated: Boolean,
    val truncationReason: String?,
)

/** Thrown by [accumulatePages] when a page fetch fails after at least one earlier page
 * succeeded, so the caller can still recover the pages read so far instead of losing them. */
internal class PaginationFailure(
    override val cause: Throwable,
    val partialResult: PaginationResult<*>,
) : Exception(cause)

/**
 * Follows Health Connect's `pageToken` chain to completion, accumulating every page's records.
 * Isolated from [MainActivity] and [androidx.health.connect.client.HealthConnectClient] so the
 * pagination/termination logic can be unit-tested with a fake [fetchPage] instead of a real
 * Health Connect client or an Android runtime.
 *
 * The first call to [fetchPage] receives `null` — Health Connect's first page must be requested
 * with no page token at all, not an empty string. Passing `""` for the first request is a real
 * defect this function must never reintroduce: `ReadRecordsRequest` parses a non-null pageToken
 * as an opaque continuation token, and an empty string fails with `NumberFormatException: For
 * input string: ""` before any record is read. A blank string returned as a *next* token is
 * likewise treated as "no more pages", identically to a null next token — it must never be fed
 * back into [fetchPage] as if it were a real continuation token.
 *
 * Terminates when a page returns a null/blank next-page token. Two defensive guards prevent an
 * unbounded loop if Health Connect ever misbehaves: a repeated page token (the same non-blank
 * token returned twice) or exceeding [maxPages] both stop iteration early and mark the result
 * `truncated = true` with a reason, rather than looping forever.
 *
 * If [fetchPage] throws after at least one page has already been read, the records, page count,
 * and truncation reason gathered so far are preserved and surfaced via [PaginationFailure] rather
 * than discarded — callers should catch [PaginationFailure] to report a genuine partial result,
 * separately from a first-page failure (which propagates as the original exception, since there
 * is nothing partial to report).
 */
internal suspend fun <T> accumulatePages(
    maxPages: Int,
    fetchPage: suspend (pageToken: String?) -> PageFetchResult<T>,
): PaginationResult<T> {
    val allRecords = mutableListOf<T>()
    val seenPageTokens = mutableSetOf<String>()
    var pagesRead = 0
    var truncated = false
    var truncationReason: String? = null
    var pageToken: String? = null

    while (true) {
        val page = try {
            fetchPage(pageToken)
        } catch (e: Exception) {
            if (pagesRead > 0) {
                throw PaginationFailure(
                    cause = e,
                    partialResult = PaginationResult(
                        records = allRecords,
                        pagesRead = pagesRead,
                        truncated = true,
                        truncationReason = e.message ?: "read error after $pagesRead pages",
                    ),
                )
            }
            throw e
        }
        allRecords += page.records
        pagesRead++

        val nextToken = page.nextPageToken?.takeIf { it.isNotBlank() }
        when {
            nextToken == null -> break
            !seenPageTokens.add(nextToken) -> {
                truncated = true
                truncationReason = "repeated page token after $pagesRead pages"
                break
            }
            pagesRead >= maxPages -> {
                truncated = true
                truncationReason = "page guard hit ($maxPages pages)"
                break
            }
            else -> pageToken = nextToken
        }
    }

    return PaginationResult(allRecords, pagesRead, truncated, truncationReason)
}
