# Fusion Health — Build and Delivery Flow

Status: WP0 passed for PR1 scope (see README). **PR1 complete** — cloud build, permanent release signing, and in-place update all proven on Warwick's device. PR2/PR3 not yet authorised.

## Core delivery principle

Fusion Health is developed without requiring Warwick to install Android Studio, Java, Gradle, Samsung tooling, or any `.exe` files on his work computer.

```text
Larry writes and reviews source code
            ↓
GitHub repository
            ↓
GitHub Actions cloud build
            ↓
Signed test APK
            ↓
Warwick downloads APK directly to Galaxy phone
            ↓
Warwick installs, grants permissions and runs test
            ↓
Diagnostic report returned to Larry
```

Warwick's work laptop is not part of the Android development or testing environment.

---

## BUILD-005 — Personal Health Data Integration and Intelligence

**Governance note:** this idea converged in Foundry as `IDEA-005` and entered production as `BUILD-005`, tracked in ClickUp under **Fusion 247 MyPKA** (production delivery), not Fusion 247 Foundry (exploration/convergence). Foundry retains only a concise handoff record for this idea. PR1's branch (`idea-005/wp1/android-cloud-build`) is kept as-is — a historical exception, since the PR was already open and fully tested before this naming convention took effect. Future branches use `build-005/wpX/...`.

### Desired outcome

Create a secure personal health-data layer for Fusion that can eventually combine:

- Withings weight and body-composition data
- Samsung Health and Galaxy Watch data
- MyFitnessPal nutrition data through Health Connect
- Dashboards, trends and contextual Fusion briefings

The work proceeds through small evidence-led work packages. Only the currently authorised work package should consume build attention.

---

## WP0 — Capability, dependency and cloud-build preflight

**Status: passed for PR1 scope.**

- Java 21 available, exceeds Samsung's 17+ requirement (moot for cloud build — GitHub Actions provides its own toolchain).
- Local Android SDK not installed in the research environment — not a blocker; build moves entirely to GitHub Actions (`actions/setup-java` + standard Android cmdline-tools install).
- Gradle 8.14.3 confirmed available; AGP 8.5–8.7 range compatible.
- Samsung Health Data SDK download/docs: `developer.samsung.com` blocked at the research environment's egress proxy — unresolved, only blocks PR3.
- SDK licence/redistribution terms: unverified, same blocker.
- Signing key: **configured and proven.** One release keystore generated, stored as encrypted GitHub Actions secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`), reused across builds so updates install in place — confirmed by Build A → Build B (see PR1 status below). **Durable backup remains Warwick's ongoing operational responsibility:** losing this keystore breaks in-place updates for all future builds permanently.
- Delivery mechanism: GitHub prerelease with the raw `.apk` as a release asset (preferred over a bare Actions artifact — avoids zip-extraction friction on Android). **Note:** this is only actually private while the repository itself is private — the repo is currently public for build purposes, so releases are public too regardless of "prerelease" labelling.
- Diagnostic return path: on-screen copy/export, no backend, no laptop connection needed.

Full preflight and correction-memo trail: `Deliverables/2026-07-13-health-data-pipeline-source-feasibility.md` in `warwickallan/fusion247pka` (held uncommitted there).

**Gate:** PR1 is clear to proceed on Java/Gradle/GHA/signing grounds. Samsung SDK licensing remains open and only gates PR3.

---

## WP1 — Comparative Android diagnostic

### Purpose

Determine whether Health Connect alone provides sufficient information or whether the Samsung Health Data SDK adds materially useful records or richer fields.

### Boundaries

Read-only, local-only, no Fusion backend, no network upload, no database, no background synchronisation, no permanent health-data retention, no production user interface, no work-laptop dependency. Copy/Export diagnostic function only.

### PR1 — Android scaffold and cloud APK pipeline

Branch: `idea-005/wp1/android-cloud-build`

Scope: this repo (currently public for build purposes, to be flipped to private before production), minimal Kotlin Android app, Gradle wrapper + reproducible build config, GitHub Actions workflow, signed APK compiled on a GitHub-hosted runner, published as a downloadable build artefact/prerelease, simple version/diagnostic-status screen. No health permissions or health-data access yet.

**Status: COMPLETE.** Cloud build pipeline proven end-to-end — Actions run succeeded (SDK setup, Gradle provisioning, lint, unit tests, signed APK build, artifact upload, prerelease publish), and Warwick confirmed the resulting APK installs and launches cleanly on his Galaxy phone with no health permissions requested.

**Release signing — configured and proven:**
- **Build A** (`0.2.0-wp1-build-a`, versionCode 2): first release-signed build. Release: [wp1-diagnostic-8](https://github.com/warwickallan/fusion-health/releases/tag/wp1-diagnostic-8). Warwick uninstalled the earlier debug-signed app, installed Build A fresh, and confirmed the version displayed correctly.
- **Build B** (`0.3.0-wp1-build-b`, versionCode 3): second release-signed build, same signing identity. Release: [wp1-diagnostic-11](https://github.com/warwickallan/fusion-health/releases/tag/wp1-diagnostic-11). Warwick installed it **directly over Build A without uninstalling** — the in-place update succeeded, confirmed by the app reporting `0.3.0-wp1-build-b (3)` afterward.

Acceptance — all met: Actions completes successfully; automated tests/lint pass; signed APK produced; Warwick downloads and installs directly to Galaxy phone; no `.exe`/dev tool installed on the work machine; a later build updates the installed app using the same signing identity (proven by Build A → Build B).

### PR2 — Health Connect baseline

Branch: `build-005/wp1/health-connect-baseline`

Scope: detect Health Connect availability; request only required read permissions; read a small sample of sleep, steps, heart rate, nutrition; identify source-application metadata where available; display record type/timestamp/value/unit/source/significant fields; copy/export diagnostic output; no retention beyond the diagnostic session.

Requires a lightweight Vex review before shipping — first point real personal health data is touched, even transiently.

Phone actions required from Warwick: install the APK; grant Health Connect permissions; run the diagnostic; confirm whether MyFitnessPal nutrition appears; return the exported text/report.

Acceptance: a genuine Health Connect record appears; record provenance visible where supported; MyFitnessPal nutrition classified as available-and-populated / available-but-empty / unavailable / permission-blocked; diagnostic output returnable without a laptop connection.

### PR3 — Samsung Health Data SDK comparison

Branch: `build-005/wp1/samsung-health-comparison`

Scope: integrate the Samsung Health Data SDK; connect to Samsung Health in developer mode; request read-only permission; compare overlapping data through both routes (sleep, steps/activity, heart rate); inspect Samsung-specific availability (Energy Score, activity summary, sleep-apnoea records, irregular heart-rhythm notifications); classify each as populated / supported-but-empty / unsupported / unavailable-on-device / permission-denied; extend the export report with a route-by-route comparison.

Blocked on: Samsung SDK artifact + licence terms (WP0 open item — needs Warwick to obtain, or the research sandbox's network policy to allow developer.samsung.com; not a blocker for this repo's own build once the artifact is supplied).

Phone actions required from Warwick: ensure Samsung Health meets the required version; enable Samsung Health developer mode; install the new APK over the previous build; grant Samsung Health permissions; run the comparison; return the exported diagnostic. USB debugging should not be required for the normal test path — optional troubleshooting only.

Acceptance table:

| Question | Evidence required |
|---|---|
| Does each route expose sleep? | Actual record and fields |
| Does each route expose steps/activity? | Actual record and fields |
| Does each route expose heart rate? | Actual record and fields |
| Does Samsung expose richer fields? | Field-level comparison |
| Does Energy Score appear? | Populated, empty or unavailable |
| Are Samsung-exclusive clinical records present? | Explicit availability result |
| What historical depth is readable? | Earliest and latest returned dates |
| Where could duplicates arise? | Matching timestamps/source evidence |

### WP1 architecture gate

After Warwick completes the device test, record one explicit decision:

- **Option A — Health Connect only.** Select when Samsung adds little meaningful value.
- **Option B — Dual adapter.** Select when Samsung provides valuable unique or richer records. Health Connect handles third-party Android data; Samsung SDK handles deliberately selected Samsung-native data; overlapping records have an explicit authority rule.
- **Option C — Health Connect first, Samsung extension later.** Select when Samsung appears useful but not currently worth the added dependency.

Do not dynamically ingest whichever version appears "richer." Authority should be deterministic.

---

## WP2 — Source authority and canonical contract

### PR4 — Architecture decision and canonical health contract

Document: selected Android route; authority per metric; prohibited duplicate paths; units and timestamp conventions; provenance; corrections and deletions; minimal privacy and retention rules.

Initial likely authority model:

| Metric | Proposed authority |
|---|---|
| Weight/body composition | Withings direct API |
| MyFitnessPal nutrition | Health Connect |
| Samsung sleep and physiology | WP1-selected route |
| Steps/activity | WP1-selected route |
| Samsung-exclusive metrics | Samsung SDK, if retained |
| Derived trends | Fusion, labelled calculated |

Canonical record fields: `record_id`, `metric_type`, `value`, `unit`, `measured_at`, `source_system`, `source_application`, `source_record_id`, `ingested_at`, `updated_at`, `deleted_at`.

Confirmed duplicate-route risk to design against: a live Samsung–Withings cross-sync partnership means weight/body-fat can arrive via both Withings-direct and Samsung Health/Health Connect if both are ingested — exclude Android-sourced weight when Withings-direct is authoritative.

Acceptance: Warwick approves the authority map; weight arriving through Android is explicitly excluded if Withings-direct is authoritative; canonical examples validate against the proposed contract; no speculative metrics added merely for future completeness.

---

## WP3 — Withings cloud vertical slice

Independent of the Android application.

- **PR5 — Withings OAuth and measurement retrieval.** OAuth flow; secure token handling; refresh; retrieve selected weight/body-composition records; map to the canonical contract.
- **PR6 — Withings notification-driven ingestion.** Public callback; verification handling; notification receipt; follow-up API retrieval; idempotent canonical ingest. Re-verify exact callback/payload/retry behaviour directly against developer.withings.com before building — blocked to automated fetch in the research phase, Medium confidence only.
- **PR7 — Reconciliation and missed-event recovery.** Scheduled API reconciliation; cursor/timestamp tracking; missed-notification recovery; duplicate protection; operational health status.

Gate: a real measurement must travel Withings scale → Withings cloud → Fusion service → canonical record → readable output.

---

## WP4 — Android companion MVP

Begins only after WP1 and WP2 establish the chosen Android route.

- **PR8 — Local outbox and manual synchronisation.** Retain chosen data adapters; local encrypted outbox; source-specific sync cursor; manual "Sync now"; last-success and error status.
- **PR9 — Secure Fusion upload.** Authenticated HTTPS upload; batch contract; idempotency; server validation; privacy-safe logs.
- **PR10 — Background sync and offline catch-up.** WorkManager; retry and backoff; multi-day offline recovery; correction and deletion propagation; battery-conscious scheduling.

Acceptance: phone can remain offline and catch up later; records are not lost; duplicate uploads do not create duplicate canonical records; Android data reaches Fusion without the work laptop being involved.

---

## WP5 — Unified data quality and observability

- **PR11 — Authority enforcement and deduplication.** Enforce metric authority; reject excluded Android duplicates; validate timestamps and units; retain provenance.
- **PR12 — Record lifecycle and ingestion status.** Updates; deletions; failed-record visibility; source health; ingestion history.

---

## WP6 — Read model and dashboard

- **PR13 — Health timeline and query layer.** Daily and weekly summaries; weight/body-composition trends; sleep; activity; nutrition; source completeness.
- **PR14 — Personal health dashboard.** Current-day overview; trend views; missing-data indicators; source and last-sync status; provenance drill-down.

---

## WP7 — Intelligence and contextual briefings

- **PR15 — Derived trends.** Rolling weight trend; sleep consistency; training load context; nutrition adherence; data completeness and confidence.
- **PR16 — Fusion health briefing.** Morning summary; important changes; missing-source warnings; training and nutrition context. No medical diagnosis or unsupported causal claims.

---

## WP8 — Security and distribution hardening

Future work — should not block the personal diagnostic or early private use: full Vex security review; export and deletion controls; retention policy; signing-key rotation; recovery procedures; Samsung partnership submission; Play Store health-data declarations; release distribution.

---

## Revised PR sequence

| PR | Work Package | Outcome |
|---:|---|---|
| PR1 | WP1 | Cloud-build pipeline produces installable APK |
| PR2 | WP1 | Health Connect data proven on Warwick's phone |
| PR3 | WP1 | Samsung SDK compared against Health Connect |
| PR4 | WP2 | Source authority and canonical contract approved |
| PR5 | WP3 | Withings OAuth and API retrieval |
| PR6 | WP3 | Withings notification ingest |
| PR7 | WP3 | Reconciliation and recovery |
| PR8 | WP4 | Android local sync foundation |
| PR9 | WP4 | Secure Fusion upload |
| PR10 | WP4 | Background and offline sync |
| PR11 | WP5 | Deduplication and authority enforcement |
| PR12 | WP5 | Lifecycle and observability |
| PR13 | WP6 | Health query/read layer |
| PR14 | WP6 | Initial dashboard |
| PR15 | WP7 | Derived trends |
| PR16 | WP7 | Contextual health briefing |

---

## Immediate authorisation boundary

At present, authorised: WP0 capability preflight (passed); this repository and tracking scaffold.

Not yet authorised: PR1 code (Android scaffold, GitHub Actions workflow, signing key generation), PR2/PR3 (real health-data access), Withings, backend ingestion, dashboards, or intelligence work.

Milestone 1: Larry produces a signed APK in GitHub Actions, and Warwick installs it directly on his Galaxy phone without installing anything on the work computer.

Milestone 2: The APK produces a truthful field-level comparison between Health Connect and Samsung Health Data SDK using Warwick's real health data.
