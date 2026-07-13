# Fusion Health

Personal health-data integration layer for Fusion — Withings (weight/body composition), Samsung Health/Health Connect (Galaxy Watch, activity, sleep), and MyFitnessPal (nutrition via Health Connect).

## Delivery principle

No Android Studio, Java, Gradle, or Samsung tooling is installed on Warwick's work computer. The build chain is:

```text
Larry writes and reviews source code
            ↓
GitHub repository (this repo)
            ↓
GitHub Actions cloud build
            ↓
Signed test APK
            ↓
Warwick downloads APK directly to Galaxy phone
            ↓
Warwick installs, grants permissions, runs test
            ↓
Diagnostic report returned to Larry
```

**Repository visibility — currently public.** This is a deliberate, temporary choice made for build purposes; the repo will be flipped to private before production use. Anything published from this repo (releases, prereleases, artifacts) is public while the repo is public, regardless of "prerelease" labelling — treat nothing here as confidential until visibility is actually changed.

## Status

**WP0 (capability preflight) — passed for PR1 scope.** Java/Gradle/GitHub Actions build chain confirmed viable. Samsung Health Data SDK acquisition and licence/redistribution terms remain unresolved (developer.samsung.com is blocked to automated fetch in the research environment) — this blocks PR3 only, not PR1 or PR2.

**PR1 — complete.** Cloud build pipeline proven end-to-end, permanent release signing configured via GitHub secrets, and the in-place update path proven on Warwick's Galaxy device: Build A installed fresh, Build B installed directly over it without uninstalling, and the update succeeded (see `docs/plan.md` status notes for full evidence).

Full work-package breakdown and PR sequence: [`docs/plan.md`](docs/plan.md).

Tracking: ClickUp — **BUILD-005**, Fusion 247 MyPKA space (production delivery). This idea converged and was approved for production in Fusion 247 Foundry as `IDEA-005`; Foundry now retains only a concise handoff record for it, not active work-package/PR tracking.

## Current authorisation boundary

Authorised and complete: WP0 preflight, repository/tracking scaffolding, PR1 (Android scaffold, cloud APK pipeline, permanent release signing, proven in-place update).
Not yet authorised: PR2/PR3 health-data access, any backend/ingestion/dashboard work.

## Related

- `warwickallan/fusion247pka` — myPKA scaffold repo where the original research brief and correction memo live (`Deliverables/2026-07-13-health-data-pipeline-source-feasibility.md`), held uncommitted there pending Warwick's decision on its disposition.
