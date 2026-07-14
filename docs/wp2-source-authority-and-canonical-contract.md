# WP2 / PR4 — Source Authority and Canonical Health Data Contract

Status: **Design draft — documentation only, not authorised for implementation.** No application code, Samsung SDK dependency, network access, or health-data storage is introduced by this document or its accompanying PR.

Carries forward, without reopening, the WP1 architecture-gate decision (Option C, 2026-07-13): Health Connect is the primary integration route; Samsung Health SDK extension remains deferred, not cancelled, unless one of WP1's five documented reconsideration triggers is met. Samsung-originated steps/sleep/heart-rate, MyFitnessPal nutrition, and Withings weight/body-composition currently all arrive through Health Connect, proven end-to-end on Warwick's device (BUILD-005/WP1/PR2, final accepted head `f72b658eb8978f3789a5c72d5cbd1fe2b1230d5d`, merge SHA `9b8eda1b3e2d1add0f871a5fa55a661718f074c4`).

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
| Weight | Proven populated via Health Connect | Confirmed duplicate-route risk with Withings-direct — see §4 |
| Body fat / body composition | Proven populated via Health Connect | Same duplicate-route risk as weight |
| Derived Fusion metrics | Not yet built | Rolling trends, adherence scores, etc. — always `record_kind: DERIVED`, never conflated with a raw source record |

Exercise sessions are explicitly out of scope for this contract, consistent with WP1/PR2's boundary (no `READ_EXERCISE`, no `ExerciseSessionRecord`) — adding them remains a scope change requiring separate authorisation, unrelated to whether PR3 is ever reconsidered.

---

## 2. Source authority rules

| Domain | Preferred authoritative producer | Ingestion route | Fallback source | Provenance fields retained | Duplicate / republished-copy treatment | Conflict-resolution rule | Fusion-calculated? |
|---|---|---|---|---|---|---|---|
| Steps / activity | Samsung Health (or whichever app the wearer uses) | Health Connect | None named yet | `source_application`, `health_connect_origin_package`, `record_kind` | Sum only records from `source_application`s that are genuine step-tracking apps; never sum Health Connect's own aggregation-layer origin as if it were an independent producer (§4) | Health Connect's own `AggregateRequest` result is authoritative for a daily total; raw per-record summation is diagnostic-only, never canonical | Rolling averages/trends: yes, labelled `DERIVED` |
| Sleep | The sleep-tracking app in use (e.g. Samsung Health) | Health Connect | None named yet | `source_application`, `record_kind` | One canonical session per source per night; overlapping sessions from two source apps are both retained with distinct `source_record_id`s, never merged into one — resolution is a WP5 concern (§4), not silently auto-picked here | Not yet defined — explicitly an unresolved question, see §8 | Sleep-consistency scores: yes, labelled `DERIVED` |
| Heart rate & physiology | The tracking app in use | Health Connect | None named yet | `source_application`, `record_kind` | Same as sleep: retain all, do not merge | Not yet defined — unresolved, see §8 | Resting-HR trend: yes, labelled `DERIVED` |
| Nutrition | MyFitnessPal | Health Connect | None named yet | `source_application`, `record_kind` (always `ORIGINAL` — MFP has no known second route) | N/A — no confirmed duplicate route for MFP nutrition (Foundry research brief §5: MFP's private developer API is separately gated and not a practical second route) | N/A | Macro-adherence score: yes, labelled `DERIVED` |
| Weight | **Withings direct API** (once WP3 exists) | Withings OAuth (WP3) today; **Health Connect only until WP3 ships** | Health Connect (interim, until WP3 ships) | `source_system`, `source_application`, `record_kind` | **Exclude any Android-sourced weight record once Withings-direct is ingesting the same person** — confirmed live Samsung↔Withings cross-sync partnership means the same reading can otherwise arrive twice (Foundry research brief §5, Withings' own support documentation, Medium-High confidence) | Withings-direct always wins when both exist for the same `measured_at` (± a tolerance window, see §4) | Rolling weight trend: yes, labelled `DERIVED` |
| Body fat / composition | Withings direct API (once WP3 exists) | Same as weight | Same as weight | Same as weight | Same exclusion rule as weight — confirmed overlap surface (Withings' own support article: "Samsung Health sync only syncs weight and body fat%") | Same as weight | Yes, labelled `DERIVED` |
| Derived Fusion metrics | Fusion itself | N/A (computed, not ingested) | N/A | `derived_from` (list of source `record_id`s), `calculation_version` | N/A | N/A | Always — this domain exists only to hold calculated values |

Two rows above are marked as having **no defined conflict-resolution rule yet** (sleep, heart rate/physiology overlaps between two source apps). This is deliberate: WP1 did not test multi-app overlap for these domains, and inventing a rule without evidence would violate the "no speculative metrics/rules" acceptance criterion already on file for PR4. See §8.

---

## 3. Identity and provenance

Minimum fields carried on every canonical record, beyond the domain value itself:

| Field | Purpose | Distinguishes |
|---|---|---|
| `source_application` | The installed app that wrote the underlying record (e.g. `com.sec.android.app.shealth`) | The human-facing data source |
| `health_connect_origin_package` | The Health Connect `dataOrigin.packageName` actually observed on the record | May equal `source_application`, or may be a Health Connect aggregation/phone package (e.g. WP1's observed `com.android.healthconnect.phone.*` origin) — **never treated as an independent human data source in its own right** (§4) |
| `originating_device` | Device identifier, where Health Connect or the source API exposes one | Distinguishes "phone-recorded" vs "watch-recorded" vs "scale-recorded" for the same person, where knowable — WP1 did not test this field's actual availability; treat as optional/nullable until confirmed |
| `record_kind` | One of `ORIGINAL`, `REPUBLISHED`, `DERIVED` | Original: written directly by the producing app. Republished: a copy that arrived via a second route (e.g. Samsung→Withings cross-sync landing in Health Connect). Derived: calculated by Fusion, never a raw reading |
| `ingested_at` | When Fusion's own pipeline read/stored the record | Pipeline-side timestamp, not the health event itself |
| `measured_at` (source event timestamp) | When the underlying health event actually occurred, per the source | The value WP2/WP3 domain logic reasons about — never conflate with `ingested_at` |
| `timezone_offset_at_measurement` | The UTC offset in effect at `measured_at`, captured at ingestion time | Needed because "today's steps" is a local-day-boundary concept (WP1's aggregate step total already had to solve this — `localDayStart()` in PR2 is the proven pattern, see §6) — never assume UTC midnight |
| `is_diagnostic_evidence` | `true` for anything captured as WP1-style diagnostic output, `false`/absent for genuine canonical pipeline data | Keeps WP1's diagnostic screenshots/exports from ever being mistaken for production canonical records if they're ever pasted into a ticket or doc |
| `updated_at` / `deleted_at` | Correction and deletion lifecycle | See §5 |

---

## 4. Deduplication and aggregation

Five specific failure modes, each with the rule that prevents it:

1. **Double-counting Samsung records republished through Health Connect.** Only sum raw records from a `source_application` that is a genuine step/activity-tracking app. Never sum records whose `health_connect_origin_package` is Health Connect's own aggregation/phone origin as if it were a second independent source — that origin does not represent a second human data source, only a restatement of data Health Connect already holds from elsewhere.
2. **Treating Health Connect's own package identity as an independent human-data source.** Directly related to (1): `health_connect_origin_package` is provenance metadata about *where Health Connect got the copy*, not a new source to authorise or sum independently. WP1's PR2 already surfaced this exact origin on Warwick's device and correctly reported it as "additional observed origin, source authority unresolved" rather than silently including or excluding it — this contract makes that same caution the permanent rule, not a one-off diagnostic caveat.
3. **Confusing record count with actual step total.** This was WP1/PR2's own defect, found and fixed on Warwick's device: a `StepsRecord` object count is not a step total (one record can span many steps). The canonical contract carries the same fix forward structurally — `record_count` (number of record objects) and any domain `value` (e.g. an aggregate step total) are always distinct, separately labelled fields, never the same number reused for two meanings.
4. **Combining overlapping intervals incorrectly.** For session-based domains (sleep), never merge two overlapping sessions from different `source_application`s into one record — retain both with distinct `source_record_id`s. Silent merging discards provenance and can produce a session that no single source app ever actually reported. A canonical "which session wins" rule is deliberately not specified yet (§2, §8) — retaining-both is the safe default until evidence justifies a specific merge rule.
5. **Silently replacing an authoritative Withings or MyFitnessPal record with a weaker copy.** Enforced by the source-authority table in §2: once Withings-direct ingestion exists (WP3), any Android-sourced weight/body-fat record for the same person is excluded outright, not "preferred against" — there is no ranking or scoring step that could accidentally let a weaker copy through. Until WP3 ships, Health Connect remains the only route for weight/body-fat, and no exclusion applies yet (there is nothing to exclude against).

**General aggregation principle carried forward from WP1's own proven fix:** where Health Connect provides an aggregate API for a metric (as it does for `StepsRecord.COUNT_TOTAL`), the aggregate result is authoritative for that metric's total. Raw record summation by this pipeline is never used to produce a canonical total when an aggregate exists — it is diagnostic-only. Whether Health Connect's aggregate itself correctly deduplicates the Samsung/Health-Connect-phone-origin overlap is **not proven** by WP1 and is carried into §8 as an explicit unresolved question, not assumed.

---

## 5. Historical depth and incremental sync

**What WP1 proved:** Health Connect pagination works correctly when implemented against the real SDK contract (BUILD-005/WP1/PR2's page-token defect and fix, final accepted head `f72b658`) — a `null`/omitted first-page token, following `pageToken` to a null/blank terminator, with defensive repeated-token and page-count guards. WP1's device test read multiple pages successfully (4 pages for one domain on Warwick's device) with no data loss.

**What remains unknown:**
- Health Connect's actual maximum retained history per record type — not tested by WP1, which queried `Instant.EPOCH` to now and simply received however much history existed.
- Behaviour of `pageToken` continuation across Health Connect app restarts, OS updates, or long time gaps between pipeline runs — untested.
- Whether Health Connect surfaces corrections to already-read historical records via a change/delta mechanism, or only via a fresh full re-read.

**Proposed lookback window:** initial full sync per domain reads all available history (matches WP1's proven approach — no artificial window imposed), since Health Connect has already shown it does not require an assumed window to paginate correctly.

**Pagination requirements:** carry forward WP1's exact pattern — nullable token starting `null`, blank-or-null next-token as the sole termination signal, repeated-token and page-count defensive guards, partial-result preservation on a later-page failure (all proven in `HealthConnectPagination.kt`/`HealthConnectRequests.kt`, BUILD-005/WP1/PR2).

**Sync cursor / watermark approach:** proposed, not yet implemented or tested — a per-domain `last_successful_measured_at` watermark, advanced only after a full page-set completes without a partial-failure, so a mid-sync failure re-reads from the last confirmed-complete watermark rather than an arbitrary retry point. This needs its own implementation PR to validate against real Health Connect behaviour before being treated as settled.

**Late-arriving and corrected-record handling:** not tested by WP1. Proposed contract-level behaviour (design only, unimplemented): a record with a `source_record_id` already seen updates the existing canonical record's `updated_at` and value rather than creating a duplicate; a genuinely new `source_record_id` for an already-synced time range is treated as late-arriving, not an error.

**Deletion/revocation considerations:** WP1 proved Health Connect permission revocation is handled safely at the read layer (`SecurityException` → `PERMISSION_DENIED` state, not a crash). What is not proven: whether Health Connect exposes record-level deletion events distinct from a permission revocation, and how a canonical pipeline should propagate a source-side deletion into `deleted_at` rather than simply stopping ingestion. Flagged as unresolved, §8.

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

  source_system: enum { HEALTH_CONNECT, WITHINGS_API, FUSION }  // WITHINGS_API reserved for WP3
  source_application: string | null     // e.g. "com.sec.android.app.shealth"; null for FUSION-sourced derived records
  health_connect_origin_package: string | null  // raw dataOrigin.packageName, per §3/§4 -- never used as a second source
  source_record_id: string | null       // the source's own record identifier, for correction/dedup matching
  originating_device: string | null     // optional/nullable, unconfirmed availability (§3)
  record_kind: enum { ORIGINAL, REPUBLISHED, DERIVED }

  derived_from: [record_id] | null      // only set when record_kind == DERIVED
  calculation_version: string | null    // only set when record_kind == DERIVED, so a later formula change is distinguishable

  is_diagnostic_evidence: boolean       // default false; true only for WP1-style diagnostic captures, per §3
}
```

**Worked synthetic examples** (all values fictitious, no real personal data):

```
// A Health-Connect-sourced steps total for one synthetic day.
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
  source_system: "HEALTH_CONNECT",
  source_application: "com.sec.android.app.shealth",
  health_connect_origin_package: "com.sec.android.app.shealth",
  source_record_id: null,               // aggregate result has no single backing record ID
  originating_device: null,             // unconfirmed availability, left null per §3
  record_kind: "ORIGINAL",
  derived_from: null,
  calculation_version: null,
  is_diagnostic_evidence: false
}

// A Withings-direct weight record (WP3, once it exists) that must exclude
// the Android-sourced republished copy of the same reading, per §2/§4.
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
  source_system: "WITHINGS_API",
  source_application: null,
  health_connect_origin_package: null,
  source_record_id: "withings_meas_98765",
  originating_device: "withings_scale_001",
  record_kind: "ORIGINAL",
  derived_from: null,
  calculation_version: null,
  is_diagnostic_evidence: false
}

// The excluded Android-sourced republished copy -- never ingested as canonical,
// shown here only to make the exclusion rule concrete.
{
  // NOT CREATED as a canonical record, per §2/§4's exclusion rule --
  // would otherwise have appeared as source_system: "HEALTH_CONNECT",
  // record_kind: "REPUBLISHED", same measured_at/value as chr_00456 above.
}

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
  source_system: "FUSION",
  source_application: null,
  health_connect_origin_package: null,
  source_record_id: null,
  originating_device: null,
  record_kind: "DERIVED",
  derived_from: ["chr_00456", "chr_00457", "chr_00458"],
  calculation_version: "weight-trend-v1",
  is_diagnostic_evidence: false
}
```

Raw source facts (`ORIGINAL`/`REPUBLISHED`) and calculated Fusion facts (`DERIVED`) are kept in the same table/shape for query simplicity, but `record_kind` and `derived_from` make every calculated value permanently identifiable as calculated — a `DERIVED` record can never be mistaken for a raw reading downstream.

---

## 7. Privacy and retention boundary

Explicitly separated, per the guardrail that this PR does not implement any of the latter two:

- **Current local diagnostic (WP1, shipped):** in-memory only, activity-lifetime scoped, no storage, no network, no retention — already implemented and device-proven. Unchanged by this document.
- **Future local canonical processing (this WP2 design, plus its eventual implementation PRs):** would introduce on-device or pipeline-side canonical records per §6's schema. **Not implemented by this PR.** Storage mechanism, encryption-at-rest approach, and retention duration are all open decisions requiring Warwick's explicit approval before any implementation PR proceeds.
- **Any future cloud storage (WP4+):** explicitly out of scope for WP2 entirely. This document does not propose a cloud backend, database, upload mechanism, or retention policy implementation — those remain WP4/WP5-era decisions, each requiring their own separate authorisation, consistent with `docs/plan.md`'s existing WP boundary structure.

Noted from the Foundry/Pax research brief as relevant background for whichever future PR does implement storage (not acted on here): health data is UK GDPR special-category data requiring an Article 9 condition; encryption in transit (TLS) and at rest are both explicit ICO expectations; OAuth tokens (once WP3 exists) should be stored server-side, encrypted, with minimum necessary scopes. These are flagged for the relevant future PR, not decided or implemented now.

---

## 8. Decision and uncertainty register

**Accepted decisions (carried forward from WP1, not reopened):**
- Health Connect is the primary Android integration route (Option C).
- Samsung Health SDK extension remains deferred, not cancelled.
- Samsung steps/sleep/heart-rate, MyFitnessPal nutrition, and (interim) Withings weight/body-composition all currently arrive through Health Connect.
- PR3 remains unauthorised unless a documented reconsideration trigger is met (see below).

**Evidence-backed assumptions (this document, backed by WP1 device evidence or Foundry research):**
- All six WP1-tested record types are genuinely readable via Health Connect on a real device (device-proven, not assumed).
- Health Connect's own aggregation/phone origin is a real, observed phenomenon, not a hypothetical (device-proven).
- The Samsung↔Withings cross-sync duplicate-route risk for weight/body-fat is real (Withings' own support documentation + trade press, Medium-High confidence per the Foundry brief) — heart rate/sleep/blood-pressure overlap via the same cross-sync is explicitly *not* confirmed and must not be assumed.
- MyFitnessPal's Health-Connect-synced nutrition data is meal-summary granularity, not per-food-item (confirmed directly from MyFitnessPal's own support documentation, per the Foundry brief).

**Unresolved questions (do not implement against an assumed answer):**
1. Conflict-resolution rule for overlapping sleep or heart-rate/physiology records from two different source apps (§2) — no evidence yet; retain-both is the interim safe default, not a resolution.
2. Whether Health Connect's own aggregate API (e.g. `StepsRecord.COUNT_TOTAL`) correctly deduplicates the Samsung/Health-Connect-phone-origin overlap, or whether it can itself double-count (§4) — not proven by WP1's device test, which only confirmed the aggregate produced a plausible total, not that it is provably correct against ground truth.
3. Health Connect's actual maximum retained history per domain, and its behaviour across long gaps between pipeline runs (§5).
4. Whether Health Connect exposes record-level deletions distinct from permission revocation (§5).
5. Field-level richness differences between Samsung's native SDK and the same domain via Health Connect (e.g. sleep-stage granularity) — explicitly unconfirmed either way per the Foundry research brief, and only relevant if PR3 is ever reconsidered.

**Reconsideration triggers for the Samsung SDK (carried forward verbatim from the WP1 architecture-gate decision, not modified here):**
1. A required Samsung metric is unavailable through Health Connect.
2. Health Connect data latency, fidelity, or history proves inadequate.
3. Samsung-only metadata or exercise detail becomes an accepted requirement.
4. Source-authority analysis (this document or its successors) demonstrates that Health Connect cannot provide a reliable canonical result.
5. A later architecture decision explicitly authorises comparative Samsung SDK work.

---

## 9. Implementation sequence

Proposed smallest-sensible follow-on sequence, none authorised by this document:

1. **PR4a — Canonical schema + in-memory reference implementation.** The `CanonicalHealthRecord` shape from §6 as actual types/data classes, plus pure-function mapping from Health Connect's existing WP1 record types into it. No storage, no network — an extension of WP1's existing diagnostic-only pattern, testable the same way WP1's pagination/aggregate logic was (real-SDK-class unit tests, synthetic fixtures).
2. **PR4b — Sync-cursor/watermark design validation.** A focused spike proving (or disproving) the §5 watermark proposal against real Health Connect behaviour on Warwick's device — still diagnostic-only, no persistence beyond the diagnostic screen, answering unresolved question 3 from §8 before any real pipeline commits to a specific incremental-sync design.
3. **PR4c — Deduplication rule validation.** A focused test proving whether Health Connect's own aggregate API actually deduplicates the observed phone-origin overlap (§8, unresolved question 2) — this is the single most consequential open question for whether §4's aggregation principle actually holds, and should be resolved before WP3 or any implementation PR builds on top of it.
4. **Only after 1–3:** a genuine WP2 implementation PR proposing actual canonical storage — which reopens the §7 privacy/retention conversation for Warwick's explicit approval, and is out of scope for this document.

This sequence deliberately keeps each step small and reviewable rather than bundling schema definition, sync design, and deduplication proof into one PR.
