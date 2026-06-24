# Per-Exercise Estimate Progression — Design

Date: 2026-06-21

## Motivation

The current progression system models strength as a **rank-1 factorization**:
`expected1RM(exercise) = baseline_muscle × coef_exercise`. The product has a gauge
freedom — `(baseline·k, coef/k)` prescribes identical weights — and essentially all of
the recent complexity in `RollingConservingProgressionController` exists to manage that
freedom: a common/differential split, a Huber `RobustCenter`, gauge conservation, a
separate geomean reclaimer, and a bracket-confidence "snap" path that interpolates four
config knobs. This is a control-theory treatment of an identifiability problem.

This redesign removes the identifiability problem at its root by making the
**per-exercise estimate the primary learned quantity**. The muscle baseline and the
per-exercise coefficient stop being tracked, evolving state and become derived views.

### Goals (unchanged product behavior)

1. A well-calibrated weight is achievable at RIR 0–1 on the last (most-fatigued) set.
2. Exercises cross-inform each other so a sparsely-trained lift is still prescribed well.
3. Down-signals within an exercise are respected — a weight that failed is not
   re-prescribed next session.

### Framing principles

- The "estimated 1RM" we show users is presentation, not the internal truth.
- The seed coefficients are LLM guesses: vaguely correct, not gospel.
- The baseline is inaccurate early and goes stale; per-exercise estimates that each
  track their own data avoid a single stale latent.

## Architecture

The core entity flips from muscle to exercise. Each loaded exercise carries:

```
Estimate {
  exerciseId
  lnE          // log of this exercise's estimated 1RM (kg)
  confidence   // recency-decayed effective sample size, >= 0
  updatedAt
}
```

`lnE` is the single source of truth for prescription. The muscle baseline and the
per-exercise coefficient are derived:

- **coefficient(i)** is only ever a *ratio* between siblings: observed `E_i / E_j` when
  both are confidently trained, else the seed ratio `seedCoef_i / seedCoef_j`. Never
  persisted as evolving state.
- **muscle "strength"** (the est-1RM shown to the user) is a confidence-weighted
  aggregate of the muscle's estimates. Display only.

`Estimate` is **derived state** held in `DerivedStateStore`, reconstructed every launch
by `replayDerivedState`. It is not a persisted table.

### Data flow per session

1. `SessionSignalExtractor` runs unchanged → per-exercise `(est1RM_obs, w_obs)` plus the
   drop-cascade `bracketConfidence`.
2. **Write (local):** fold the session signal into *only that exercise's* `Estimate`.
   A failure moves nothing but the failed exercise — Goal 3 is structural.
3. **Read (pooling):** when the planner needs a weight for exercise *i*, blend *i*'s own
   estimate with a sibling prediction, weighted by *i*'s confidence — Goal 2. Read-time
   only, so cross-informing never contaminates stored per-exercise state (that
   contamination was the source of the gauge machinery).

## Data model & migration

**Persisted truth becomes per-exercise** (was per-muscle). One table keyed by
`exerciseId`, mirroring today's `BaselineOverride` shape (an `initial` flag + reason,
`asOf`, optional `sessionId`), storing an **e1rm value per exercise**:

- **Initials** — user-selected starting strength, now per exercise.
- **Overrides** — manual edits and detraining, now per exercise.

This fixes a latent bug: today a manual edit of one exercise's weight at PlanPreview is
backed out into a whole-muscle baseline via `deriveBaselineFromSessionWeight`
(`WorkoutSessionController`), so editing one exercise silently moves its siblings.
Per-exercise overrides make the edit apply strictly to the exercise it targets.

### Migration (Room v16 → v17, proper `Migration`)

Expand every existing muscle-keyed `BaselineOverride` row into per-exercise rows —
`value × seedCoef(exercise)` for each loaded exercise in that muscle, same
`asOf`/`sessionId`/reason. Zero-coefficient (unloadable) exercises get no row. This
reproduces the weights the old `baseline × coef` model would have prescribed at each
event, so day-one behavior is continuous; per-exercise trajectories then evolve under the
new algorithm. Update `MigrationTest` forward lists and the exported schema JSON.

### Authoring changes

- `seedInitialWeights` writes per-exercise initials (see per-exercise seeding below).
- Manual edit at PlanPreview sets that exercise's e1rm directly
  (`toOneRepMax(newWeight, reps)`); `deriveBaselineFromSessionWeight` and the muscle
  projection are deleted.
- Detraining writes one per-exercise override per affected exercise
  (`currentEstimate × factor`), preserving the uniform haircut with no UI change.

### Per-exercise seeding

Keep the muscle×coef formula as the common denominator (the migration needs it, since
existing users only have muscle baselines). Extend `StartingWeights`:

```
exerciseSeedE1rm(sex, level, exercise): Float?   // curated real per-lift numbers; null if absent
seedInitialE1rm(...) = exerciseSeedE1rm(...) ?: muscleReference(sex, level, muscle) × seedCoef(exercise)
```

**Scope for this effort:** ship the mechanism + fallback only. The curated table starts
empty (or a handful of entries) and is filled incrementally later; new-user behavior is
identical to today until then. `ExerciseCoefficients` and the muscle reference table both
remain alive for migration, the seed fallback, and the ratio fallback.

## Update, confidence, and pooling math

All math is in **log space** (strength is multiplicative). Per exercise: `lnE`,
`confidence c`, `updatedAt`.

**Decay (applied before any use):** `c ← c · 0.5^(Δt / halfLife)`, reusing the ~21-day
half-life. Stale ⇒ low confidence ⇒ leans on siblings.

**Write — session update, local to one exercise:**
```
lnE ← (c · lnE + W · ln est1RM_obs) / (c + W)
c   ← min(c + W, Cmax)             // cap keeps it adaptive long-term
```
**Asymmetric W (makes Goal 3 airtight):** when the observation is *below* the current
estimate — a failure / low-RIR session, signalled strongly by `bracketConfidence` — `W`
is large, so the point snaps down toward the demonstrated ceiling and the failed weight
is not re-prescribed. When *above*, `W` is the gentle progressive-overload weight.
`SessionSignalExtractor` already caps a failed session's `est1RM_obs` at the failed
weight's implied capacity, so the snap-down lands below the weight that failed. The
repurposed `bracketConfidence` thus feeds `W` instead of a separate snap path.

**HURT** stays muscle-level: multiply `lnE` of the muscle's exercises by the 0.85 factor
(a forced down-override).

**Read — prescription, where cross-informing happens (Goal 2):**
- Muscle level from confident siblings: `L = conf-weighted geomean over j of (E_j / coef_j)`.
- Sibling prediction for *i*: `pred_i = coef_i · L`. Writing `L` and `pred_i` with the
  absolute seed coefficient is just a convenient representation — `pred_i = coef_i ·
  geomean(E_j / coef_j)` depends only on the ratios `coef_i / coef_j`, so the absolute
  level cancels and no gauge is introduced.
- Ratio refinement (second-order): where both *i* and a sibling *j* are confidently
  trained, use their observed ratio `E_i / E_j` in place of the seed ratio, so a wrong LLM
  seed self-corrects once both lifts have data (helping the next cold variant).
- Confidence shrink: `lnE_used = (c_i · lnE_i + κ · ln pred_i) / (c_i + κ)`. Confident ⇒
  trusts itself; cold ⇒ uses the muscle prediction; brand-new muscle ⇒ `pred` falls back
  to the seed initial, so `E_used = seed`.
- Weight = `round(fromOneRepMax(E_used, sessionReps))`, unchanged.

**Tuning constants** (pinned by the simulation test): `halfLife`, `Cmax`, `κ`, the
up/down `W` values, and the "confident sibling" confidence threshold.

## Components

New, small, pure, independently testable:

- `ExerciseEstimateUpdater` — `(prior, sessionAggregate, now) → newEstimate`. Decay,
  asymmetric-W log-EMA, confidence cap, HURT. Replaces the controller write path.
- `SiblingPredictor` — `(targetId, allEstimates, seedCoefs, now) → effectiveE1rm`. Muscle
  level, prediction, confidence shrink. The read path.
- `DerivedStateStore` swaps its baseline + coefficient projections for one
  `Map<exerciseId, Estimate>`.

**Wiring:** `replayDerivedState` initializes estimates from the per-exercise initials,
folds each session through `ExerciseEstimateUpdater`, and applies per-exercise override
events as forced estimate sets. `applySessionProgression` shrinks to "extract observations
→ update estimates." `WorkoutPlanner.weightForExercise` calls `SiblingPredictor` instead
of `baseline × coef`.

**Deleted:** `RollingConservingProgressionController`, `RobustCenter`,
`ProgressionStepInput/Output/Observation`, the gauge/reclaimer/snap config, the
`seedCoefficients` reclaim plumbing, and `deriveBaselineFromSessionWeight` + the muscle
projection in `WorkoutSessionController`.

**Kept:** `SessionSignalExtractor` (`bracketConfidence` repurposed as a strong
down-signal driving `W`), `DefaultProgressionEngine`, `ExerciseCoefficients` (now
read-only: migration, seed fallback, ratio fallback).

**Read-site audit:** everything that read the muscle baseline / coefficient projections
(home strength display, history, debug screens) now reads the derived muscle aggregate `L`
or per-exercise `E`. Enumerated during planning.

## Testing

- Rewrite the simulation test around per-exercise estimates using the same synthetic-lifter
  harness (real library, real planner, cross-set fatigue, optional growth).
  - **Keep:** last fatigued set near RIR 0–1; failRate ≤ 0.20; convergence budget; bounded
    jitter; static-lifter non-divergence.
  - **Replace:** the gauge / `coefInflation` assert (no gauge anymore) with "muscle
    aggregate tracks truth."
  - **Add:** Goal-2 assert — a cold exercise with well-trained siblings is prescribed near
    its true capacity. Goal-3 assert — after a failure, the next prescription for that
    exercise is strictly below the failed weight.
- Unit tests for `ExerciseEstimateUpdater` and `SiblingPredictor` (pure functions).
- Adapt the Bulgarian-bracket characterization test to the estimator.
- DB: `AppDatabase` v16→v17 + `Migration_16_17`; update `MigrationTest` forward lists and
  the schema JSON; test that the muscle→per-exercise override expansion is correct.

## Out of scope

- Curating the full per-exercise seed table (mechanism + fallback only here).
- Changes to the PlanPreview UI beyond the override-semantics fix.
- Any change to the rep-range, detraining trigger UI, or duration estimation.
