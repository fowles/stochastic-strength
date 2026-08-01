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

- Weights points by recency via exponential decay (default half-life 14 days). No hard time cutoff — old sessions just contribute negligibly.
- Detects "all exercises in muscle group drift together" as a baseline-engine signal, not a coefficient signal

### Available data: precise failure reps

`WorkoutSet.actualReps: Int?` is populated by `WorkoutSessionController` whenever the user taps the rep prompt after `TOO_HARD`. Historical TOO_HARD sets followed by a reduced-weight same-exercise set are backfilled by `ActualRepsBackfill` (inverting `DefaultProgressionEngine.scaleReps`). Remaining TOO_HARD sets — last-set-of-exercise, last-set-of-workout, or sets the backfill couldn't resolve — keep `actualReps = null` and are usable as an upper-bound signal. Backfill-inferred and user-tapped values are not distinguished in storage; both are read identically.

## Section 1: Conceptual Model

For any (exercise, session) we can derive a noisy point estimate of `c_e`:

```
est_1RM_e,s     = aggregate over the session's sets (see Section 2)
est_coef_e,s    = est_1RM_e,s / muscleBaseline_at_s
```

Where `muscleBaseline_at_s` is looked up via `input.baselines[(sessionId, primaryMuscle)]` (the `previousBaseline` from the corresponding `baseline_change_log` PROGRESSION row — i.e., the baseline that was actually in effect when the session was planned).

These point estimates are recency-weighted into a single proposed coefficient per exercise, gated by a cross-exercise consensus check, then damped against the current coefficient.

## Section 2: Set-Level Signal Extraction

For each `WorkoutSet` in a session for a non-bodyweight exercise:

| Feedback | est_1RM | Confidence | Notes |
|----------|---------|------------|-------|
| `null` | — | — | skip |
| `HURT` | — | — | skip (hurt sets carry no calibration info) |
| `RIR_5_PLUS` | `toOneRepMax(targetWeight, targetReps + 7)` | 0.4 | very wide bucket; weak signal |
| `RIR_2_4` | `toOneRepMax(targetWeight, targetReps + 3)` | 0.7 | midpoint of 2–4 RIR |
| `RIR_0_1` | `toOneRepMax(targetWeight, targetReps + 1)` | 0.85 | "1 in reserve" — integer-rounded midpoint of 0–1 RIR |
| `TOO_HARD` with `actualReps` | `toOneRepMax(targetWeight, actualReps)` | 0.95 | precise point estimate |
| `TOO_HARD` without `actualReps` | upper bound: `toOneRepMax(targetWeight, max(1, targetReps - 1))` | 0.5 | asymmetric — pulls down only |

`toOneRepMax` is `ProgressionEngine.toOneRepMax` — the existing load-aware 1RM estimator used elsewhere in the codebase (falls back to Epley only when the load is too light for the load-aware branch).

**Asymmetric upper-bound handling:** a `TOO_HARD` set without `actualReps` produces an `est_1RM` value treated as a ceiling. It contributes to the session aggregate *only if* the session's other-feedback estimate exceeds it; otherwise it's omitted. This prevents an upper-bound point from spuriously dragging coefficients down when the rest of the session already agrees with a value below the bound.

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

1. Collect `(est_coef_e,s, weight_e,s)` for every session `s` where the exercise appeared with a usable signal. No hard time cutoff — recency decay does the work.
   - `est_coef_e,s = est_1RM_e,s / muscleBaseline_at_s`
   - `weight_e,s = recency(s) × session_confidence_e,s`
   - `recency(s) = exp(-(now - sessionTime_s) × ln(2) / crossLiftIndependenceEstimate_half)` — at `Δt = crossLiftIndependenceEstimate_half`, recency = 0.5
2. Compute the **weighted median** of `est_coef_e,s` values, weighted by `weight_e,s`. This is `proposal_e`.
3. Compute `total_weight_e = Σ weight_e,s`.
4. Compute `proposal_confidence_e = Σ(recency_e,s × session_confidence_e,s) / Σ recency_e,s` — recency-weighted mean of session confidences. (Not weighted by `weight_e,s`, which would double-count confidence.)
5. Set a "definite point" flag `has_definite_e` = true iff any contributing point had `TOO_HARD` with `actualReps` (either user-tapped or backfill-inferred; the field doesn't distinguish them).
6. Record `session_count_e` = number of distinct contributing sessions.

If `total_weight_e < min_evidence_weight` AND `has_definite_e` is false: emit nothing for this exercise.

Output of H1 per exercise: `H1Proposal(proposal_e, proposal_confidence_e, total_weight_e, has_definite_e, session_count_e)`.

Weighted median is used rather than weighted mean because individual sessions are noisy (RIR buckets are wide) and median is robust to a single freak point. Over the recency-dominated horizon (a few half-lives, ~6 weeks of meaningful contribution), `c_e` is approximately stationary, so a median tracks the cluster of estimates well.

## Section 4: Heuristic H2 — Cross-Exercise Consensus

H2 modifies H1's output. It runs after H1 produces per-exercise proposals.

For each muscle group `m` with two or more exercises producing H1 proposals:

1. Compute `signed_log_ratio_e = log(proposal_e / current_coef_e)` for each exercise.
2. Examine the direction and magnitude across exercises in the muscle group:

```
let n = number of exercises with proposals in m
let mean_log_ratio = mean of signed_log_ratio_e in m

case (n, pattern):
  n == 1:
    pass through (no consensus check possible — H1's proposal stands at proposal_confidence_e)

  n ≥ 2 AND all signed_log_ratio_e same sign AND
    |mean_log_ratio| > crossLiftIndependenceEstimate_consensus_threshold:
    → likely baseline-engine drift, NOT coefficient miscalibration
    → suppress all proposals in m (emit nothing for this muscle group this round)
    → metadata on a suppressed-but-logged marker is out of scope (see Open Question 1)

  n ≥ 3 AND exactly one exercise e* has |signed_log_ratio_e*| > crossLiftIndependenceEstimate_outlier_threshold
    AND e* has session_count_e* ≥ 2
    AND every other exercise in m has |signed_log_ratio_e| < crossLiftIndependenceEstimate_consensus_threshold:
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

Why require `session_count_e* ≥ 2` for the outlier: a single high-confidence session (e.g., one TOO_HARD + actualReps) can solo-form an H1 proposal by bypassing `min_evidence_weight`. Without the session-count gate, that one freak session would qualify for the 1.0 confidence boost and drive a full-step update — then sit in the lookback long enough to keep firing the outlier path on subsequent recomputes. The gate forces at least one confirming session before the strongest path engages. Solo proposals still pass through via the mixed-signal branch at H1's native (≤ 0.95) confidence, where damping keeps the per-update step small.

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
| Recency half-life | `crossLiftIndependenceEstimate_half` | 14 days | sole time control; no hard cutoff. At Δt=28d recency ≈ 0.25, at 56d ≈ 0.06 |
| Min evidence weight | `min_evidence_weight` | 1.5 | roughly 2–3 medium-confidence sessions; bypassed by any `actualReps` point |
| Outlier minimum session count | `min_outlier_sessions` | 2 | the outlier exercise must have ≥ this many contributing sessions to qualify for the 1.0 confidence boost |
| Consensus suppression threshold | `crossLiftIndependenceEstimate_consensus_threshold` | `ln(1.05)` | ≈5% mean log-ratio; below this, mixed-signal path |
| Outlier detection threshold | `crossLiftIndependenceEstimate_outlier_threshold` | `ln(1.10)` | ≈10% log-ratio; outlier must diverge meaningfully |
| Damping rate | `α` | 0.2 | fraction of log-distance closed per update |
| Max log-step per update | `max_log_step` | `ln(1.05)` | ≈5% max change per recompute |
| Min change threshold | `min_change_threshold` | 0.005 | absolute coefficient change below this is dropped |

## Section 7: Architecture

### Input shape

`CoefficientComputationInput` is restructured to expose raw indexed data rather than pre-joined per-session bundles — heuristics group as they need. `SetSnapshot` and `ExerciseSessionSnapshot` are removed; the per-(session, exercise) grouping is a one-liner inside any heuristic that wants it.

```kotlin
data class CoefficientComputationInput(
    val sets: List<WorkoutSet>,                          // all completed sets, any session/exercise
    val sessionTimes: Map<Long, Long>,                   // sessionId → epoch millis
    val exerciseMuscle: Map<Long, MuscleGroup>,          // exerciseId → primary muscle
    val baselines: Map<Pair<Long, MuscleGroup>, Float>,  // (sessionId, muscle) → baseline at plan time
    val currentCoefficients: Map<Long, Float>,           // exerciseId → current coefficient
)
```

`WorkoutSet` already carries everything the per-set table in Section 2 reads (`targetWeight`, `targetReps`, `actualReps`, `feedback`), so heuristics read fields directly off the entity. `targetReps`, which used to be hoisted to the session level, now stays per-set where it actually lives.

### Heuristic

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

1. **Bucket**: group `input.sets` by `(sessionId, exerciseId)`. Drop buckets for bodyweight exercises (current coefficient `≤ 0`) and buckets with no usable sets (all `feedback` null / HURT).
2. **Per-bucket session signal**: from each bucket compute `est_1RM_e,s` per Section 2 (set table + session aggregation), then `est_coef_e,s = est_1RM_e,s / input.baselines[(sessionId, input.exerciseMuscle[exerciseId])]`. Apply recency from `(now - input.sessionTimes[sessionId])`.
3. **Per-exercise H1**: aggregate session signals per exerciseId into `H1Proposal(proposal, totalWeight, proposalConfidence, hasDefinitePoint)`.
4. **Apply H2** per muscle group, using `input.exerciseMuscle` to bucket H1 proposals — producing a `(exerciseId → (proposal, confidence, metadata))` map of survivors.
5. **Damp**: convert each survivor into a `CoefficientResult` via the Section 5 formula. Drop those below `min_change_threshold`.

## Section 8: Worked Examples

### Example A: simple convergence

User has done Barbell Bench Press 4 times in the last 21 days. Current `c_e = 1.00`, muscle baseline currently 80 kg.

Using `denom = -2.55 + 4.58·ln(80) ≈ 17.52`, est_1RM = `80 × (1 + (reps-1)^0.85 / 17.52)`. Recency uses `crossLiftIndependenceEstimate_half = 14d`, so `recency(Δt) = 2^(-Δt/14)`:

| Session | Δt | targetWeight | targetReps | feedback | reps used | est_1RM | est_coef | recency | weight |
|---------|----|---|---|---|---|---|---|---|---|
| s1 | 2d | 80 | 5 | RIR_2_4 | 8 | 103.9 | 1.30 | 0.91 | 0.91×0.7 = 0.64 |
| s2 | 7d | 80 | 5 | RIR_2_4 | 8 | 103.9 | 1.30 | 0.71 | 0.71×0.7 = 0.50 |
| s3 | 13d | 80 | 8 | RIR_0_1 | 9 | 106.7 | 1.33 | 0.53 | 0.53×0.85 = 0.45 |
| s4 | 20d | 80 | 5 | RIR_5_PLUS | 12 | 115.0 | 1.44 | 0.37 | 0.37×0.4 = 0.15 |

Weighted median of est_coef ≈ 1.30. `proposal_e = 1.30`, `total_weight ≈ 1.74`, `proposal_confidence ≈ 0.69`, `session_count_e = 4`.

Damping: `log_step = 0.2 × 0.69 × log(1.30/1.00) = 0.036`. Below cap (`ln(1.05) ≈ 0.0488`). `new_coef = 1.00 × exp(0.036) ≈ 1.037`.

Coefficient nudges from 1.00 to 1.04 — small move, conservative. Over several sessions of consistent evidence, it climbs toward 1.30; an old session that drops out of relevance fades smoothly via recency rather than dropping off a cliff.

### Example B: consensus suppression

Same muscle group (chest), three exercises with recent H1 proposals, all showing RIR_2_4 feedback at their planned weights.

- Barbell Bench Press: est_coef 1.06 vs current 1.00 → log_ratio +0.058
- Incline Barbell: est_coef 0.91 vs current 0.85 → log_ratio +0.068
- Dumbbell Bench: est_coef 0.43 vs current 0.40 → log_ratio +0.072

All same sign, `mean_log_ratio = 0.066`, exceeds `crossLiftIndependenceEstimate_consensus_threshold = ln(1.05) ≈ 0.049`. → **suppressed**. The user got stronger; baseline will catch up next session. Coefficients untouched. Metadata: `consensus_suppressed:m=CHEST,mean_log_ratio=0.066`.

### Example C: outlier detection

Three back exercises with recent H1 proposals:

- Barbell Row: est_coef 1.01 vs current 1.00 → +0.010
- Lat Pulldown: est_coef 0.79 vs current 0.80 → −0.013
- Seated Cable Row: est_coef 0.96 vs current 0.75 → **+0.247**

Two near-zero, one strongly positive and the outlier. `crossLiftIndependenceEstimate_outlier_threshold = 0.0953`. Assume Seated Cable Row has `session_count_e = 3` (≥ 2 required) — outlier qualifies. → Update Seated Cable Row with boosted confidence (1.0). Barbell Row and Lat Pulldown suppressed this round. Metadata on the row update: `consensus_outlier:m=BACK,sibling_count=2`.

(If Seated Cable Row had only one contributing session — e.g., a freak TOO_HARD + actualReps that bypassed `min_evidence_weight` — the session-count gate would fail and H2 would fall through to the mixed-signal path, emitting the proposal at H1's native ≤ 0.95 confidence. The next session would either confirm the outlier signal or wash it out.)

Damping with confidence 1.0: `log_step = 0.2 × 1.0 × 0.247 = 0.0494`, clamped to `ln(1.05) = 0.0488`. `new_coef = 0.75 × exp(0.0488) = 0.787`. Coefficient moves 0.75 → 0.79 this round. Several more rounds of similar evidence will converge it toward 0.96.

## Section 9: Testing

All tests live on the JVM — the heuristic is a pure function over `CoefficientComputationInput`.

**Set-level signal extraction:**
- Each feedback type yields the expected `(est_1RM, confidence)` tuple
- `null` and `HURT` are skipped
- `TOO_HARD` with `actualReps = null` produces an upper-bound point
- `TOO_HARD` with `actualReps = 3` (at targetReps=8) yields `toOneRepMax(weight, 3)` at confidence 0.95

**Session aggregation:**
- Multiple sets at the same weight combine via confidence-weighted mean
- A reduced-weight set after `TOO_HARD` is included as an additional point
- A session with all-null sets contributes nothing
- Upper-bound `TOO_HARD` is omitted when other points already agree below the bound

**H1 (per-exercise aggregation):**
- Empty history → no proposal
- Single high-confidence session below `min_evidence_weight` → no proposal unless it's `TOO_HARD + actualReps`
- Old sessions are still included but contribute negligibly via recency decay (e.g., a 60-day-old session has weight ≈ 0.05)
- Recency weighting: a recent low-confidence session and an old high-confidence session contribute appropriately
- Weighted median ignores a single outlier session

**H2 (consensus):**
- One exercise per muscle group → pass-through
- All exercises same direction with mean log-ratio above threshold → all suppressed (empty result for that muscle group)
- All exercises same direction with mean log-ratio below threshold → mixed-signal path; all pass through
- Three exercises, one outlier above outlier threshold with `session_count_e* ≥ 2` → outlier emitted with boosted confidence; others suppressed
- Three exercises, one outlier above outlier threshold but `session_count_e* = 1` → mixed-signal path (gate fails); outlier emits at H1 confidence
- Three exercises, one outlier below outlier threshold → mixed-signal path

**Damping:**
- Proposal equals current coefficient → no result emitted (below `min_change_threshold`)
- Large proposal change → log-step clamped to `max_log_step`
- Small proposal change → log-step proportional to confidence and distance

**Integration / end-to-end:**
- Realistic `CoefficientComputationInput` (multiple exercises, multiple sessions, mixed feedback) produces a sensible set of `CoefficientResult`s with non-empty metadata strings

## Section 10: Out of Scope

- **Distinguishing user-tapped from backfill-inferred `actualReps`.** Both are read identically and contribute at confidence 0.95. The backfill's inversion is tight (0.5 kg tolerance, rounding-tie resolves to the higher rep candidate); residual error is well below the damping rate and not worth a separate confidence bucket.
- **UI surfacing of coefficient changes.** No new screens, indicators, or change-log visualization. The user-visible effect is solely through adjusted session weights.
- **Parameter tuning from data.** Defaults in Section 6 are starting values. Empirical tuning is a follow-up activity once the heuristic has produced a few weeks of real updates.
- **Multiple heuristics + sophisticated merge.** First heuristic only. `mergeHeuristicResults` stays as first-non-null-wins.
- **Pure ratio-only heuristic (H4 from brainstorming).** Considered and deferred. H1+H2 covers similar ground for the common case where the baseline engine is reasonably accurate. Revisit if baseline-engine error becomes a dominant source of coefficient noise.

## Section 11: Open Questions

1. **Should the heuristic write a `CoefficientChangeLog` entry with `coefficient = current` (no-op row) when H2 suppresses a proposal, for visibility?** Argument for: makes the log explainable when the user wonders why nothing changed. Argument against: clutter; the metadata-bearing row is only meaningful when something actually changed. **Working assumption: no — suppression is silent.**

2. **First-update bootstrap.** When `current_coefficient` comes entirely from the seed (`ExerciseCoefficients`) — i.e., the exercise has never had a user-coefficient row — should the first update be damped (same as any other) or allowed to take a larger step? **Working assumption: same damping. The seed coefficient is a reasonable prior and shouldn't be overridden on a single recent batch of evidence.**

3. ~~Should sessions older than the window contribute nothing, or contribute at very low weight via the recency decay?~~ **Resolved.** No hard cutoff; `crossLiftIndependenceEstimate_half = 14d` recency decay handles the fade naturally.

## Files Changed

| File | Change |
|------|--------|
| `domain/EstCoefConsensusHeuristic.kt` | new — implements `CoefficientHeuristic` (H1 + H2 + damping) |
| `domain/CoefficientHeuristic.kt` | remove `SetSnapshot` and `ExerciseSessionSnapshot`; restructure `CoefficientComputationInput` to `sets` + `sessionTimes` + `exerciseMuscle` + `baselines` + `currentCoefficients` |
| `domain/WorkoutRepository.kt` | rebuild `buildCoefficientInput` to populate the new input shape (load sets, index sessions, build muscle and baseline maps) |
| `StochasticStrengthApp.kt` | register `EstCoefConsensusHeuristic` in `WorkoutRepository` construction |
| `test/.../EstCoefConsensusHeuristicTest.kt` | new — unit tests per Section 9 |
