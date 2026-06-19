# Prescribed-1RM line on the exercise chart

**Date:** 2026-06-19
**Status:** Approved, ready for planning

## Goal

Overlay a smoothed line on the exercise detail chart showing the planner's
*prescribed* estimated-1RM for that exercise over time — i.e.
`baseline(t) × coefficient(t)`. Today the chart shows only the **achieved**
1RM as scattered dots (primary exercise + coefficient-scaled shadow points).
The new line gives the user the model's intended strength target to compare
their actual lifts against.

## Why the product is in 1RM units

`WorkoutPlanner.weightForExercise` derives the session weight as:

```
fromOneRepMax(baseline × coeff, sessionReps) → WeightFormatter.round(...)
```

So `baseline × coeff` is, by construction, the estimated 1RM **before** the
`fromOneRepMax` rep-scaling and plate rounding. That is the same y-axis the
existing achieved dots use (`toOneRepMax(set.targetWeight, set.targetReps)`),
so the new series overlays directly with no unit conversion.

## True (unrounded) values — the key requirement

The line MUST be the true, pre-rounding product:

- **Prescribed line (new):** read `baseline × coefficient` straight from the
  history tables. `CoefficientHistory.coefficient` is an unrounded `Float` and
  `BaselineHistory.newBaseline` is the stored model value; their product is the
  true estimated-1RM target. We do **not** reconstruct it from the rounded
  `sessionWeight` (`WeightFormatter.round`), which would re-introduce plate
  quantization. Example to lock in a test: `baseline = 101.3`, `coeff = 0.617`
  → `62.5021`, not a rounded value.
- **Achieved dots (existing, unchanged):** still
  `toOneRepMax(set.targetWeight, targetReps)`. These legitimately carry rounding
  because `targetWeight` was a real, rounded plate load — they reflect what was
  actually lifted.

The visual contrast — smooth true target vs. quantized reality — is the point.

## Data reconstruction (ViewModel)

New pure helper (mirroring the existing `buildBaselineChartPoints` /
`computeShadowPoints` style), unit-tested on the JVM. Inputs:

- `baselineEvents: List<BaselineHistory>` from
  `repository.getBaselineEvents(exercise.primaryMuscle)` — each `newBaseline`
  applies from its `timestamp` onward (step function).
- `coefficientEvents: List<CoefficientHistory>` from
  `repository.getCoefficientEvents(exerciseId)` — each `coefficient` applies
  from its `computedAt` onward (step function).
- `seedCoefficient: Float` = `ExerciseCoefficients.byName[exercise.name] ?: 0f`,
  used before the first coefficient event / when there is no history.
- `dayKeys`: the epoch-day keys that already have a primary dot (reuse
  `primarySetsByDay.keys`).

Algorithm — for each `day` in `dayKeys` (ascending):

1. `baseline(day)` = `newBaseline` of the latest event with
   `timestamp ≤ end-of-day`. Before the first event, use the first event's
   `previousBaseline` **if > 0**, else the day is dropped (matches the existing
   `buildBaselineChartPoints` convention that avoids dragging the chart to 0
   from the INITIAL assessment).
2. `coefficient(day)` = `coefficient` of the latest event with
   `computedAt ≤ end-of-day`, else `seedCoefficient`.
3. Emit `ChartPoint(dateMs = day * 86_400_000L, weightKg = baseline × coeff)`.

**Sampling on the existing dot x-grid** is deliberate: it keeps the new series
on the same x values as the achieved dots, so the marker / day-selection logic
(`markerListener`, `selectDay`) needs **no changes** — there are no new x
positions for the marker to land on. Sampling a step function at those days
still yields the exact prescribed value on each day.

**Suppression:** when `seedCoefficient ≤ 0` (bodyweight / unloadable) the line
is empty — the product is undefined, consistent with the primary dots already
being suppressed for those exercises.

State: add `prescribedPoints: List<ChartPoint>` to `ExerciseDetailState`,
populated in `loadChartData`.

## Rendering (Compose, `ExerciseChart`)

- Add `prescribedPoints` as a third `series(...)` in the existing `lineSeries`
  transaction, ordered **after** the primary and shadow dot series so the
  marker target order is unchanged.
- Provider: a new `LineCartesianLayer.rememberLine` that is a **stroke, not
  dots** — `LineStroke.continuous(thickness = 2.dp)`,
  `pointConnector = PointConnector.cubic()`, transparent area fill, color
  `MaterialTheme.colorScheme.tertiary` (distinct from primary/secondary dots).
  Append it to the `LineProvider.series(...)` list, guarded by a
  `hasPrescribed` flag exactly like `hasPrimary` / `hasShadow`.
- Empty-state guard in `ExerciseDetailScreen` extends to:
  `primaryPoints.isEmpty() && shadowPoints.isEmpty() && prescribedPoints.isEmpty()`.
- Add a short caption/legend under the chart clarifying the line means
  "prescribed target" vs. the achieved dots. Keep it lightweight (a `Text` row
  with a color swatch); no new icons.

## Testing

- JVM unit tests for the new reconstruction helper:
  - step-function sampling picks the latest event ≤ day for both baseline and
    coefficient;
  - seed-coefficient fallback before the first coefficient event;
  - leading-day rule (first event `previousBaseline > 0` vs. INITIAL `== 0`);
  - bodyweight suppression (`seedCoefficient ≤ 0` → empty);
  - **true-value assertion**: `baseline = 101.3 × coeff = 0.617 → 62.5021`,
    explicitly not a rounded plate value.
- Compose wiring verified by `:app:assembleDebug` build + a visual check.

## Out of scope

- No smoothing/averaging beyond the cubic Bezier connector (the product is the
  true value; we only render it as a flowing curve).
- No change to the achieved-dot series or the shadow-point computation.
- No marker/day-selection changes (the shared x-grid makes them unnecessary).
