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

## What this build can and cannot test

`currentToken` in `SyncSpikeActivity` is an in-memory field only, by design (no persistent
storage). That gives this build three distinct, non-equivalent continuity scopes:

- **Same-process repeated pulls** — supported and the primary thing this spike tests: get a
  token once, make several source-app changes, tap "Check for changes" multiple times while the
  app stays running.
- **Activity recreation while the process stays alive** (e.g. rotation, backgrounding without the
  process being killed) — potentially observable, since `currentToken` is a field on the
  Activity instance and Android may or may not recreate that instance depending on OS behaviour,
  but not guaranteed and not something this spike deliberately exercises or asserts about.
- **Full app/process close and reopen** — **token continuity cannot be tested with this build.**
  Closing the app discards `currentToken`; reopening always starts a fresh
  `getChangesToken()` call. Whether a token obtained in an earlier process remains valid is
  therefore **unresolved and outside this spike's proven capability** — it would require either
  persisting the token (not authorised for this spike) or a differently-scoped follow-on test.

## Methodology (for the device test)

1. Install the spike build; grant the six read permissions from the "Incremental Sync Spike" icon.
2. Tap **Get changes token**.
3. Create one new record in a source app, return to the spike, tap **Check for changes**, and
   record the complete metadata/count output (upsertion count, new vs. updated split, deletion
   count, `has_more`, `changes_token_expired`).
4. Edit an existing source record where the source app supports editing; tap **Check for
   changes** again and record the output.
5. Delete a source record where supported; tap **Check for changes** again and record the output.
6. Perform at least one further pull with no intervening change, to observe empty-delta behaviour.
7. All of the above is a same-process test. Do not claim, and do not attempt to test, restart
   (full close/reopen) token continuity with this build — see "What this build can and cannot
   test" above.

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
