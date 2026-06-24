# Per-exercise progression chart + cross-tuning bars

**Date:** 2026-06-23
**Status:** Approved, pending implementation plan

## Problem

The progression engine has been substantially reworked (per-exercise log-space
estimates, read-time pooling via `MuscleStrengthProjector`), but the Debug &
Advanced area still visualizes the *old* model: the per-exercise detail screen
shows a "Coefficient over time" line and a "Coefficient vs seed" deviation list.
Neither surfaces what the current engine actually does — fold per-session
observations into a per-exercise estimate, then cross-inform exercises within a
muscle at read time.

We want the per-exercise detail screen to show, in estimated-1RM space, how the
engine arrives at its prescription for that exercise, plus a muscle-wide view of
how well the exercises are cross-tuned.

## Goals

Per exercise, a single chart in **estimated 1RM (kg) space** showing:

1. **Filled dots** — the exercise's own per-session observed implied 1RM.
2. **Hollow dots** — sibling exercises' observed 1RMs, rescaled into the
   primary's space.
3. **Own estimate** line — the exercise's own `ExerciseEstimate` over time.
4. **Siblings (leave-one-out)** line — what the *other* exercises in the muscle
   predict for this exercise.
5. **Merged** line — the engine's actual `effectiveE1rm` (what gets prescribed).

Plus muscle-wide **cross-tuning bars**:

- **Agreement vs consensus** — how far each exercise's own estimate sits from
  the leave-one-out pool prediction.
- **Contribution** — each exercise's share of the muscle's total decayed
  confidence (who actually drives the level).

## Non-goals

- No schema or DB changes — no new durable state is introduced.
- No change to prescription/progression behavior. The engine refactor in this
  spec is a pure extraction guarded by existing tests.
- The `DebugStatsScreen` list (muscle grid, recent/all coefficients) is
  untouched.

## Background: data availability

- **Durable:** the full set log; the per-exercise estimate map (current value
  only); per-exercise strength overrides.
- **Persisted derived time-series (in `DerivedStateStore`):** muscle **level**
  (`baseline_history`) and per-exercise **derived coefficient**
  (`coefficient_history`).
- **Not persisted:** the trajectory of each exercise's own `lnE`, and any
  leave-one-out pool. Both required series must therefore be **recomputed on
  demand**.

Mapping each series to its source:

| Series | Source |
| --- | --- |
| Filled dots (own observations) | `SessionSignalExtractor.aggregateSession(targetSets).est1RM` per session |
| Hollow dots (siblings) | `obs_j × seedCoef[target] / seedCoef[j]` per session per sibling |
| Own estimate line | `currentEstimates[target].e1rm` after each session, via replay |
| Siblings (leave-one-out) line | `MuscleStrengthProjector.project(ids − target).level × seedCoef[target]` |
| Merged line | engine's true `effectiveE1rm[target]` from the full projection |

Decisions:

- **Dots are per-session** (the aggregate the engine folds), not per-set.
- **Siblings line is leave-one-out** (excludes the target's own vote) — a genuine
  "what do my siblings predict for me" line.
- The **merged** line stays the engine's real `effectiveE1rm` (a confidence-blend
  of own estimate and the *all-in* pool). With a leave-one-out siblings line it
  will sit close to, but not exactly between, own and siblings. Labeling makes
  the distinction clear.

## Design

### 1. Engine refactor — extract a per-session stepper

`WorkoutRepository.applySessionProgression(sessionId, snapshot, asOf, scratch)`
currently inlines: HURT (muscle-level) → per-exercise fold → per-affected-muscle
projection → writes to `scratch`.

Extract the pure core into `SessionProgressionStepper`
(`domain/progression`). It mutates `snapshot.currentEstimates` in place (as
today) and **returns** the affected muscles together with their
`MuscleProjection`s. The repo's `applySessionProgression` becomes:

1. `stepper.step(...)` → `StepResult` (affected muscles + projections).
2. Translate the result into the existing `writeLevelUpdate` /
   `writeDerivedCoefficients` calls against `scratch`.

Persistence (`scratch` writes, epsilon-dedupe) stays in the repo. The fold +
projection becomes reusable for the series builder.

**Guard:** `ExerciseEstimatorSimulationTest` and `ReplayDerivedStateTest` must
remain green — they are the proof the extraction changed no behavior.

### 2. `ExerciseProgressionSeriesBuilder` (domain)

Given the static replay snapshot (sessions sorted by end time, sets per session,
`seedCoefficients`, `exerciseMuscle`, `muscleExerciseIds`) and a target
`exerciseId`:

1. Resolve the target's muscle `m` and its exercise ids.
2. Replay `m`'s exercises across all sessions through the **same**
   `SessionProgressionStepper` (estimates evolve identically to production;
   HURT is muscle-level so a muscle-scoped replay is self-contained).
3. After each session that touched `m`, sample at the session's `asOf`:
   - `ownEstimate` = `currentEstimates[target].e1rm`
   - `merged` = `projection.effectiveE1rm[target]` (full projection)
   - `siblingsEstimate` = `project(m ids − target).level × seedCoef[target]`
4. For each session containing the target's sets, add a filled-dot sample =
   `aggregateSession(targetSets).est1RM`.
5. For each session, for each sibling `j` with sets, add a hollow-dot sample =
   `aggregateSession(jSets).est1RM × seedCoef[target] / seedCoef[j]`.

Output:

```kotlin
data class ExerciseProgressionSeries(
    val ownEstimate: List<DebugChartPoint>,        // line
    val siblingsEstimate: List<DebugChartPoint>,   // leave-one-out line
    val merged: List<DebugChartPoint>,             // line (engine effectiveE1rm)
    val ownObservations: List<DebugChartPoint>,    // filled dots
    val siblingObservations: List<DebugChartPoint>,// hollow dots
)
```

All values in 1RM (kg); the screen formats to the user's weight unit.

Edge cases:

- Target is the muscle's only exercise → leave-one-out pool = seed prior only →
  siblings line sits at `seedLevel × seedCoef[target]`.
- Zero/null-coefficient (unloadable) siblings are skipped (no dot, no vote),
  matching the engine.

### 3. Chart component

New `ExerciseProgressionChart` composable generalizing the Vico setup in
`DebugLineChart`. It accepts a list of styled series:

```kotlin
data class ChartSeries(
    val points: List<DebugChartPoint>,
    val style: SeriesStyle, // LINE(color) | FILLED_DOTS(color) | HOLLOW_DOTS(color)
)
```

- `LINE` — visible line, no points.
- `FILLED_DOTS` — transparent line, filled point.
- `HOLLOW_DOTS` — transparent line, stroke-only point.

Shared epoch-day x-axis and weight y-formatter (reusing the existing
`timestampToLocalEpochDay` / `epochDayLabel` helpers). A small legend row labels
the five series. Colors come from the theme. `DebugLineChart` is retained
unchanged for the muscle baseline-over-time chart.

### 4. Cross-tuning bars (replaces "Coefficient vs seed")

New per-muscle `computeCrossTuning(muscle)` evaluated at `now`, producing per
exercise:

- **Agreement** = `(ownE1rm − leaveOneOutPred) / leaveOneOutPred`, signed.
- **Contribution** = decayed confidence / Σ decayed confidence over the muscle
  (0..1).

Rendering:

- **Agreement vs consensus** — the existing diverging-bar widget (generalized
  from `CoefficientDeviationBar` / `CoefficientDeviationList`, ±50% saturating
  range). Current exercise highlighted (bold) on the exercise screen.
- **Contribution** — left-anchored 0–100% proportion bars, sorted descending.

`computeCoefficientDeviations` and the `CoefficientDeviationRow` /
`CoefficientDeviationList` usage are retired; the diverging-bar primitive is
generalized and reused for agreement.

### 5. Screen wiring

- **ExerciseCoefficientDetailScreen**
  - top: `ExerciseProgressionChart` (replaces "Coefficient over time")
  - then: cross-tuning section (agreement + contribution, self highlighted)
  - then: change-events feed (unchanged)
- **MuscleBaselineDetailScreen**
  - baseline-over-time chart (kept)
  - cross-tuning section replaces "Coefficient vs seed"
  - change events (kept)

ViewModels obtain data via two new repository methods so DB/snapshot access stays
in the repo layer:

- `getExerciseProgressionSeries(exerciseId): ExerciseProgressionSeries`
- `getCrossTuning(muscle): List<CrossTuningRow>`

Both load the static replay snapshot (`ReplaySnapshot.loadStaticFromDb`) and run
the new builders. They are invoked when a detail screen opens (rare; a
muscle-scoped replay over a handful of exercises is cheap).

### 6. Testing

- **Refactor parity:** `ExerciseEstimatorSimulationTest`, `ReplayDerivedStateTest`
  stay green.
- **`SessionProgressionStepperTest`** — the stepper reproduces the inline fold
  (HURT, per-exercise fold, projections) on a synthetic snapshot.
- **`ExerciseProgressionSeriesBuilderTest`** — each of the five series on
  synthetic sessions: own-estimate tracks folds; leave-one-out excludes the
  target; merged == engine `effectiveE1rm`; sibling dot rescaling by seed-coef
  ratio; per-session dot count.
- **`CrossTuningTest`** — agreement sign (above/below consensus) and contribution
  normalization (sums to 1 over confident exercises; cold exercise ≈ 0).
- Chart composable: keep to label/format-level tests in the style of the existing
  `ChartMarkerLabelTest` (no heavy render testing).

## Risks

- **Drift between series builder and production engine** — mitigated by both
  driving the same `SessionProgressionStepper`.
- **Leave-one-out vs merged framing** — the merged line is not strictly between
  own and siblings; addressed by labeling, not geometry.
