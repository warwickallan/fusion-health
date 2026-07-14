# WP2 / PR4b — Incremental-sync capability spike

**Status: SPIKE OBJECTIVE ACHIEVED.** Device evidence accepted by Warwick. The MVP requirement —
Withings scale measurement → Health Connect → Fusion Health can read the weight record — is proven.

**Dependency:** this spike is a follow-on proposed by the WP2 source-authority / canonical contract
design, which is **GitHub PR #3 — still open and unmerged**. This spike (PR #4) is **blocked by
PR #3**: it must not merge before PR #3 is independently accepted and merged, and after PR #3
merges this branch must be updated/rebased onto the resulting `main` before final review. No
finding from this spike may silently modify the WP2 contract before that review.

## Purpose

Per `docs/wp2-source-authority-and-canonical-contract.md` §9, this reduces technical uncertainty
around three questions, with evidence rather than assumption:

1. Does Health Connect's change-token / incremental-sync mechanism (`getChangesToken` /
   `getChanges`) exist and work at all?
2. When a source record is **edited**, does its upsertion carry the **same** record ID (so a sync
   can treat it as an update of the existing record) or a different one? And are **deletions**
   surfaced distinctly?
3. Given (1) and (2), what is the smallest correct sync contract Fusion can build on?

Diagnostic spike only: no persistent storage, no network services, no Withings OAuth, no Samsung
SDK, no production pipeline. All state lives in `SyncSpikeActivity`'s in-memory fields and is lost
on process death.

## Scope decisions that make the test valid

- **Single low-noise record type — `WeightRecord` only.** Steps and heart-rate records change
  automatically in the background and would contaminate the counts, making it impossible to
  attribute an observed change to Warwick's controlled action. Weight is manually
  create/edit/delete-able and does not tick on its own. The broader six-domain production question
  is deliberately kept out of this experiment. (If a source app cannot manually create/edit/delete
  weight, pick one alternative manually-controllable low-noise type and record why.)
- **Evidence-accurate counting — `first_seen_id` / `repeat_seen_id`, not "new" / "updated".** The
  Changes API does not label an upsertion as insert vs update. The spike can only observe whether a
  record ID has been seen before in this same in-memory session. An edit to a record that existed
  *before* the token was obtained appears for the first time in this session and is therefore
  first-seen despite being an update — so "first-seen" must never be reported as "new record".
  Only the controlled create-**then-edit-the-same-record** sequence gives the counts causal
  meaning, and that interpretation ("likely insert" / "likely update") is written only into the
  findings, never claimed by the app.
- **Every pull is drained.** "Check for changes" follows `nextChangesToken` until `hasMore=false`
  (or the token expires, or a repeated token is detected, or a defensive page cap is hit — the same
  guards proven in WP1/PR2 pagination). A single page is *not* treated as a complete pull, so
  earlier pages can't leak into the next experimental stage.
- **No permission-request race.** "Get changes token" first reads the granted permissions; it
  requests the token immediately only if `WeightRecord` read is already granted, otherwise it
  launches the permission UI and requests the token only from the result callback once permission
  is confirmed. `getChangesToken` is never called concurrently with the permission dialog, and
  `SecurityException` is caught and reported distinctly from generic failures.

## What this build can and cannot test

`currentToken` is an in-memory field only, by design. Three distinct continuity scopes:

- **Same-process repeated pulls** — supported; the primary thing this spike tests.
- **Activity recreation while the process stays alive** — potentially observable, not guaranteed,
  and not deliberately exercised or asserted.
- **Full app/process close and reopen** — token continuity **cannot** be tested; closing discards
  `currentToken` and reopening always starts a fresh token. This is **unresolved and outside this
  spike's proven capability**, and no persistent storage was added to work around it.

## Controlled device-test methodology

1. Install the corrected build in place (in-place update).
2. Open the standalone **Incremental Sync Spike** launcher (separate icon from the main app).
3. Grant the single `WeightRecord` read permission if requested.
4. Tap **Get changes token** — the token is acquired only after permission is confirmed.
5. Create **one clearly synthetic** weight record in the named source app (use an obviously
   non-real value and remove it after testing).
6. Return to the spike, tap **Check for changes** (it drains to `has_more=false`), and record the
   complete output (pages drained, upsertions, first-seen, repeat-seen, deletions, final has-more,
   token-expiry, stopped reason).
7. **Edit that exact same record** in the source app.
8. Tap **Check for changes** again (drains fully); record whether the **same record ID reappeared**
   (repeat-seen) or a new ID appeared — note the source-app behaviour either way, without claiming
   the Changes API failed.
9. **Delete that exact same record.**
10. Tap **Check for changes** again (drains fully); record the deletion output.
11. Tap **Check for changes** once more with no intervening controlled action, to observe
    empty-delta behaviour.
12. Do **not** claim cross-process (restart) token continuity from this build.
13. Note any unrelated background changes rather than attributing them to the controlled action.

## Findings (accepted device evidence)

Warwick ran the controlled device test and accepted the evidence as sufficient for the MVP.

- The corrected APK installed successfully as an in-place update; the existing WP1 diagnostic
  remained functional.
- A synthetic **99.9 kg** record entered through Withings appeared in Samsung Health (i.e. it
  reached Health Connect via the normal source path).
- The Changes API spike **observed the new weight record**: `upsertions=1`, `first_seen_id=1`,
  `pages_drained=1`, `changes_token_expired=false`.
- A later pull **observed the same record ID again**: `repeat_seen_id=1` — confirming that a
  repeat of the same record ID is surfaced through the token, as the classification relies on.
- Subsequent pulls returned **empty deltas cleanly**.

**Proven:** Health Connect exposes a working change-token / delta mechanism, and a Withings scale
measurement flows through Health Connect to a point where Fusion Health can read the weight record
and observe it via the Changes API. This is the MVP requirement.

### Explicitly non-blocking / out of scope for the current MVP

Editing historical scale readings; source-side deletion propagation; cross-process token
persistence; and exhaustive correction/reconciliation semantics are all out of scope and not
required for automatic-scale ingestion.

Deletion observation, recorded as required: *"Withings source deletion did not produce an observed
Health Connect deletion during the test window; this is not required for the current
automatic-scale ingestion use case."*

## Next step

The spike has served its purpose (reduce uncertainty). The next work item is a real, minimal
feature — a **Canonical Latest Weight Preview** — tracked separately, not a continuation of this
spike. No PR4c/PR4d sync-semantics work is authorised.
