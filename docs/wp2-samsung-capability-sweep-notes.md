# WP2 — Samsung Health Capability Sweep

**Status: OBJECTIVE ACHIEVED.** Real-device sweep run and accepted by Warwick (2026-07-14).

## Purpose

A read-only inventory experiment: discover which Samsung Health-originated data types Fusion Health
can already read through Health Connect, on Warwick's real phone/watch ecosystem. Health Connect
only — no Samsung Health Data SDK. Withings- and MyFitnessPal-originated records are never queried.

## Accepted device result

Samsung-originated data (`com.sec.android.app.shealth`) is available through Health Connect for
**8 of the 18 tested domains**.

**Populated (available now):**

- Steps
- Distance
- Total calories
- Exercise sessions (including type + duration)
- Speed
- Sleep sessions (including stage records)
- Heart rate
- Oxygen saturation

**Empty — current capability gaps (not failures, not blockers):**

- Floors climbed
- Active calories
- Elevation gained
- Power
- Resting heart rate
- HRV (RMSSD)
- Respiratory rate
- Blood pressure
- Blood glucose
- Body temperature

## Findings

- This is sufficient evidence to proceed on Health Connect alone; the Samsung Health Data SDK is
  **not** required for the current work.
- Samsung exports raw **heart rate** to Health Connect but **not** resting heart rate, HRV or
  respiratory rate — those are the notable gaps and would require the Samsung SDK (out of scope).
- Distance, speed, total calories and exercise sessions share one time window — they are
  **workout-derived**.
- **Sleep** exposes a full stage breakdown, so sleep-stage analysis is viable through Health
  Connect.
- One SDK detail discovered during the build: `connect-client:1.1.0` exposes no distinct read
  permission for `CyclingPedalingCadenceRecord`, so cycling cadence was excluded from the sweep
  (documented in code) rather than blocking the build.

The empty domains are recorded as **current capability gaps**, not failures. No further
capability-sweep expansion, no additional record types, no Samsung SDK work.

## Next

The sweep's objective (reduce uncertainty about Samsung availability) is met. The follow-on is the
first user-facing summary — the **Samsung Health Snapshot** — tracked separately, built on these
proven-available domains.
