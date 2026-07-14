# WP2 / PR4b — Incremental-sync capability spike

**Status:** implementation complete, pending device test. **Findings below are placeholders until
Warwick runs the device test and reports what Health Connect actually returns.**

## Purpose

Per `docs/wp2-source-authority-and-canonical-contract.md` §9, this is the second follow-on
validation PR from the WP2 design. It exists to answer three questions with evidence, not
assumption:

1. Does Health Connect's change-token / incremental-sync mechanism (`getChangesToken` /
   `getChanges`) exist and work at all?
2. Does it distinguish a record **update** (an upsertion of an ID already seen) from a genuinely
   **new** record, and separately expose **deletions**?
3. Given (1) and (2), what is the smallest correct sync contract Fusion can build on?

This is a diagnostic spike only: no persistent storage, no network services, no Withings OAuth,
no Samsung SDK, no production pipeline. All state lives in `SyncSpikeActivity`'s in-memory fields
and is lost on process death.

## What was built

- `SyncSpikeAnalysis.kt` — pure, Android-independent logic: `ChangesPullSummary`,
  `UpsertionClassification`, `classifyUpsertionIds()`, `formatChangesPullReport()`. Directly unit
  tested (`SyncSpikeAnalysisTest.kt`) without a real `HealthConnectClient` or device.
- `SyncSpikeActivity.kt` — a standalone Activity with its own LAUNCHER entry (separate app-drawer
  icon), so WP1/PR2's device-proven `MainActivity` flow is not touched. Two buttons:
  - **Get changes token** — calls `HealthConnectClient.getChangesToken()` for the same six record
    types PR2 already reads, keeping the token only in memory.
  - **Check for changes** — calls `HealthConnectClient.getChanges(token)`, splits the response
    into `UpsertionChange`/`DeletionChange`, classifies each upsertion's record ID as new or
    already-seen this session (Health Connect does not label updates explicitly, so "seen before
    in this session" is the spike's own signal for "this was an update"), and reports counts and
    pagination/expiry fields (`hasMore`, `changesTokenExpired`) — metadata only, never raw health
    values.

## Methodology (for the device test)

1. Install the spike build; grant the six read permissions from the "Incremental Sync Spike" icon.
2. Tap **Get changes token**.
3. In a source app (Samsung Health, MyFitnessPal, or manual Health Connect entry), add one new
   record, then edit an existing record, then delete a record — as three separate, identifiable
   steps.
4. Tap **Check for changes** after each step and record what the app reports: upsertion count,
   new vs. updated split, deletion count, `has_more`, `changes_token_expired`.
5. Repeat after closing and reopening the app (fresh token) to check behaviour across a restart,
   and after a longer gap to probe token expiry.

## Findings

**Pending device test.** No claims are made here about Health Connect's actual behaviour until
Warwick runs the steps above and reports the results — this avoids repeating WP2 FIX1's original
mistake of asserting unverified API behaviour as fact.

## Next steps after the device test

- If the change-token mechanism works and distinguishes updates from new records: draft the
  "smallest correct sync contract" section of the WP2 design doc from the actual evidence.
- If deletions are not reliably surfaced, or updates are indistinguishable from new records: note
  the gap explicitly and decide (with Warwick) whether a periodic full-reconciliation fallback is
  required in the eventual sync contract.
- Either result feeds PR4c (dedup-rule validation) and PR4d (Withings-tolerance validation), per
  the WP2 design doc's implementation sequence.
