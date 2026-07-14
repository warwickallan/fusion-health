# WP2 / PR4 — Source Authority and Canonical Health Data Contract

Status: **Design draft — documentation only, not authorised for implementation.** No application code, Samsung SDK dependency, network access, or health-data storage is introduced by this document or its accompanying PR.

Carries forward, without reopening, the WP1 architecture-gate decision (Option C, 2026-07-13): Health Connect is the primary integration route; the Samsung Health SDK extension (tracked as **WP1 planned PR3 — Samsung SDK comparison**, not to be confused with this repository's GitHub PR #3, which is this WP2 design document) remains deferred, not cancelled, unless one of WP1's five documented reconsideration triggers is met. Samsung-originated steps/sleep/heart-rate, MyFitnessPal nutrition, and Withings weight/body-composition currently all arrive through Health Connect, proven end-to-end on Warwick's device (BUILD-005/WP1/PR2, final accepted head `f72b658eb8978f3789a5c72d5cbd1fe2b1230d5d`, merge SHA `9b8eda1b3e2d1add0f871a5fa55a661718f074c4`).

All examples in this document use synthetic, round-number, or clearly fictitious values. No real personal health record from Warwick's device is reproduced here.

---

## 1. Canonical record domains

Seven domains, matching WP1's proven record types plus the two domains already named in `docs/plan.md`'s WP2 section:

| Domain | WP1 evidence | Notes |
|---|---|---|
| Steps / activity | Proven populated via Health Connect (PR2, device-accepted) | Distinguish record-count from aggregate total — see §4 |
| Sleep | Proven populated via Health Connect | Session-based, has start/end, not a point-in-time value |
| Heart rate & physiology | Proven populated via Health Connect | Point-in-time samples; physiology extensions (HRV, SpO2, etc.) out of scope until named a requirement |
| Nutrition | Proven populated via Health Connect (MyFitnessPal) | Meal-summary granularity only (confirmed: MyFitnessPal writes meal-level totals to Health Connect, not per-food-item detail — Foundry research brief §2) |
| Weight | Proven populated via Health Connect | Confirmed duplicate-route risk with Withings-direct — see §2/§4 |
| Body fat / body composition | Proven populated via Health Connect | Same duplicate-route risk as weight |
| Derived Fusion metrics | Not yet built | Rolling trends, adherence scores, etc. — always `record_kind: DERIVED`, never conflated with a raw source record |

Exercise sessions are explicitly out of scope for this contract, consistent with WP1/PR2's boundary (no `READ_EXERCISE`, no `ExerciseSessionRecord`) — adding them remains a scope change requiring separate authorisation, unrelated to whether **WP1 planned PR3** is ever reconsidered.

---

## 2. Source authority rules

**Note on producer identity:** the columns below describe *intended* authority, not a claim that Fusion can always prove who produced a given reading. §3 defines the actual provenance fields (`observed_writer_package`, `claimed_producer`, `inferred_producer`, `producer_confidence`, `republish_status`) that record what is actually known versus assumed for each record. A domain's "preferred authoritative producer" is a target for source-authority *rules*, not a label automatically applied to every record from that domain.

| Domain | Preferred authoritative producer | Ingestion route | Fallback source | Duplicate / republished-copy treatment | Conflict-resolution rule | Fusion-calculated? |
|---|---|---|---|---|---|---|
| Steps / activity | Whichever app the wearer actually uses for tracking (commonly Samsung Health) | Health Connect | None named yet | Sum only records whose `observed_writer_package` is a genuine step/activity-tracking app; never sum records whose `observed_writer_package` is Health Connect's own aggregation/phone package as if it were an independent producer (§4) | Health Connect's own `AggregateRequest` result is authoritative for a daily total; raw per-record summation is diagnostic-only, never canonical | Rolling averages/trends: yes, labelled `DERIVED` |
| Sleep | The sleep-tracking app in use | Health Connect | None named yet | One canonical session per source per night; overlapping sessions from two different `observed_writer_package`s are both retained with distinct `source_record_id`s, never merged into one | Not yet defined — explicitly an unresolved question, see §8 | Sleep-consistency scores: yes, labelled `DERIVED` |
| Heart rate & physiology | The tracking app in use | Health Connect | None named yet | Same as sleep: retain all, do not merge | Not yet defined — unresolved, see §8 | Resting-HR trend: yes, labelled `DERIVED` |
| Nutrition | MyFitnessPal | Health Connect | None named yet | No confirmed duplicate route for MFP nutrition (Foundry research brief §5: MFP's private developer API is separately gated and not a practical second route) | N/A | Macro-adherence score: yes, labelled `DERIVED` |
| Weight | Withings direct, for measurements proven or strongly attributable to that route (**planned direct Withings OAuth route once WP3 is separately authorised and implemented — not yet built**) | Health Connect today; Withings API once WP3 exists | Health Connect (until and alongside WP3) | **Suppression, not blanket exclusion — see the revised rule below.** A Health-Connect-sourced weight record is suppressed only when it is a confirmed or sufficiently high-confidence duplicate of a direct Withings record; unrelated Android-originated weight readings remain eligible canonical records with provenance preserved | Match candidates on source identity, metric type, value, unit, and measurement time within a documented (configurable, unfinalised) tolerance; a match found suppresses the Health-Connect copy; an ambiguous candidate is retained/quarantined for reconciliation, never silently deleted | Rolling weight trend: yes, labelled `DERIVED` |
| Body fat / composition | Same model as weight | Same as weight | Same as weight | Same suppression-not-exclusion rule as weight — confirmed overlap surface (Withings' own support article: "Samsung Health sync only syncs weight and body fat%") | Same as weight | Yes, labelled `DERIVED` |
| Derived Fusion metrics | Fusion itself | N/A (computed, not ingested) | N/A | N/A | N/A | Always — this domain exists only to hold calculated values |

Two rows above are marked as having **no defined conflict-resolution rule yet** (sleep, heart rate/physiology overlaps between two source apps). This is deliberate: WP1 did not test multi-app overlap for these domains, and inventing a rule without evidence would violate the "no speculative metrics/rules" acceptance criterion already on file for PR4. See §8.

### Revised Withings/Health-Connect weight and body-fat rule

The prior draft of this document proposed excluding *all* Android-sourced weight/body-fat records once Withings-direct ingestion exists. Independent review correctly identified that rule as too broad: it could discard legitimate readings from another scale or producer, manual entry, a clinical or other application source, Health Connect records with no Withings-side counterpart, or periods where the direct API is incomplete, delayed, or unavailable. The corrected principle:

- Withings direct is the preferred authority for measurements proven or strongly attributable to the Withings route.
- A Health Connect weight/body-composition record is suppressed **only** when it is a confirmed or sufficiently high-confidence duplicate of a direct Withings record for the same person.
- Matching considers source identity, metric type, value, unit, measurement time, and a documented tolerance window — not measurement time alone.
- Unrelated Android-originated readings (no matching Withings record) remain eligible canonical records, with their provenance preserved as observed.
- Ambiguous candidates (partial match, borderline tolerance) are retained or quarantined for reconciliation — never silently deleted.
- The tolerance value is explicitly configurable and unfinalised until validated against real paired data from both routes; no specific number is proposed here.

---

## 3. Identity and provenance

WP1 proved that Health Connect exposes a `dataOrigin.packageName` for each record. It did **not** prove that this package identifies the true original human-facing producer of a measurement, as opposed to an app that merely wrote or republished a copy of data that originated elsewhere (e.g. via the confirmed Samsung↔Withings cross-sync). The provenance model below distinguishes *what Fusion actually observed* from *what Fusion is inferring or has been explicitly told*, so the contract never claims a fact it cannot support.

| Field | Purpose | Notes |
|---|---|---|
| `observed_source_system` | The API route Fusion directly read the record from | `HEALTH_CONNECT` or `WITHINGS_API` (once WP3 exists) — a directly observed fact, never inferred |
| `observed_writer_package` | The package identifier Health Connect actually returned on the record (`dataOrigin.packageName`) | A directly observed fact. May be a genuine source app (e.g. `com.sec.android.app.shealth`) or Health Connect's own aggregation/phone package (WP1's observed `com.android.healthconnect.phone.*` origin) — this field alone does **not** establish who originally produced the measurement |
| `claimed_producer` | Producer identity supplied by a direct source API or other explicit, trustworthy metadata | Nullable. Populated only when a source system explicitly states the producer (e.g. Withings' own account/device metadata once WP3 exists) — never populated from a heuristic guess |
| `inferred_producer` | A producer identity inferred heuristically from `observed_writer_package` or other circumstantial signals | Nullable. Distinct from `claimed_producer` precisely because it is a guess, not a stated fact — e.g. "this record's writer package suggests Samsung Health produced it," which is a reasonable inference but not proof |
| `producer_confidence` | Confidence in `inferred_producer`, where set | One of `CONFIRMED`, `HIGH`, `MEDIUM`, `LOW`, `UNKNOWN`. `CONFIRMED` is reserved for cases backed by `claimed_producer`, not inference alone |
| `republish_status` | Whether this record is believed to be an original writer's own data or a republished copy of data that originated via a different route | One of `CONFIRMED_ORIGINAL`, `CONFIRMED_REPUBLISHED`, `SUSPECTED_REPUBLISHED`, `UNKNOWN`. A Health Connect record is **not** labelled `CONFIRMED_ORIGINAL` merely because a recognisable app (e.g. MyFitnessPal or Samsung Health) is the observed writer — that alone is insufficient evidence. Default is `UNKNOWN` unless positive evidence exists |
| `originating_device` | Device identifier, where an API exposes one | Nullable. WP1 did not test this field's actual availability on any route — treat as unconfirmed until a real API response is observed to contain it |
| `record_kind` | `RAW` or `DERIVED` | `RAW`: read from a source system, carries the provenance fields above. `DERIVED`: calculated by Fusion, carries `derived_from`/`calculation_version` instead (§6) |
| `ingested_at` | When Fusion's own pipeline read/stored the record | Pipeline-side timestamp, not the health event itself |
| `measured_at` | When the underlying health event actually occurred, per the source | Never conflated with `ingested_at` |
| `timezone_offset_at_measurement` | The UTC offset in effect at `measured_at`, captured at ingestion time | Needed because "today's steps" is a local-day-boundary concept (WP1's aggregate step total already had to solve this — `localDayStart()` in PR2 is the proven pattern, see §6) — never assume UTC midnight |
| `updated_at` / `deleted_at` | Correction and deletion lifecycle | See §5 |

Where provenance cannot be established, the record still preserves every fact that *was* directly observed (`observed_source_system`, `observed_writer_package`, the value itself) and reports the rest as `UNKNOWN` — it is never silently upgraded to a stronger claim than the evidence supports.

**Diagnostic evidence is not part of this schema.** WP1's diagnostic screenshots, copied reports, and test-run output are never canonical health records, even conditionally. See §6 for the separate `DiagnosticEvidenceRecord` structure and §7 for why the two must not share a datastore or schema.

---

## 4. Deduplication and aggregation

Five specific failure modes, each with the rule that prevents it:

1. **Double-counting Samsung records republished through Health Connect.** Only sum raw records whose `observed_writer_package` is a genuine step/activity-tracking app. Never sum records whose `observed_writer_package` is Health Connect's own aggregation/phone package as if it were a second independent source — that package does not represent a second human data source, only a restatement of data Health Connect already holds from elsewhere.
2. **Treating Health Connect's own package identity as an independent human-data source.** Directly related to (1): `observed_writer_package` is provenance metadata about *where Health Connect got the copy from*, not a new source to authorise or sum independently. WP1's PR2 already surfaced this exact origin on Warwick's device and correctly reported it as "additional observed origin, source authority unresolved" rather than silently including or excluding it — this contract makes that same caution the permanent rule, not a one-off diagnostic caveat.
3. **Confusing record count with actual step total.** This was WP1/PR2's own defect, found and fixed on Warwick's device: a `StepsRecord` object count is not a step total (one record can span many steps). The canonical contract carries the same fix forward structurally — `record_count` (number of record objects, where reported) and any domain `value` (e.g. an aggregate step total) are always distinct, separately labelled fields, never the same number reused for two meanings.
4. **Combining overlapping intervals incorrectly.** For session-based domains (sleep), never merge two overlapping sessions from different `observed_writer_package`s into one record — retain both with distinct `source_record_id`s. Silent merging discards provenance and can produce a session that no single source app ever actually reported. A canonical "which session wins" rule is deliberately not specified yet (§2, §8) — retaining both is the safe default until evidence justifies a specific merge rule.
5. **Silently replacing an authoritative Withings or MyFitnessPal record with a weaker copy.** Enforced by §2's revised weight/body-fat rule: a Health-Connect-sourced record is suppressed only on a confirmed or high-confidence match against a direct Withings record (source identity, metric type, value, unit, time within tolerance) — never on measurement time alone, and never as a blanket exclusion of an entire route. Ambiguous candidates are quarantined for reconciliation, not discarded.

**General aggregation principle carried forward from WP1's own proven fix:** where Health Connect provides an aggregate API for a metric (as it does for `StepsRecord.COUNT_TOTAL`), the aggregate result is authoritative for that metric's total. Raw record summation by this pipeline is never used to produce a canonical total when an aggregate exists — it is diagnostic-only. **Whether Health Connect's aggregate itself correctly deduplicates the Samsung/Health-Connect-phone-origin overlap is not proven by WP1** and remains the single most consequential unresolved question in §8 — it is not assumed to be correct.

---

## 5. Historical depth and incremental sync

**What WP1 proved:** Health Connect pagination works correctly when implemented against the real SDK contract (BUILD-005/WP1/PR2's page-token defect and fix, final accepted head `f72b658`) — a `null`/omitted first-page token, following `pageToken` to a null/blank terminator, with defensive repeated-token and page-count guards. WP1's device test read multiple pages successfully (4 pages for one domain on Warwick's device) with no data loss.

**What remains unknown:**
- Health Connect's actual maximum retained history per record type — not tested by WP1, which queried `Instant.EPOCH` to now and simply received however much history existed.
- Whether Health Connect (or, once WP3 exists, Withings) exposes any official change-token or delta mechanism at all, and if so its exact semantics.
- Behaviour of `pageToken` continuation across Health Connect app restarts, OS updates, or long time gaps between pipeline runs — untested.
- Whether Health Connect surfaces corrections to already-read historical records, or deletions, via any mechanism Fusion can detect.

**Proposed lookback window:** initial full sync per domain reads all available history (matches WP1's proven approach — no artificial window imposed), since Health Connect has already shown it does not require an assumed window to paginate correctly.

**Pagination requirements:** carry forward WP1's exact pattern — nullable token starting `null`, blank-or-null next-token as the sole termination signal, repeated-token and page-count defensive guards, partial-result preservation on a later-page failure (all proven in `HealthConnectPagination.kt`/`HealthConnectRequests.kt`, BUILD-005/WP1/PR2).

### Incremental-sync strategy (revised)

**The prior draft of this document proposed a per-domain `last_successful_measured_at` watermark as the sync-correctness mechanism. That proposal is REJECTED / SUPERSEDED by this revision** — independent review correctly identified that `measured_at` is the health-event timestamp, not a reliable source-change cursor, and a pure measured-time watermark can miss late-arriving records, retrospective corrections, source-side edits to an older measurement, and records restored after a sync interruption.

Revised design:

1. **An official SDK change-token or delta mechanism is preferred where one exists**, and must be investigated as a focused spike (§9) before any pipeline design relies on it. No change-token behaviour is treated as accepted architecture until that investigation confirms its actual availability and semantics against a real device/API — this document does not assume one exists.
2. **If no reliable delta mechanism is available**, the fallback design is:
   - an overlapping rolling reread window (re-reading a trailing period on every sync run, not just forward from a cursor);
   - idempotent upsert keyed on stable source identity (`observed_source_system` + `source_record_id`) where a stable ID is available;
   - a configurable, explicitly triggerable reconciliation/full-rescan route, for recovering from any gap the rolling window itself might miss;
   - explicit handling for records that have no stable source ID at all (e.g. an aggregate result with no backing record identifier) — these cannot be idempotently upserted by ID and need a documented alternative (e.g. always-replace-for-the-period), not silently dropped or silently duplicated.
3. **A measurement-time watermark may be used only as an optimisation boundary** — to bound how far back the rolling reread window needs to look by default — always combined with the overlap and reconciliation mechanisms above, **never as the sole correctness mechanism**.
4. The next implementation sequence (§9) must explicitly validate: change-token availability and semantics; correction detection; deletion detection; late-arriving records; and behaviour across app restarts and long gaps between pipeline runs — none of these are assumed correct by this design.

**Deletion/revocation considerations:** WP1 proved Health Connect permission revocation is handled safely at the read layer (`SecurityException` → `PERMISSION_DENIED` state, not a crash). What is not proven: whether Health Connect exposes record-level deletion events distinct from a permission revocation, and how a canonical pipeline should propagate a source-side deletion into `deleted_at` rather than simply stopping ingestion. Flagged as unresolved, §8, and explicitly part of the next spike's validation scope (§9).

---

## 6. Canonical schema proposal

```
CanonicalHealthRecord {
  record_id: string                    // Fusion-assigned, stable, unique
  metric_type: enum {
    STEPS_TOTAL, SLEEP_SESSION, HEART_RATE_SAMPLE, NUTRITION_MEAL_SUMMARY,
    WEIGHT, BODY_FAT_PERCENT, DERIVED_TREND  // extend only against an accepted new domain, per §1
  }
  value: number | structured-value      // shape depends on metric_type; sleep/nutrition are structured, not scalar
  unit: string                          // e.g. "count", "kg", "percent", "bpm" -- never implied, always explicit
  measured_at: timestamp                // source event time
  timezone_offset_at_measurement: string // e.g. "+10:00", captured at ingestion, per §3
  ingested_at: timestamp                // pipeline-side, per §3
  updated_at: timestamp | null
  deleted_at: timestamp | null

  record_kind: enum { RAW, DERIVED }

  // --- Populated only when record_kind == RAW ---
  observed_source_system: enum { HEALTH_CONNECT, WITHINGS_API } | null  // WITHINGS_API reserved for WP3
  observed_writer_package: string | null   // raw dataOrigin.packageName, per §3/§4 -- never treated as a second source on its own
  claimed_producer: string | null          // only from explicit source-API/metadata, never inferred (§3)
  inferred_producer: string | null         // heuristic guess, distinct from claimed_producer (§3)
  producer_confidence: enum { CONFIRMED, HIGH, MEDIUM, LOW, UNKNOWN } | null
  republish_status: enum { CONFIRMED_ORIGINAL, CONFIRMED_REPUBLISHED, SUSPECTED_REPUBLISHED, UNKNOWN } | null
  source_record_id: string | null       // the source's own record identifier, for correction/dedup matching
  originating_device: string | null     // nullable, unconfirmed API availability (§3)

  // --- Populated only when record_kind == DERIVED ---
  derived_from: [record_id] | null
  calculation_version: string | null    // so a later formula change is distinguishable
}
```

```
// Separate structure -- diagnostic evidence is never a CanonicalHealthRecord (§7).
DiagnosticEvidenceRecord {
  evidence_id: string
  captured_at: timestamp
  build_identifier: string              // e.g. the WP1 release tag the evidence was captured against
  test_identifier: string | null        // e.g. "PR2 fifth device test"
  redacted_output_reference: string     // pointer to the redacted diagnostic text/report, not the raw report itself
  retention_classification: enum { EPHEMERAL, ARCHIVED_EVIDENCE }
}
```

**Worked synthetic examples** (all values fictitious, no real personal data):

```
// A Health-Connect-sourced steps total for one synthetic day. The writer package
// is a recognisable tracking app, but republish_status is still UNKNOWN by default --
// being a recognisable app is not, on its own, evidence of original-vs-republished status.
{
  record_id: "chr_00123",
  metric_type: "STEPS_TOTAL",
  value: 8000,
  unit: "count",
  measured_at: "2099-01-01T00:00:00+10:00",   // represents the local day, per §3
  timezone_offset_at_measurement: "+10:00",
  ingested_at: "2099-01-02T03:00:00Z",
  updated_at: null,
  deleted_at: null,
  record_kind: "RAW",
  observed_source_system: "HEALTH_CONNECT",
  observed_writer_package: "com.sec.android.app.shealth",
  claimed_producer: null,
  inferred_producer: "Samsung Health",
  producer_confidence: "MEDIUM",
  republish_status: "UNKNOWN",
  source_record_id: null,               // aggregate result has no single backing record ID
  originating_device: null,             // unconfirmed availability, left null per §3
  derived_from: null,
  calculation_version: null
}

// A Withings-direct weight record (once WP3 exists). claimed_producer is populated
// because Withings' own API states the device identity explicitly -- not inferred.
{
  record_id: "chr_00456",
  metric_type: "WEIGHT",
  value: 70.0,
  unit: "kg",
  measured_at: "2099-01-01T07:15:00+10:00",
  timezone_offset_at_measurement: "+10:00",
  ingested_at: "2099-01-01T07:20:00Z",
  updated_at: null,
  deleted_at: null,
  record_kind: "RAW",
  observed_source_system: "WITHINGS_API",
  observed_writer_package: null,
  claimed_producer: "withings_scale_001",
  inferred_producer: null,
  producer_confidence: "CONFIRMED",
  republish_status: "CONFIRMED_ORIGINAL",
  source_record_id: "withings_meas_98765",
  originating_device: "withings_scale_001",
  derived_from: null,
  calculation_version: null
}

// A candidate Health-Connect weight record matched against chr_00456 above and
// suppressed per §2/§4's revised rule -- shown to make the suppression concrete.
// This record is NOT created as a canonical record; the match evaluation and its
// outcome are what the pipeline would log, not a persisted CanonicalHealthRecord.
//   candidate: observed_source_system=HEALTH_CONNECT, value=70.0kg,
//              measured_at within tolerance of chr_00456's measured_at
//   match_result: SUPPRESSED (high-confidence duplicate of chr_00456)

// A Fusion-calculated derived trend -- always distinguishable from raw source data.
{
  record_id: "chr_00789",
  metric_type: "DERIVED_TREND",
  value: { trend: "stable", window_days: 7, delta_kg: 0.1 },
  unit: "kg",
  measured_at: "2099-01-07T00:00:00Z",
  timezone_offset_at_measurement: "+00:00",
  ingested_at: "2099-01-07T00:05:00Z",
  updated_at: null,
  deleted_at: null,
  record_kind: "DERIVED",
  observed_source_system: null,
  observed_writer_package: null,
  claimed_producer: null,
  inferred_producer: null,
  producer_confidence: null,
  republish_status: null,
  source_record_id: null,
  originating_device: null,
  derived_from: ["chr_00456", "chr_00457", "chr_00458"],
  calculation_version: "weight-trend-v1"
}
```

`record_kind` and `derived_from` make every calculated value permanently identifiable as calculated — a `DERIVED` record can never be mistaken for a raw reading downstream. `republish_status` and `producer_confidence` make every raw record's provenance strength explicit rather than implied by which fields happen to be populated.

---

## 7. Privacy and retention boundary

Explicitly separated, per the guardrail that this PR does not implement any of the latter two:

- **Current local diagnostic (WP1, shipped):** in-memory only, activity-lifetime scoped, no storage, no network, no retention — already implemented and device-proven. Unchanged by this document. Its output, if ever captured as evidence, belongs to the separate `DiagnosticEvidenceRecord` structure (§6), never to `CanonicalHealthRecord` — the two are structurally distinct so a screenshot or exported diagnostic report can never be mistaken for, or accidentally merged with, production canonical data.
- **Future local canonical processing (this WP2 design, plus its eventual implementation PRs):** would introduce on-device or pipeline-side `CanonicalHealthRecord`s per §6's schema. **Not implemented by this PR.** Storage mechanism, encryption-at-rest approach, and retention duration are all open decisions requiring Warwick's explicit approval before any implementation PR proceeds.
- **Any future cloud storage (WP4+):** explicitly out of scope for WP2 entirely. This document does not propose a cloud backend, database, upload mechanism, or retention policy implementation — those remain WP4/WP5-era decisions, each requiring their own separate authorisation, consistent with `docs/plan.md`'s existing WP boundary structure.

Noted from the Foundry/Pax research brief as relevant background for whichever future PR does implement storage (not acted on here): health data is UK GDPR special-category data requiring an Article 9 condition; encryption in transit (TLS) and at rest are both explicit ICO expectations; OAuth tokens (once WP3 exists) should be stored server-side, encrypted, with minimum necessary scopes. These are flagged for the relevant future PR, not decided or implemented now.

---

## 8. Decision and uncertainty register

**Accepted decisions (carried forward from WP1, not reopened):**
- Health Connect is the primary Android integration route (Option C).
- The Samsung Health SDK extension (WP1 planned PR3) remains deferred, not cancelled.
- Samsung steps/sleep/heart-rate, MyFitnessPal nutrition, and (interim) Withings weight/body-composition all currently arrive through Health Connect.
- WP1 planned PR3 remains unauthorised unless a documented reconsideration trigger is met (see below).

**Evidence-backed assumptions (this document, backed by WP1 device evidence or Foundry research):**
- All six WP1-tested record types are genuinely readable via Health Connect on a real device (device-proven, not assumed).
- Health Connect's own aggregation/phone origin is a real, observed phenomenon, not a hypothetical (device-proven).
- The Samsung↔Withings cross-sync duplicate-route risk for weight/body-fat is real (Withings' own support documentation + trade press, Medium-High confidence per the Foundry brief) — heart rate/sleep/blood-pressure overlap via the same cross-sync is explicitly *not* confirmed and must not be assumed.
- MyFitnessPal's Health-Connect-synced nutrition data is meal-summary granularity, not per-food-item (confirmed directly from MyFitnessPal's own support documentation, per the Foundry brief).

**Unresolved questions (do not implement against an assumed answer):**
1. Conflict-resolution rule for overlapping sleep or heart-rate/physiology records from two different source apps (§2) — no evidence yet; retain-both is the interim safe default, not a resolution.
2. Whether Health Connect's own aggregate API (e.g. `StepsRecord.COUNT_TOTAL`) correctly deduplicates the Samsung/Health-Connect-phone-origin overlap, or whether it can itself double-count (§4) — not proven by WP1's device test, which only confirmed the aggregate produced a plausible total, not that it is provably correct against ground truth. **This is the single most consequential open question in this document.**
3. Whether Health Connect or Withings expose any official change-token/delta mechanism, and its exact semantics (§5) — the incremental-sync design's preferred path is unvalidated until this is answered.
4. Health Connect's actual maximum retained history per domain, and its behaviour across long gaps between pipeline runs (§5).
5. Whether Health Connect exposes record-level deletions distinct from permission revocation (§5).
6. The exact tolerance (time window, and whether value tolerance is also needed) for matching a Health-Connect weight/body-fat record against a direct Withings record (§2) — deliberately left unfinalised pending real paired data.
7. Field-level richness differences between Samsung's native SDK and the same domain via Health Connect (e.g. sleep-stage granularity) — explicitly unconfirmed either way per the Foundry research brief, and only relevant if WP1 planned PR3 is ever reconsidered.

**Reconsideration triggers for the Samsung SDK / WP1 planned PR3 (carried forward verbatim from the WP1 architecture-gate decision, not modified here):**
1. A required Samsung metric is unavailable through Health Connect.
2. Health Connect data latency, fidelity, or history proves inadequate.
3. Samsung-only metadata or exercise detail becomes an accepted requirement.
4. Source-authority analysis (this document or its successors) demonstrates that Health Connect cannot provide a reliable canonical result.
5. A later architecture decision explicitly authorises comparative Samsung SDK work.

---

## 9. Implementation sequence

Proposed smallest-sensible follow-on sequence, none authorised by this document:

1. **PR4a — Canonical schema + in-memory reference implementation.** The `CanonicalHealthRecord` and `DiagnosticEvidenceRecord` shapes from §6 as actual types/data classes, plus pure-function mapping from Health Connect's existing WP1 record types into the canonical shape (populating `observed_source_system`/`observed_writer_package` directly, leaving `claimed_producer`/`inferred_producer`/`producer_confidence`/`republish_status` at their honest default — `UNKNOWN` — until a later PR adds real inference logic). No storage, no network — an extension of WP1's existing diagnostic-only pattern, testable the same way WP1's pagination/aggregate logic was (real-SDK-class unit tests, synthetic fixtures).
2. **PR4b — Incremental-sync spike.** A focused investigation, against Health Connect (and, if reachable, general research into Withings' API) proving or disproving: whether an official change-token/delta mechanism exists and its semantics; whether corrections and deletions are detectable at all; behaviour across app restarts and long gaps. Still diagnostic-only, no persistence beyond the diagnostic screen. This resolves §8's unresolved questions 3–5 before any real pipeline commits to a specific sync design, and validates or further revises §5's fallback design.
3. **PR4c — Deduplication rule validation.** A focused test proving whether Health Connect's own aggregate API actually deduplicates the observed phone-origin overlap (§8, unresolved question 2) — the single most consequential open question for whether §4's aggregation principle actually holds, and should be resolved before WP3 or any implementation PR builds on top of it.
4. **PR4d — Withings-match tolerance validation.** Once WP3 exists and real paired Withings/Health-Connect weight data is available, validate and finalise the matching tolerance from §2/§8's unresolved question 6 against real (not synthetic) paired readings, under whatever privacy-safe evaluation process is appropriate at that time.
5. **Only after 1–4:** a genuine WP2 implementation PR proposing actual canonical storage — which reopens the §7 privacy/retention conversation for Warwick's explicit approval, and is out of scope for this document.

This sequence deliberately keeps each step small and reviewable rather than bundling schema definition, sync design, and deduplication proof into one PR.
