# Bracket-aware capacity signal + confidence-scaled differential snap

**Date:** 2026-06-21
**Status:** Approved (design), pending implementation plan

## Problem

When a loaded exercise is prescribed far too heavy, the user drops the weight
mid-session until it is doable — e.g. Bulgarian Split Squat on Jun 18:
`55 lb fail (2 reps) → 35 lb fail (2 reps) → 20 lb completed (RIR 0-1)`. This
brackets the true 10-rep capacity at roughly **20 lb**.

The system instead re-prescribed ~55 lb the next session — off by nearly 3×.
Two compounding causes (confirmed against prod data via the in-app debug screen,
`pi:n=3,common=0.0140`, quad baseline `230→232`, Bulgarian coefficient `-3%` vs
seed):

1. **The strongest signal is discarded.** `SessionSignalExtractor.aggregateSession`
   keeps only full-weight sets (`w0 = max targetWeight`). The reduced-weight
   failures *and* the successful 20 lb set carry no signal, so the one fact that
   demonstrates true capacity never reaches the controller. The estimator sees
   only "one 55 lb set, 2 reps," rep-penalized.
2. **The correction is rate-limited far below the evidence.** Even the damped
   signal is throttled by EMA `β=0.5`, `kC=0.5`, and the per-session differential
   clamp `maxLogStepC = ln(1.10)` (±10%/session). Combined with the gauge-correct
   baseline drifting *up* on the common mode and 5-lb rounding, the next
   prescription stays pinned at 55 lb for many sessions.

## Goal

Make a demonstrated drop-cascade pull the exercise's next prescription down to
roughly the demonstrated capacity within **1–2 sessions**, in a
**gauge-preserving** way (coefficient geomean conserved; correction lands on the
exercise's coefficient, not a hard clamp that bypasses the controller).

## Non-goals

- No hard ceiling clamp that bypasses the controller. The correction flows
  through the existing common/differential loop.
- No robust/median common mode. We keep the simple confidence-weighted-mean
  common term and **accept the small (~1–2%, self-recovering) baseline dip** a
  high-confidence outlier causes. (Explicit decision 2026-06-21.)

## Design

### 1. Bracket-aware capacity estimator (`SessionSignalExtractor`)

Replace the top-weight-only capacity logic in `aggregateSession` with a bracket
over **all** sets that have feedback:

- **Anchor on the heaviest *completed* set**, converted with its reserve:
  `est1RM = max over completed sets of rawToOneRepMax(weight, targetReps + reserve(feedback))`
  where `reserve(feedback)` is the existing `RESERVE_RIR_*` constants. For Jun 18
  this is `20 lb @ 10 reps, RIR_0_1` → est1RM from a near-failure set at a
  sustainable rep count (the trustworthy reading).
- **Failures are ceilings, not point estimates.** A failed heavy set is *not*
  used as a 1RM estimate (the formula is unreliable that far out of range). It
  only caps the completed anchor from above: `est1RM` may not exceed
  `min over failed sets of rawToOneRepMax(failedWeight, targetReps)`. This rarely
  binds when a completed anchor exists well below the failed weights; it protects
  the all-failed case below.
- **All-failed session** (no completed set at any weight): estimate from the
  **lightest** failed set (the one with the most achieved reps, hence the most
  reliable point estimate): `est1RM = rawToOneRepMax(lightestFailWeight, actualReps)`.
  Still a strong downward signal. HURT continues to carry no load signal.
- **Confidence** is high **only** when there is a genuine bracket: a full-weight
  failure *and* a completed lighter set (the drop-cascade). A normal no-failure
  session keeps today's modest per-feedback confidence, so ordinary days do not
  snap. Expose this as the `sessionConfidence` already returned in
  `SessionAggregate` (raised toward ~0.95+ for a true bracket).

Single-weight sessions (no drop) and pure-up sessions retain today's behavior —
the asymmetric reserve/failure aggregation is unchanged; only the multi-weight
bracket path is new.

### 2. Confidence-scaled, gauge-preserving differential (`RollingConservingProgressionController`)

The snap comes from relaxing the *rate limiter* on high-confidence sessions, not
from touching the gauge. The differential update `kC·gainᵢ·(eᵢ − common)` is
already exactly sum-zero (`Σ (wᵢ/maxW)(eᵢ − common) = 0`); only the `coerceIn`
clamp breaks it (the bounded residual drift noted in the controller).

Scale the differential **step** by the observation's confidence:

- `kC_eff = lerp(kC, ~1.0, confidenceScale)` — high-confidence bracket uses
  near-full gain so the coefficient moves the bulk of `(eᵢ − common)` in one step.
- `maxLogStepC_eff = lerp(maxLogStepC, large, confidenceScale)` — widen the clamp
  so a genuine bracket is not capped at ±10%. In the high-confidence limit the
  step is effectively unclamped, where the update is **exactly sum-zero → gauge
  conserved exactly**.
- Low-confidence sessions keep today's `kC=0.5` / `±ln(1.10)` clamp (noise
  control). `confidenceScale` is derived from the per-exercise observation
  confidence threaded from `ProgressionObservation.confidence` into the
  differential loop (today only used as pool weight `w`).

Result for Jun 18 Bulgarian: coefficient drops ~`ln(20/55)` over 1–2 sessions;
peer quad coefficients each rise ~1–2% to hold the geomean; baseline dips
slightly via the common mode and recovers (accepted). Next Bulgarian
prescription lands near the demonstrated ~20–25 lb.

### Data flow (unchanged shape)

`WorkoutSet[]` → `SessionSignalExtractor.aggregateSession` → `(est1RM,
sessionConfidence)` → `ProgressionObservation` → `RollingConservingProgressionController.step`
→ `BaselineUpdate` / `CoefficientUpdate`. Only the est1RM/confidence computation
(1) and the differential step scaling (2) change; interfaces are unchanged except
the controller now reads `ProgressionObservation.confidence` inside the
differential loop.

## Testing

- **`SessionSignalExtractorTest`**: drop-cascade (`55 fail / 35 fail / 20 RIR_0_1`)
  → est1RM anchored on the 20 lb completed set, not the 55 lb top set; all-failed
  cascade → estimate from lightest failed set; ordinary single-weight sessions
  unchanged; HURT still null.
- **`ProgressionControllerSimulationTest`**: a scenario reproducing
  "55 → fail → drop to 20, re-prescribed 55" with a mixed quad pool, asserting
  (a) the next prescription reaches ~capacity within ≤2 sessions, (b) coefficient
  geomean stays within the existing `coefInflation` ceiling (gauge conserved),
  (c) baseline drift stays within a small bound, (d) low-confidence sessions still
  obey the tight clamp (no regression in convergence on normal data).

## Risks

- **Over-correction on a genuine bad day** mislabeled as a capacity drop. Mitigated
  by gating high confidence on the *bracket* (failure + completed lighter set),
  not on a single failure, and by the est1RM anchoring on an actually-completed
  near-failure set.
- **Peer coefficient inflation** from repeated large differentials. Bounded by the
  sum-zero construction and asserted by the simulation test's geomean ceiling.
