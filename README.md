# Fusion Health

Personal health-data integration layer for Fusion — Withings (weight/body composition), Samsung Health/Health Connect (Galaxy Watch, activity, sleep), and MyFitnessPal (nutrition via Health Connect).

## Delivery principle

No Android Studio, Java, Gradle, or Samsung tooling is installed on Warwick's work computer. The build chain is:

```text
Larry writes and reviews source code
            ↓
Private GitHub repository (this repo)
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

## Status

**WP0 (capability preflight) — passed for PR1 scope.** Java/Gradle/GitHub Actions build chain confirmed viable. Samsung Health Data SDK acquisition and licence/redistribution terms remain unresolved (developer.samsung.com is blocked to automated fetch in the research environment) — this blocks PR3 only, not PR1 or PR2.

Full work-package breakdown and PR sequence: [`docs/plan.md`](docs/plan.md).

Tracking: ClickUp — Fusion 247 Foundry space, IDEA-005.

## Current authorisation boundary

Authorised so far: WP0 preflight, repository/tracking scaffolding (this commit).
Not yet authorised: PR1 Android code/GitHub Actions workflow, PR2/PR3 health-data access, any backend/ingestion/dashboard work.

## Related

- `warwickallan/fusion247pka` — myPKA scaffold repo where the original research brief and correction memo live (`Deliverables/2026-07-13-health-data-pipeline-source-feasibility.md`), held uncommitted there pending Warwick's decision on its disposition.
