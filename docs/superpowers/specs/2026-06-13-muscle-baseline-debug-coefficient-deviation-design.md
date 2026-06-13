# Muscle Baseline Debug — Coefficient Deviation Chart

## Motivation

The `MuscleBaselineDetailScreen` is a developer-facing debug view for the
per-muscle progression engine. Today it shows the current baseline weight,
a baseline-over-time line chart, and a list of baseline change events.

A subtle pathology the engine can exhibit is **baseline gains being
masked by drifting coefficients**: if the per-exercise coefficient
(`currentCoeff` for that exercise) drifts upward relative to its seed,
the user lifts heavier loads on that exercise but the muscle's *baseline
weight* may not move — because session weight is computed as
`baseline × coefficient`. Looking at the baseline chart alone, the
developer cannot tell whether a flat baseline means "no progress" or
"progress hidden inside coefficient drift."

This design adds a per-exercise diverging bar chart that shows each
exercise's current coefficient relative to its seed coefficient,
making any unusual drift visible at a glance.

## Scope

In scope:

- Remove the "Current baseline" Card from `MuscleBaselineDetailScreen`.
- Tighten the gap between the "Baseline over time" section header and
  the line chart.
- Add a new "Coefficient vs seed" section between "Baseline over time"
  and "Change events", rendering a horizontal diverging bar list of
  exercises in the muscle group.
- Extend `MuscleBaselineDetailViewModel` to compute the per-exercise
  deviations.

Out of scope:

- Changes to any other debug screen (`ExerciseCoefficientDetailScreen`,
  `DebugStatsScreen`).
- Changes to the coefficient computation pipeline or progression
  engine.
- New domain logic. This is a read-only view of existing data.

## UX Changes

### Removed

The `Card` block at `MuscleBaselineDetailScreen.kt:69-88` showing
"Current baseline" and the formatted weight is deleted. The current
baseline value is already implied by the rightmost point of the
"Baseline over time" chart and shown verbatim as `newBaseline` in the
most recent change event row.

### Spacing tweak

`SectionHeader` (used for both "Baseline over time" and "Change events")
currently uses `padding(horizontal = 16.dp, vertical = 12.dp)`. The
chart row immediately below it uses `padding(horizontal = 16.dp,
vertical = 8.dp)`. The combined 20dp gap above the line chart feels
oversized for a debug screen.

Change:

- `SectionHeader` vertical padding: `12.dp` → `4.dp`
- The line chart wrapper vertical padding (`MuscleBaselineDetailScreen.kt:102`):
  `8.dp` → `0.dp`

Net gap above the chart drops to ~4dp. This applies symmetrically to
the "Change events" header too, since `SectionHeader` is shared.

### New section: Coefficient vs seed

Inserted as a `LazyColumn` item between "Baseline over time" and
"Change events".

Visual:

```
Bench Press           ████████▶  +25%
Incline Bench         ██▶         +8%
Decline Bench        ◀█           -3%
Dumbbell Press   ◀████             -18%
                      0
```

Per-row layout (a Compose `Row` per exercise):

- **Name column** (fixed `140.dp` width): exercise name in
  `MaterialTheme.typography.bodyMedium`, single line, ellipsized at end
  if too long.
- **Bar column** (`weight = 1f`): a `Box` with a 1dp vertical center
  guideline. A colored bar extends from the center toward the left
  (negative deviation) or right (positive deviation). Bar length is
  scaled to `max(abs(deviation))` across the dataset, so the largest-
  magnitude bar fills the half-width.
- **Value column** (fixed `56.dp` width, right-aligned): formatted
  percentage like `+25%`, `-3%`. Uses
  `MaterialTheme.typography.labelSmall`.

Colors:

- Positive bars: `MaterialTheme.colorScheme.primary`.
- Negative bars: `MaterialTheme.colorScheme.error`.

Sort order: by deviation, descending (largest positive first, largest
negative last).

Filtering:

- Exercises whose **seed coefficient** is `0f` are omitted (bodyweight
  exercises — `coeff / seed - 1` is undefined and they don't drive
  baseline math).
- Disliked exercises (`isDisliked = true`) **are included**. The
  debug intent is to surface drift on every exercise the engine knows
  about, not just those the user currently trains.

Empty state: if no exercise in the muscle group has a non-zero seed
coefficient, render the same placeholder pattern used for the baseline
chart and event list — a `Box` of height ~`120.dp` with the text
"No weighted exercises".

## Data Layer

### State additions

`MuscleBaselineDetailState` gains one field:

```kotlin
data class CoefficientDeviationRow(
    val name: String,
    val deviation: Float, // currentCoefficient / seedCoefficient - 1
)

data class MuscleBaselineDetailState(
    // …existing fields…
    val coefficientDeviations: List<CoefficientDeviationRow> = emptyList(),
)
```

### Computation

In `MuscleBaselineDetailViewModel.init`, after the existing data
fetches:

1. Fetch `db.exerciseDao().getAll()` and filter to
   `primaryMuscle == muscleGroup`. Includes disliked exercises.
2. Build a map of current effective coefficient per exercise id:
   `db.coefficientChangeLogDao().getLatestPerExercise()` keyed by
   `exerciseId`, value = `coefficient`. (This is the same source used by
   `WorkoutRepository.buildCoefficientInput`.)
3. For each exercise:
   - `seed = ExerciseCoefficients.byName[exercise.name] ?: continue`
   - If `seed == 0f`, skip.
   - `current = latestUserCoefficients[exercise.id] ?: seed`
   - `deviation = current / seed - 1f`
   - Emit `CoefficientDeviationRow(exercise.name, deviation)`.
4. Sort the list by `deviation` descending.
5. Store on state.

This is a one-shot read at screen open, matching how the existing chart
and event list are loaded (no Flow). The screen is a debug view and
does not need live updates.

## Testing

- Unit-test the deviation computation as a pure helper extracted from
  the ViewModel: given a list of exercises, a seed map, and a current-
  coefficient map, it returns the sorted, filtered
  `List<CoefficientDeviationRow>`. Cover: positive drift, negative
  drift, zero drift, seed=0 skipped, missing latest coefficient falls
  back to seed (deviation = 0), descending sort.
- Composable preview: a `@Preview` rendering the new bar component with
  a fixed sample list, sanity-check positive/negative/zero bars and
  long name ellipsis.

## File touch list

- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailScreen.kt`
  — remove current-baseline card, adjust `SectionHeader` and chart
  padding, add the new section composable.
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt`
  — add `CoefficientDeviationRow`, extend state, compute in `init`.
  Extract pure helper for testability.
- New: `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/CoefficientDeviationTest.kt`
  — unit tests for the helper.
