# Baseline adaptation heuristic redesign

## Background

The previous heuristic (`DefaultProgressionEngine.computeNextBaseline`) takes a
list of `SetFeedback` values, averages them into a bracket score, and applies a
fixed percentage step (+7.5 / +5 / +2.5 / 0 / -5 / -10 %), with `HURT` short
-circuiting to ×0.85 and floors of 0.5 / 1.0 / 2.5 kg ensuring some movement at
low weights. The heuristic ignores actual weight, est1RM, exercise coefficients,
and history.

In parallel the project has shipped:

- a load-aware 1RM formula in `DefaultProgressionEngine.rawToOneRepMax` /
  `rawFromOneRepMax`
- `EstCoefConsensusHeuristic` — per-exercise coefficient adjustment via per-set
  est1RM signals, confidence-weighted session aggregates, recency-decayed pool,
  cross-exercise consensus checks, and log-space damped emit
- `SeedNormalizer` — anti-drift normalization that re-attributes coefficient
  drift to the baseline

The bracket-score baseline heuristic is now the only piece of the engine that
does not reason about est1RM. This design replaces it with a per-muscle
heuristic that targets the session's implied 1RM, mirroring the structure of
`EstCoefConsensusHeuristic` but acting on baselines instead of coefficients.

## Goals

Steady state:

- baseline tracks the user's actual est1RM for the muscle group
- as the user gets stronger, baseline grows with them
- a single anomalous or injury session does not jump the baseline far

Discover phase:

- new users will not have a good initial est1RM estimate
- come down from above quickly when sets fail
- come up from below quickly but not so fast as to risk injury

## Architecture

### New interface

A new `BaselineHeuristic` interface lives alongside `CoefficientHeuristic` and
`BaselineNormalizer` in `domain/`:

```kotlin
data class BaselineComputationInput(
    val sets: List<WorkoutSet>,                          // this session's sets only
    val exerciseMuscle: Map<Long, MuscleGroup>,
    val currentCoefficients: Map<Long, Float>,           // pre-session
    val currentBaselines: Map<MuscleGroup, Float>,       // pre-session
    val recentHistory: Map<MuscleGroup, List<BaselineHistory>>, // window for safety layer
    val sessionReps: Int,
    val minReductionFractions: Map<MuscleGroup, Float>,  // manual override carry-over
    val asOf: Long,
)

data class BaselineProposal(
    val muscleGroup: MuscleGroup,
    val newBaseline: Float,
    val metadata: String?,
)

interface BaselineHeuristic {
    val name: String
    fun compute(input: BaselineComputationInput): List<BaselineProposal>
}
```

The default implementation is `EstBaselineConsensusHeuristic` in
`domain/EstBaselineConsensusHeuristic.kt`.

### Engine cleanup

`ProgressionEngine.computeNextBaseline` is removed from the interface and
`DefaultProgressionEngine`. Helpers that exist solely for the bracket score —
`scoreFromFeedbacks`, `applyScoreBaseline`, `feedbackPoints`,
`weightIncreasedWithFloor`, `weightDecreasedWithFloor`, `weightDecreased` — are
deleted. `toOneRepMax` / `fromOneRepMax` / `scaleReps` / `repOptions` stay.

### Wiring

`StochasticStrengthApp` gains a lazy
`baselineHeuristic: BaselineHeuristic = EstBaselineConsensusHeuristic()`,
constructed alongside `heuristic` and `normalizer`. `WorkoutRepository` takes
`baselineHeuristic` as a constructor parameter (non-nullable: this is the
primary mechanism, not optional like the coefficient heuristic).

`applySessionProgression` is rewritten:

1. Build a `BaselineComputationInput` from the snapshot + this session's sets.
2. Call `baselineHeuristic.compute(input)` → `List<BaselineProposal>`.
3. For each proposal: upsert `MuscleGroupStrength`, update
   `snapshot.currentBaselines` and `snapshot.progressionBaselines`, write a
   `BaselineHistory` row, append to `snapshot.baselineHistoryByMuscle`.
4. Call `recomputeCoefficients` and `applyBaselineNormalization` as today.

The per-muscle iteration over `exercisesByMuscle` moves into the heuristic.

### ReplaySnapshot

A new mutable field is added:

```kotlin
val baselineHistoryByMuscle: MutableMap<MuscleGroup, MutableList<BaselineHistory>> =
    mutableMapOf()
```

It is appended to by `applySessionProgression` after each `BaselineHistory`
write, in session order. The heuristic reads it through `recentHistory`.
Replay deterministically rebuilds it from scratch.

## Algorithm

For each muscle group with sets in the session:

1. **HURT short-circuit.** If any set in the muscle group has
   `feedback == HURT`, emit `B_new = round(B_old × 0.85)` with
   `metadata = "hurt"`. Skip everything else.

2. **Per-set signals.** Reuse `EstCoefConsensusHeuristic.setSignal(set)`
   verbatim — same Mayhew-based est1RM, same confidence, same `isUpperBound` /
   `isDefinite` flags. The set-signal code lives where the coefficient
   heuristic owns it; the baseline heuristic depends on it.

3. **Implied baselines.** For each signal, compute
   `impliedBaseline = est1RM / coefficient[exerciseId]`. Drop sets whose
   exercise has no coefficient or `coef ≤ 0`. If every set in the muscle is
   dropped, emit no proposal for the muscle.

4. **Session aggregate.** Apply the same upper-bound dropping and
   confidence-weighted mean as `aggregateSession`:
   - Compute the confidence-weighted mean of non-upper-bound implied baselines.
   - Drop any upper-bound signal whose `impliedBaseline` is below that mean.
   - Recompute the confidence-weighted mean over the surviving set. This is
     `B_target`. Its session confidence is the mean confidence of survivors.

5. **Raw log step.** `raw = alpha * sessionConfidence * ln(B_target / B_old)`.
   `alpha = 0.3f`.

6. **Asymmetric cap with safety layer.**
   - Base caps: `stepUpMax = ln(1.025f)`, `stepDownMax = ln(1.10f)`.
   - Read `recentHistory[muscle]`, filter to `timestamp >= asOf - safetyWindowMs`
     (`safetyWindowMs = 14 days`) and drop rows with
     `changeReason == INITIAL` (sentinel `previousBaseline` makes their sign
     meaningless). Define `signs` as the ordered list of
     `sign(newBaseline - previousBaseline)` over the surviving entries (skip
     zeros).
   - **Oscillation:** if `signs` has ≥ `safetyOscillateFlips = 2` adjacent
     sign flips, `stepUpMax *= 0.5f`.
   - **Consistent up:** if the last `safetyConsistentLength = 3` entries of
     `signs` are all positive, `stepUpMax *= 2.0f`. (HURT drops are
     automatically negative and so cannot appear in this window — no
     explicit HURT cross-check needed.)
   - Oscillation and consistent-up are evaluated independently; both could
     trigger in pathological cases. If both fire on the same window, net
     effect is `stepUpMax * 1.0f`. Down cap is never modified.
   - `clamped = raw.coerceIn(-stepDownMax, stepUpMax)`. Record the effective
     cap actually used: `effectiveCap = if (raw > 0f) stepUpMax else
     stepDownMax`.

7. **Apply and round.** `B_new = WeightFormatter.round(B_old × exp(clamped),
   unit)`.

8. **Floor when the cap binds.** If `abs(raw) > effectiveCap` (the cap
   actually clipped the signal) **and** `B_new == B_old` (rounding zeroed
   the result), force `B_new = B_old ± minIncrement(unit)` in the direction
   of `raw`. The floor never fires for small organic signals whose raw step
   was inside the cap and merely rounded to no-op.

   `minIncrement(unit)` returns `2.5f` for KG and `5f / 2.20462f` for LBS,
   added to `WeightFormatter` as a public helper.

9. **Manual minReductionFraction cap.** If
   `minReductionFractions[muscle]` is provided (`m > 0`), cap `B_new` at
   `B_old × (1 - m)`. Same intent as today, applied after the heuristic.

10. **No-op suppression.** If `B_new == B_old`, emit no proposal for the
    muscle.

11. **Metadata.** Emit a readable string, e.g.
    `"target=132.5,conf=0.78,step=+1.0%,safety=consistent_up"` (or
    `safety=default` / `safety=oscillating` / `safety=hurt`). Used by the
    debug screen.

### What's dropped from the old algorithm

- The "max-reps + mixed TOO_HARD" zero-score rule. The per-set est1RM signal
  now handles this case naturally: a TOO_HARD set produces a low est1RM, an
  RIR_5_PLUS produces a high one, and the confidence-weighted aggregate
  averages between them.
- The fixed-floor increments inside the bracket math (0.5 / 1.0 / 2.5 kg
  minimums). The floor in step 8 is the principled successor: it fires only
  when the cap clipped a real signal and rounding zeroed the result.

### Tunable defaults

| Constant                 | Value                         |
| ------------------------ | ----------------------------- |
| `alpha`                  | `0.3f`                        |
| `stepUpMax`              | `ln(1.025f)` (~2.5%)          |
| `stepDownMax`            | `ln(1.10f)` (~10%)            |
| `hurtFactor`             | `0.85f`                       |
| `safetyWindowMs`         | `14L * 24 * 60 * 60 * 1000`   |
| `safetyOscillateFlips`   | `2`                           |
| `safetyConsistentLength` | `3`                           |

## Data model changes

### BaselineHistory schema

Two new nullable columns mirror `CoefficientHistory`:

- `heuristicName: String?`
- `heuristicMetadata: String?`

Existing columns (`feedbacks`, `sessionReps`, `minReductionFraction`) stay
nullable and remain populated where applicable (HURT-only rows still get
`feedbacks = "HURT"` for backward compat with the debug UI).

### Room migration

`AppDatabase` bumps from version 11 to 12 with a `Migration(11, 12)` that
runs:

```sql
ALTER TABLE baseline_history ADD COLUMN heuristicName TEXT
ALTER TABLE baseline_history ADD COLUMN heuristicMetadata TEXT
```

Pre-existing rows keep `null` for both. The debug UI falls back to `feedbacks`
when `heuristicMetadata` is null.

## Replay and backfill

`WorkoutRepository.replayDerivedState` runs `applySessionProgression` per
session in time order. The new heuristic reads from
`snapshot.baselineHistoryByMuscle`, which is rebuilt in order during replay,
so the safety layer sees only history entries with `timestamp <= asOf`.

A user-invoked recompute regenerates all baselines from scratch with the new
heuristic. Resulting baselines may differ from the values stored prior to the
migration; this is expected and desired.

## Testing

### Unit tests for `EstBaselineConsensusHeuristic`

Pure JVM tests in `app/src/test/`. One test per behavior:

1. HURT short-circuit ⇒ `round(B_old × 0.85)`, no safety interaction.
2. Single RIR_2_4 set, plain inputs ⇒ step direction correct, within up cap.
3. Up cap binds ⇒ capped step ≈ `+2.5%`.
4. Down cap binds ⇒ capped step ≈ `-10%`.
5. Floor fires: B_old = 20, confident large signal, cap binds, rounding
   would zero ⇒ `B_new = 22.5`.
6. Floor does NOT fire: B_old = 100, organic small signal (raw < cap) rounds
   to no-op ⇒ no proposal.
7. `minReductionFractions[muscle] = 0.05`, heuristic proposes 100 from B_old
   = 100, but cap forces ≤ 95 ⇒ result = 95.
8. Two exercises in one muscle group, different coefficients ⇒ aggregate
   weighted by confidence; spot-check arithmetic.
9. Upper-bound drop: one TOO_HARD-without-actual-reps alongside several
   RIR_2_4 sets with higher implied baseline ⇒ upper-bound dropped from
   aggregate.
10. Safety oscillating: 4 alternating-sign history rows in window ⇒ up cap
    halved; verify capped step ≈ `+1.25%`.
11. Safety consistent up: last 3 history rows positive ⇒ up cap doubled;
    verify capped step ≈ `+5%`.
12. Safety does not affect down: oscillating history + strong down signal ⇒
    down cap stays at 10%.
13. No-op suppression: B_target ≈ B_old ⇒ empty result for that muscle.
14. History outside window: positive entries older than 14 days ⇒ ignored;
    safety layer sees empty window, defaults reign.
15. `INITIAL` rows in window are skipped from `signs`.

### Repository tests

`WorkoutRepositoryTest`, `ReplayDerivedStateTest`, `LiveInputWritesTest`,
`DerivedStateBackfillTest` are updated to inject a deterministic fake
`BaselineHeuristic` (e.g., one that always proposes `B_old * 1.05`). This
keeps repository tests focused on plumbing — DB writes, replay ordering,
history rows — and decouples them from heuristic tuning.

The real `EstBaselineConsensusHeuristic` is exercised only by its dedicated
unit tests.

### Migration test

An instrumented test opens an AppDatabase v11 with a fixture
`BaselineHistory` row, migrates to v12, and asserts:

- the row is still readable
- `heuristicName` and `heuristicMetadata` are null
- a new row can be inserted with non-null values for both columns

## Risks and edge cases

- **Exercise with missing coefficient in a session.** Sets are dropped from
  the implied-baseline aggregate. If every set in a muscle is dropped, no
  proposal — baseline unchanged. Acceptable.
- **Sets with no feedback.** `setSignal` already returns null; sets contribute
  nothing. No change from today.
- **Replay determinism.** Safety layer reads `recentHistory` from the
  snapshot, which is appended in session order. Same input → same output.
- **Old `BaselineHistory` rows.** Pre-migration rows have null heuristic
  columns. Safety layer only cares about sign and timestamp; it works on
  those rows fine. Debug UI uses `feedbacks` as fallback display.
- **First session ever for a muscle.** Empty history window → no safety
  rules trigger → default caps. Correct.
- **Both safety rules firing.** Mathematically possible if 4-entry window has
  2 flips and the last 3 are positive (e.g., signs `-, +, +, +`). Net effect
  is no change to up cap. Acceptable.

## Out of scope

- Initial baseline seeding (`StartingWeights`) is untouched. The heuristic
  acts on updates only.
- `SeedNormalizer` and `EstCoefConsensusHeuristic` are untouched.
- The progression engine's 1RM math is untouched.
