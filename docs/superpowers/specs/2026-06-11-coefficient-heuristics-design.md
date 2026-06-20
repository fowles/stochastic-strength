# Coefficient Heuristics — Design Spec

**Date:** 2026-06-11
**Status:** Draft

## Goal

Define the first concrete `CoefficientHeuristic` implementation for the per-user coefficient system. This heuristic derives per-exercise coefficients from session history using temporally-local evidence, distinguishes coefficient miscalibration from baseline drift, and updates conservatively so coefficients drift rather than jump.

## Background

The per-user coefficient scaffold landed in `2026-06-10-per-user-coefficients-design.md`: schema (`coefficient_change_log`), DAO, `UserCoefficientSource`, `buildCoefficientInput`, `recomputeCoefficients`, and the `CoefficientHeuristic` interface. `WorkoutRepository` is constructed with `heuristics: List<CoefficientHeuristic> = listOf()` and the system is inert until at least one heuristic is registered in `StochasticStrengthApp`.

This spec defines that first heuristic.

### Latent-state framing

Each (user, exercise) is described by two latent values:

- `B(t)` — muscle baseline, evolves slowly over time as the user adapts. In idealized steady state it trends gently upward or stays constant.
- `c_e` — exercise coefficient, approximately stationary. Represents how this exercise's 1RM relates to the muscle's reference-exercise 1RM. Could change slowly with technique/equipment changes but should not move quickly.

The planning formula is `sessionWeight = fromOneRepMax(B(t) × c_e, sessionReps)`, so `B(t) × c_e` is the predicted 1RM for the exercise at time `t`.

The baseline progression engine already absorbs slow user adaptation by adjusting `B(t)` after every session. The coefficient heuristic's job is to find evidence that a specific `c_e` is miscalibrated — *without* being fooled by baseline drift, by sparse data, or by old data that no longer reflects current state.

### Key constraint: temporal locality

Data points far apart in time can't safely be cross-correlated. `B(t)` may have shifted between them, and the baseline engine's reconstruction of `B(t)` accumulates error over time. The heuristic therefore:

- Uses a tight aggregation window (default 28 days)
- Weights points by recency (exponential decay, default half-life 45 days)
- Detects "all exercises in muscle group drift together" as a baseline-engine signal, not a coefficient signal

### Assumed prerequisite: precise failure data

This spec assumes `WorkoutSet.completedReps: Int?` exists and is populated whenever a user takes the mid-set reduction flow after `TOO_HARD`. The schema/UI changes for that are tracked separately and are not in scope here. Historical `TOO_HARD` sets where `completedReps` is null are still usable as an upper-bound signal.

## Section 1: Conceptual Model

For any (exercise, session) we can derive a noisy point estimate of `c_e`:

```
est_1RM_e,s     = aggregate over the session's sets (see Section 2)
est_coef_e,s    = est_1RM_e,s / muscleBaseline_at_s
```

Where `muscleBaseline_at_s` is already provided in `ExerciseSessionSnapshot.muscleBaseline` (the `previousBaseline` from the corresponding `baseline_change_log` PROGRESSION row — i.e., the baseline that was actually in effect when the session was planned).

These point estimates are aggregated within a recent window into a single proposed coefficient per exercise, gated by a cross-exercise consensus check, then damped against the current coefficient.

## Section 2: Set-Level Signal Extraction

For each `SetSnapshot` in a session for a non-bodyweight exercise:

| Feedback | est_1RM | Confidence | Notes |
|----------|---------|------------|-------|
| `null` | — | — | skip |
| `HURT` | — | — | skip (hurt sets carry no calibration info) |
| `RIR_5_PLUS` | `epley(targetWeight, targetReps + 7)` | 0.4 | very wide bucket; weak signal |
| `RIR_2_4` | `epley(targetWeight, targetReps + 3)` | 0.7 | midpoint |
| `RIR_0_1` | `epley(targetWeight, targetReps + 0.5)` | 0.85 | midpoint |
| `TOO_HARD` with `completedReps` | `epley(targetWeight, completedReps)` | 0.95 | precise point estimate |
| `TOO_HARD` without `completedReps` | upper bound: `epley(targetWeight, max(1, targetReps - 1))` | 0.5 | asymmetric — pulls down only |

`epley(weight, reps) = weight × (1 + reps / 30)` — the existing `rawToOneRepMax` in `DefaultProgressionEngine`.

**Asymmetric upper-bound handling:** a `TOO_HARD` set without `completedReps` produces an `est_1RM` value treated as a ceiling. It contributes to the session aggregate *only if* the session's other-feedback estimate exceeds it; otherwise it's omitted. This prevents an upper-bound point from spuriously dragging coefficients down when the rest of the session already agrees with a value below the bound.

### Session aggregation

For sets within a single session for an exercise, aggregate to a single `est_1RM_e,s`:

```
est_1RM_e,s = confidence-weighted mean of set est_1RM values
session_confidence_e,s = mean of contributing set confidences
```

The reduced-weight sets that follow a `TOO_HARD` event contribute their own (`weight`, `feedback`) tuples and are aggregated alongside the original-weight sets — they're additional data points at a lower load, not separate sessions.

Sessions with zero usable set signals (all `null` / `HURT`) contribute nothing.

## Section 3: Heuristic H1 — Per-Exercise Aggregation

For each exercise `e` with `seed_coef > 0` (bodyweight exercises are skipped):

1. Collect `(est_coef_e,s, weight_e,s)` for every session `s` in the window `[now - W, now]` where the exercise appeared with a usable signal.
   - `est_coef_e,s = est_1RM_e,s / muscleBaseline_at_s`
   - `weight_e,s = recency(s) × session_confidence_e,s`
   - `recency(s) = exp(-(now - sessionTime_s) × ln(2) / τ_half)` — at `Δt = τ_half`, recency = 0.5
2. Compute the **weighted median** of `est_coef_e,s` values, weighted by `weight_e,s`. This is `proposal_e`.
3. Compute `total_weight_e = Σ weight_e,s`.
4. Compute `proposal_confidence_e = Σ(recency_e,s × session_confidence_e,s) / Σ recency_e,s` — recency-weighted mean of session confidences. (Not weighted by `weight_e,s`, which would double-count confidence.)
5. Set a "definite point" flag `has_definite_e` = true iff any contributing point had `TOO_HARD` with `completedReps`.

If `total_weight_e < min_evidence_weight` AND `has_definite_e` is false: emit nothing for this exercise.

Output of H1 per exercise: `H1Proposal(proposal_e, proposal_confidence_e, total_weight_e, has_definite_e)`.

Weighted median is used rather than weighted mean because individual sessions are noisy (RIR buckets are wide) and median is robust to a single freak point. Within a tight time window, `c_e` is approximately stationary, so a median tracks the cluster of estimates well.

## Section 4: Heuristic H2 — Cross-Exercise Consensus

H2 modifies H1's output. It runs after H1 produces per-exercise proposals.

For each muscle group `m` with two or more exercises producing H1 proposals within the window:

1. Compute `signed_log_ratio_e = log(proposal_e / current_coef_e)` for each exercise.
2. Examine the direction and magnitude across exercises in the muscle group:

```
let n = number of exercises with proposals in m
let mean_log_ratio = mean of signed_log_ratio_e in m

case (n, pattern):
  n == 1:
    pass through (no consensus check possible — H1's proposal stands at proposal_confidence_e)

  n ≥ 2 AND all signed_log_ratio_e same sign AND
    |mean_log_ratio| > τ_consensus_threshold:
    → likely baseline-engine drift, NOT coefficient miscalibration
    → suppress all proposals in m (emit nothing for this muscle group this round)
    → metadata on a suppressed-but-logged marker is out of scope (see Open Question 1)

  n ≥ 3 AND exactly one exercise e* has |signed_log_ratio_e*| > τ_outlier_threshold
    AND every other exercise in m has |signed_log_ratio_e| < τ_consensus_threshold:
    → e* is the miscalibrated coefficient; siblings are well-calibrated
    → emit e*'s proposal with confidence replaced by 1.0 (overrides H1's proposal_confidence)
    → suppress other exercises in m for this round
    → metadata: "consensus_outlier:m={...},sibling_count={n-1}"

  default:
    → mixed signal across exercises — both true drift and coefficient miscalibration are plausible
    → emit all H1 proposals at their normal proposal_confidence_e
    → metadata: "consensus_mixed:m={...},n={n}"
```

Why suppress when *all* exercises drift in the same direction: if every exercise in a muscle group reports easier-than-expected feedback, the user got stronger faster than the baseline engine has tracked. Updating coefficients in that situation would absorb work that belongs in the baseline — and would have to be undone later when the baseline catches up. Better to wait one or two more sessions for the baseline to update and re-evaluate.

Why boost when one exercise is an outlier: if two exercises for the same muscle agree with their current coefficients (or drift together) and a third disagrees, the third is the miscalibrated one — strongest possible coefficient signal in this system.

## Section 5: Damping and Update

For each surviving proposal — `(proposal_e, emit_confidence_e)` where `emit_confidence_e` is either H1's `proposal_confidence_e` (mixed-signal path or n=1) or 1.0 (outlier path):

```
log_step = α × emit_confidence_e × log(proposal_e / current_coef_e)
log_step = clamp(log_step, -max_log_step, +max_log_step)
new_coef_e = current_coef_e × exp(log_step)
```

If `|new_coef_e - current_coef_e| < min_change_threshold`, emit nothing (avoid log churn on essentially-zero updates).

Damping is multiplicative (in log space) for stability across coefficient scales — a 5% drift cap means the same thing for a coefficient of 0.15 (lateral raise) and 2.5 (leg press).

## Section 6: Tunable Parameters

Initial defaults — these are starting points, not validated values. All live as constants on the heuristic class so they're easy to find and adjust.

| Parameter | Symbol | Default | Notes |
|-----------|--------|---------|-------|
| Window length | `W` | 28 days | how far back est_coef points are collected |
| Recency half-life | `τ_half` | 45 days | older points decay; values past ~2× half-life contribute little |
| Min evidence weight | `min_evidence_weight` | 1.5 | roughly 2–3 medium-confidence sessions; bypassed by any `completedReps` point |
| Consensus suppression threshold | `τ_consensus_threshold` | `ln(1.05)` | ≈5% mean log-ratio; below this, mixed-signal path |
| Outlier detection threshold | `τ_outlier_threshold` | `ln(1.10)` | ≈10% log-ratio; outlier must diverge meaningfully |
| Damping rate | `α` | 0.2 | fraction of log-distance closed per update |
| Max log-step per update | `max_log_step` | `ln(1.05)` | ≈5% max change per recompute |
| Min change threshold | `min_change_threshold` | 0.005 | absolute coefficient change below this is dropped |

## Section 7: Architecture

H1 + H2 + damping is a single `CoefficientHeuristic` implementation — `EstCoefConsensusHeuristic`. It composes the three layers internally because H2 needs to see all of H1's proposals across exercises in a muscle group simultaneously, and damping needs both the H2-boosted confidence and the current coefficient.

```kotlin
class EstCoefConsensusHeuristic(
    private val now: () -> Long = System::currentTimeMillis,
    // parameters from Section 6 as constructor args with defaults
) : CoefficientHeuristic {
    override val name = "est-coef-consensus"
    override fun compute(input: CoefficientComputationInput): List<CoefficientResult> { ... }
}
```

Registered in `StochasticStrengthApp` when constructing `WorkoutRepository`:

```kotlin
heuristics = listOf(EstCoefConsensusHeuristic())
```

Future heuristics (e.g., a long-window stationarity check, or a per-equipment-class heuristic) can be added as separate `CoefficientHeuristic` instances. The existing `mergeHeuristicResults` (first-non-null wins, by list order) stays as-is for now — sufficient until we have a second heuristic that actually competes for the same exercises.

Internal structure of `compute`:

1. **Filter input**: drop bodyweight exercises (seed coefficient `≤ 0`), drop snapshots with no usable sets.
2. **Per-exercise H1**: build `(exerciseId → H1Proposal)` map, where `H1Proposal` carries `proposal`, `totalWeight`, `hasDefinitePoint`, plus an exerciseId → primaryMuscle lookup.
3. **Group by muscle**: build `(muscleGroup → List<H1Proposal>)`.
4. **Apply H2** to each muscle group, producing a `(exerciseId → (proposal, confidence, metadata))` map of survivors.
5. **Damp**: convert each survivor into a `CoefficientResult` via the Section 5 formula. Drop those below `min_change_threshold`.

`muscleGroup` is needed but not on `ExerciseSessionSnapshot` — it would be added to the snapshot in the input builder. (Currently `buildCoefficientInput` has access via `exerciseMuscle`; surfacing it on the snapshot avoids re-looking it up inside the heuristic and is a one-line change.)

## Section 8: Worked Examples

### Example A: simple convergence

User has done Barbell Bench Press 4 times in the last 21 days. Current `c_e = 1.00`, muscle baseline currently 80 kg.

| Session | Δt | targetWeight | targetReps | feedback | est_1RM | est_coef | recency | weight |
|---------|----|---|---|---|---|---|---|---|
| s1 | 2d | 80 | 5 | RIR_2_4 | 80×(1+8/30) = 101.3 | 1.27 | 0.97 | 0.97×0.7 = 0.68 |
| s2 | 7d | 80 | 5 | RIR_2_4 | 101.3 | 1.27 | 0.90 | 0.90×0.7 = 0.63 |
| s3 | 13d | 80 | 8 | RIR_0_1 | 80×(1+8.5/30) = 102.7 | 1.28 | 0.82 | 0.82×0.85 = 0.70 |
| s4 | 20d | 80 | 5 | RIR_5_PLUS | 80×(1+12/30) = 112.0 | 1.40 | 0.74 | 0.74×0.4 = 0.30 |

Weighted median of est_coef = ~1.27. `proposal_e = 1.27`, `total_weight ≈ 2.30`, `proposal_confidence ≈ 0.67`.

Damping: `log_step = 0.2 × 0.67 × log(1.27/1.00) = 0.032`. Clamped (max is `ln(1.05) ≈ 0.0488`), passes. `new_coef = 1.00 × exp(0.032) = 1.033`.

Coefficient nudges from 1.00 to 1.03 — small move, conservative. Over several sessions of consistent evidence, it climbs toward 1.27.

### Example B: consensus suppression

Same muscle group (chest), three exercises in window, all showing RIR_2_4 feedback at their planned weights.

- Barbell Bench Press: est_coef 1.06 vs current 1.00 → log_ratio +0.058
- Incline Barbell: est_coef 0.91 vs current 0.85 → log_ratio +0.068
- Dumbbell Bench: est_coef 0.43 vs current 0.40 → log_ratio +0.072

All same sign, `mean_log_ratio = 0.066`, exceeds `τ_consensus_threshold = ln(1.05) ≈ 0.049`. → **suppressed**. The user got stronger; baseline will catch up next session. Coefficients untouched. Metadata: `consensus_suppressed:m=CHEST,mean_log_ratio=0.066`.

### Example C: outlier detection

Three back exercises in window:

- Barbell Row: est_coef 1.01 vs current 1.00 → +0.010
- Lat Pulldown: est_coef 0.79 vs current 0.80 → −0.013
- Seated Cable Row: est_coef 0.96 vs current 0.75 → **+0.247**

Two near-zero, one strongly positive and the outlier. `τ_outlier_threshold = 0.0953`. Outlier qualifies. → Update Seated Cable Row with boosted confidence (1.0). Barbell Row and Lat Pulldown suppressed this round. Metadata on the row update: `consensus_outlier:m=BACK,sibling_count=2`.

Damping with confidence 1.0: `log_step = 0.2 × 1.0 × 0.247 = 0.0494`, clamped to `ln(1.05) = 0.0488`. `new_coef = 0.75 × exp(0.0488) = 0.787`. Coefficient moves 0.75 → 0.79 this round. Several more rounds of similar evidence will converge it toward 0.96.

## Section 9: Testing

All tests live on the JVM — the heuristic is a pure function over `CoefficientComputationInput`.

**Set-level signal extraction:**
- Each feedback type yields the expected `(est_1RM, confidence)` tuple
- `null` and `HURT` are skipped
- `TOO_HARD` with `completedReps = null` produces an upper-bound point
- `TOO_HARD` with `completedReps = 3` (at targetReps=8) yields `epley(weight, 3)` at confidence 0.95

**Session aggregation:**
- Multiple sets at the same weight combine via confidence-weighted mean
- A reduced-weight set after `TOO_HARD` is included as an additional point
- A session with all-null sets contributes nothing
- Upper-bound `TOO_HARD` is omitted when other points already agree below the bound

**H1 (per-exercise aggregation):**
- Empty history → no proposal
- Single high-confidence session below `min_evidence_weight` → no proposal unless it's `TOO_HARD + completedReps`
- Sessions outside window are dropped
- Recency weighting: a recent low-confidence session and an old high-confidence session contribute appropriately
- Weighted median ignores a single outlier session

**H2 (consensus):**
- One exercise per muscle group → pass-through
- All exercises same direction with mean log-ratio above threshold → all suppressed (empty result for that muscle group)
- All exercises same direction with mean log-ratio below threshold → mixed-signal path; all pass through
- Three exercises, one outlier above outlier threshold → outlier emitted with boosted confidence; others suppressed
- Three exercises, one outlier below outlier threshold → mixed-signal path

**Damping:**
- Proposal equals current coefficient → no result emitted (below `min_change_threshold`)
- Large proposal change → log-step clamped to `max_log_step`
- Small proposal change → log-step proportional to confidence and distance

**Integration / end-to-end:**
- Realistic `CoefficientComputationInput` (multiple exercises, multiple sessions, mixed feedback) produces a sensible set of `CoefficientResult`s with non-empty metadata strings

## Section 10: Out of Scope

- **`completedReps` capture.** This spec assumes `WorkoutSet.completedReps: Int?` exists. The schema migration and `WorkoutSessionController.reduceExerciseWeight` change to persist the value are tracked separately. Until that lands, no `TOO_HARD` set will carry `completedReps` and the upper-bound branch handles them.
- **Backfilling completedReps for historical TOO_HARD events.** Not possible — the value was discarded at session time. Historical TOO_HARD sets contribute only as upper bounds, which is acceptable.
- **UI surfacing of coefficient changes.** No new screens, indicators, or change-log visualization. The user-visible effect is solely through adjusted session weights.
- **Parameter tuning from data.** Defaults in Section 6 are starting values. Empirical tuning is a follow-up activity once the heuristic has produced a few weeks of real updates.
- **Multiple heuristics + sophisticated merge.** First heuristic only. `mergeHeuristicResults` stays as first-non-null-wins.
- **Pure ratio-only heuristic (H4 from brainstorming).** Considered and deferred. H1+H2 covers similar ground for the common case where the baseline engine is reasonably accurate. Revisit if baseline-engine error becomes a dominant source of coefficient noise.

## Section 11: Open Questions

1. **Should the heuristic write a `CoefficientChangeLog` entry with `coefficient = current` (no-op row) when H2 suppresses a proposal, for visibility?** Argument for: makes the log explainable when the user wonders why nothing changed. Argument against: clutter; the metadata-bearing row is only meaningful when something actually changed. **Working assumption: no — suppression is silent.**

2. **First-update bootstrap.** When `current_coefficient` comes entirely from the seed (`ExerciseCoefficients`) — i.e., the exercise has never had a user-coefficient row — should the first update be damped (same as any other) or allowed to take a larger step? **Working assumption: same damping. The seed coefficient is a reasonable prior and shouldn't be overridden on a single window's evidence.**

3. **Should sessions older than the window contribute nothing, or contribute at very low weight via the recency decay?** Current spec drops them entirely at `W = 28d`. **Working assumption: hard window cutoff. Simpler and the recency weight at 28+ days is small anyway.**

## Files Changed

| File | Change |
|------|--------|
| `domain/EstCoefConsensusHeuristic.kt` | new — implements `CoefficientHeuristic` (H1 + H2 + damping) |
| `domain/CoefficientHeuristic.kt` | add `primaryMuscle: MuscleGroup` to `ExerciseSessionSnapshot` |
| `domain/WorkoutRepository.kt` | populate `primaryMuscle` in `buildCoefficientInput` |
| `StochasticStrengthApp.kt` | register `EstCoefConsensusHeuristic` in `WorkoutRepository` construction |
| `test/.../EstCoefConsensusHeuristicTest.kt` | new — unit tests per Section 9 |
