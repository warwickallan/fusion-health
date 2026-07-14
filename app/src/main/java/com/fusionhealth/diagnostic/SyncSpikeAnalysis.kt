package com.fusionhealth.diagnostic

/**
 * BUILD-005/WP2/PR4b — incremental-sync capability spike. Pure, Android-independent logic for
 * interpreting Health Connect's Changes API (`getChangesToken` / `getChanges`), kept separate
 * from [SyncSpikeActivity] so the draining, classification and report-formatting logic is directly
 * unit-testable without a real `HealthConnectClient` or device.
 *
 * This exists to answer questions from the WP2 design doc (§5/§8/§9), and nothing else:
 *   1. Does an official Health Connect change-token/delta mechanism exist and work at all?
 *   2. When a source record is edited, does its upsertion carry the SAME record ID (a repeat-seen
 *      ID) or a different one? The spike observes this; it does not assume it.
 *   3. Are deletions surfaced distinctly from upsertions?
 *
 * Deliberately evidence-accurate naming: the API does not label a change "new record" vs
 * "updated record". All this logic can observe is whether a record ID has been seen before in
 * this same in-memory spike session. So it reports `firstSeenIdCount` / `repeatSeenIdCount`, never
 * "new" / "updated" as if those were API facts. Causal interpretation ("likely insert" / "likely
 * update") is only ever written into the findings AFTER a controlled create/edit/delete sequence
 * gives the counts their meaning.
 */

/** One page of `getChanges(token)`, reduced to what the drain logic needs -- no raw values. */
internal data class ChangesPage(
    val upsertionIds: List<String>,
    val deletionCount: Int,
    val nextChangesToken: String,
    val hasMore: Boolean,
    val changesTokenExpired: Boolean,
)

/** One fully-drained "check for changes" pull (all pages), reduced to counts -- no raw values. */
internal data class ChangesPullSummary(
    val pagesDrained: Int,
    val upsertionCount: Int,
    val deletionCount: Int,
    val firstSeenIdCount: Int,
    val repeatSeenIdCount: Int,
    val finalHasMore: Boolean,
    val changesTokenExpired: Boolean,
    val stoppedReason: String,
    /** The token to use for the NEXT pull, or null if the token expired mid-drain. */
    val nextChangesToken: String?,
)

/** Result of classifying a batch of observed upsertion IDs against IDs seen earlier this session. */
internal data class UpsertionClassification(val firstSeenCount: Int, val repeatSeenCount: Int)

/**
 * Classifies a batch of observed upsertion record IDs against IDs already seen earlier in the
 * same in-memory spike session (never persisted across app restarts -- dies with the process,
 * consistent with the no-persistent-storage guardrail). A "first-seen" ID has not appeared in this
 * session; a "repeat-seen" ID has.
 *
 * IMPORTANT: first-seen does NOT mean "newly created record", and repeat-seen does NOT mean
 * "record updated". An edit to a record that existed before the token was obtained will appear
 * for the FIRST time in this session and is therefore first-seen despite being an update. Only a
 * controlled create-then-edit sequence on the SAME record lets the findings interpret a repeat of
 * the same ID as evidence of update behaviour.
 */
internal fun classifyUpsertionIds(
    observedIds: List<String>,
    previouslySeenIds: Set<String>,
): UpsertionClassification {
    var firstSeen = 0
    var repeatSeen = 0
    for (id in observedIds) {
        if (id in previouslySeenIds) repeatSeen++ else firstSeen++
    }
    return UpsertionClassification(firstSeen, repeatSeen)
}

/**
 * Drains every change page for one "check for changes" action, so a single experimental pull is
 * complete before Warwick performs the next controlled source-app action. Follows
 * `nextChangesToken` until [ChangesPage.hasMore] is false, the token expires, a token repeats (a
 * misbehaving/looping provider), or a defensive page cap is hit -- mirroring the WP1/PR2
 * pagination guards. [seenIds] is updated in place with every observed upsertion ID so subsequent
 * pulls can tell first-seen from repeat-seen.
 *
 * [fetchPage] is a suspend lambda taking the current token and returning one page, so the real
 * Activity can pass `healthConnectClient.getChanges(...)` straight in, while unit tests pass an
 * in-memory fake driven with `runBlocking` -- no `HealthConnectClient` required either way. The
 * accumulation and all guards live here, so there is exactly one copy of this logic.
 */
internal suspend fun drainChanges(
    startToken: String,
    seenIds: MutableSet<String>,
    maxPages: Int = 1000,
    fetchPage: suspend (token: String) -> ChangesPage,
): ChangesPullSummary {
    var token = startToken
    var pages = 0
    var upsertions = 0
    var deletions = 0
    var firstSeen = 0
    var repeatSeen = 0
    var hasMore = true
    var tokenExpired = false
    var stoppedReason = "has_more=false"
    val usedTokens = mutableSetOf(startToken)

    while (true) {
        if (pages >= maxPages) {
            stoppedReason = "page guard hit ($maxPages pages)"
            break
        }
        val page = fetchPage(token)
        pages++

        val classification = classifyUpsertionIds(page.upsertionIds, seenIds)
        firstSeen += classification.firstSeenCount
        repeatSeen += classification.repeatSeenCount
        seenIds += page.upsertionIds
        upsertions += page.upsertionIds.size
        deletions += page.deletionCount

        if (page.changesTokenExpired) {
            tokenExpired = true
            hasMore = false
            stoppedReason = "changes_token_expired"
            break
        }
        if (!page.hasMore) {
            hasMore = false
            token = page.nextChangesToken
            stoppedReason = "has_more=false"
            break
        }
        // hasMore == true: advance, but guard against a provider that hands back a repeated token.
        if (!usedTokens.add(page.nextChangesToken)) {
            hasMore = true
            token = page.nextChangesToken
            stoppedReason = "repeated page token after $pages pages"
            break
        }
        token = page.nextChangesToken
    }

    return ChangesPullSummary(
        pagesDrained = pages,
        upsertionCount = upsertions,
        deletionCount = deletions,
        firstSeenIdCount = firstSeen,
        repeatSeenIdCount = repeatSeen,
        finalHasMore = hasMore,
        changesTokenExpired = tokenExpired,
        stoppedReason = stoppedReason,
        nextChangesToken = if (tokenExpired) null else token,
    )
}

/** Formats one fully-drained pull's results as plain diagnostic text -- metadata/counts only. */
internal fun formatChangesPullReport(pullNumber: Int, summary: ChangesPullSummary): String {
    val sb = StringBuilder()
    sb.appendLine("== Changes pull #$pullNumber (drained) ==")
    sb.appendLine("pages_drained=${summary.pagesDrained}")
    sb.appendLine(
        "upsertions=${summary.upsertionCount} " +
            "(first_seen_id=${summary.firstSeenIdCount}, repeat_seen_id=${summary.repeatSeenIdCount})"
    )
    sb.appendLine("deletions=${summary.deletionCount}")
    sb.appendLine("final_has_more=${summary.finalHasMore}")
    sb.appendLine("changes_token_expired=${summary.changesTokenExpired}")
    sb.appendLine("stopped_reason=${summary.stoppedReason}")
    sb.appendLine(
        "note: first_seen/repeat_seen are ID-observation facts only, NOT API claims of " +
            "insert vs update. Interpret only against your controlled create/edit/delete actions."
    )
    if (summary.changesTokenExpired) {
        sb.appendLine(
            "Token expired mid-drain -- a fresh token must be obtained; the prior token's " +
                "continuity is broken at this point."
        )
    }
    return sb.toString()
}
