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

/**
 * Follows Health Connect's `pageToken` chain to completion, accumulating every page's records.
 * Isolated from [MainActivity] and [androidx.health.connect.client.HealthConnectClient] so the
 * pagination/termination logic can be unit-tested with a fake [fetchPage] instead of a real
 * Health Connect client or an Android runtime.
 *
 * Terminates when a page returns a null/empty next-page token. Two defensive guards prevent an
 * unbounded loop if Health Connect ever misbehaves: a repeated page token (the same token
 * returned twice in a row) or exceeding [maxPages] both stop iteration early and mark the result
 * `truncated = true` with a reason, rather than looping forever.
 */
internal suspend fun <T> accumulatePages(
    maxPages: Int,
    fetchPage: suspend (pageToken: String) -> PageFetchResult<T>,
): PaginationResult<T> {
    val allRecords = mutableListOf<T>()
    val seenPageTokens = mutableSetOf<String>()
    var pagesRead = 0
    var truncated = false
    var truncationReason: String? = null
    var pageToken = ""

    while (true) {
        val page = fetchPage(pageToken)
        allRecords += page.records
        pagesRead++

        val nextToken = page.nextPageToken?.takeIf { it.isNotEmpty() }
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
