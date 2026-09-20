# Unified exercise row, explicit reps and weight

Follow-up to `2026-09-19-workout-sets-and-circuits-design.md`. That feature shipped with three UI
problems: the ⛓ link toggles cost a 32dp row each, the sets stepper blends into the reps stepper, and
the set count jumps from the row to a separate header when a row joins a circuit. Fixing those with one
row layout for both screens needs two feature definitions, which this spec also makes.

## Decisions (made with the user)

- One row layout, shared by the saved-workout editor and Today's workout (plan preview).
- Sets is a leading tinted `N ×` chip; circuits are drawn with a rail in the handle column; link
  controls sit on the divider and add no height. The "Circuit · rounds" header row is deleted.
- Reps and weight are each **explicit** or **auto** per row, independently, on both screens.
- A saved workout can store an explicit weight. It is literal and frozen (it does not progress); when
  it differs from the suggestion the row shows the suggestion beside it.
- On Today's workout a weight edit pins a fixed weight. It no longer records a one-rep-max override.
- The rep slider changes only rows whose reps are auto.

## Semantics

Each plan row and each saved row carries two independent pins:

| | auto | explicit |
|---|---|---|
| reps | the session's reps (slider) | the row's own number; the slider never touches it |
| weight | the prescription at the row's reps | the row's own number; nothing reprices it |

- Changing a row's reps (by slider or by hand) reprices its weight only when the weight is auto.
- An explicit weight bypasses the policy clamps (HURT backoff, overload nudge, failed-weight cap), as the
  one-rep-max override does today and as explicit rows already bypass planner filters. Logged sets feed
  the belief fold unchanged.
- Explicit weights are rounded to the unit grid (`WeightFormatter.round`) and floored at 2.5, as today.
- Timed rows (reps fixed at 60 s, no weight) and unloadable rows (coefficient 0: bodyweight, banded)
  have no weight control. Timed rows have no reps control either. Neither can be pinned.
- Rows the app adds itself (generated, restocked, replacement after a swipe or an in-session swap, added
  via ⋮) start with both values auto.
- In-session behaviour is unchanged: the pins matter only until the workout starts. `reduceExerciseWeight`
  keeps writing `sessionWeight` directly.

### Reset to auto

Tapping the number of an explicit value returns it to auto. On Today's workout the row is repriced at
once (reps → session reps, weight → prescription at the row's reps).

## Model

```kotlin
data class PlannedExercise(
    …,
    val repsPinned: Boolean = false,
    val weightPinned: Boolean = false,
)

data class SavedWorkoutEntry(exercise, reps: Int?, weight: Float?, sets, circuitId)   // null = auto, kg
```

`WorkoutPlan.exerciseOverrides` / `effectiveOverrides`, `WorkoutPlanner.exerciseE1rmOverrides`,
the `exerciseOverrides` parameter of `WorkoutRepository.buildPlanner`, `e1rmFromSessionWeight`,
`recomputeExercise` (only tests call them; those tests go too) and the controller's `weightAdjustJob` planner rebuild
are deleted: a pinned weight lives on the row, so the planner no longer needs to know about it.

### Planner

- `withWeight(pe, sessionReps)` becomes the single pricing rule and honours the pins: reps =
  `pe.sessionReps` when `repsPinned` else `sessionReps`; weight = `pe.sessionWeight` when
  `weightPinned` else the prescription at those reps. Warmups and duration are always recomputed.
- `repriceForReps` is unchanged in shape (maps `withWeight` over the rows) and so now skips pinned reps.
- `planExplicit(exercise, reps: Int?, weight: Float?, plan, sets, circuitId)` sets the pins from the
  non-null arguments. A non-null weight on a row that cannot be loaded is ignored.
- New `suggestedWeight(exercise, reps): Float` exposes the prescription for display (the "suggests 35"
  hint and the dimmed auto value). It is the same private `weightForExercise`, made public.

### Controller

- `adjustExerciseWeight(id, delta)`: pins `sessionWeight = round(max(2.5, w + delta))`, recomputes
  warmups and duration, marks `edited`. No planner rebuild.
- New `setExerciseReps(id, reps)` (1..50): pins reps, reprices through `withWeight`.
- New `resetExerciseReps(id)` / `resetExerciseWeight(id)`: clear the pin, reprice through `withWeight`.
- `applySavedWorkout` passes `entry.weight`. `saveCurrentPlan` writes `reps`/`weight` only for pinned
  values (today it writes `reps = null` for every row).
- `replaceExercise`, `onLocationRefreshed`, trim and restock keep each surviving row's pins (they
  already carry rows through `inSlotOf` / copy).

### Storage

- `saved_workout_exercise.weight REAL` nullable, kg. DB v21 → v22, `MIGRATION_21_22` is one
  `ALTER TABLE ADD COLUMN`. Schema JSON `22.json`; `MigrationTest` forward lists updated;
  `Migration21To22Test` added.
- Backup: `DB_VERSION = 22`, `MIN_DB_VERSION` stays 20; the parser defaults a missing `weight` to null.
- `saveSessionAsWorkout` (from a finished session) writes `weight = null`: a logged weight is history,
  not an instruction.

## UI

### `ExerciseRowScaffold` (new, `ui/components/CircuitChrome.kt`)

One composable lays out every row on both screens:

```
[handle | rail] [N × chip | gap]  name                     [trailing slot]
                                  [subtitle slot]
                (link node straddling the bottom edge, in the handle column)
```

- **Handle column (24dp + padding):** the drag handle on the first row of a block; on other members a
  2dp rail in `primary`. On the first row of a circuit the rail runs from the link node down. The rail
  ends at the centre of the last member.
- **Sets chip:** `N ×`, fixed width, `secondaryContainer`/`onSecondaryContainer`, only on the first row
  of a block; an equal-width gap on other members. Tapping opens a `DropdownMenu` of 1–10 (current value
  marked). For a circuit it sets the rounds (`CircuitEdits.setRounds`). Content description
  "Sets: 3" / "Rounds: 2".
- **Link node:** a 20dp circle (36dp touch target) centred on the row's bottom edge in the handle
  column, drawn over the divider, on every row but the last. Hollow with `LinkOff` when the rows are
  separate ("Link into a circuit"), filled `primaryContainer` with `Link` when linked ("Split the
  circuit here"). No divider is drawn between members of a circuit; the block divider is drawn after a
  block.
- `CircuitEdits.link` is unchanged: the upper block's set count wins, so the chip that stays on screen
  keeps its number — except a solo row linked onto a circuit below it, which adopts the circuit's rounds
  (the existing rule; the circuit is the thing the user built).
- `CountStepper`, `LinkToggle` and `CircuitHeader` are deleted.

### `ValueStepper` (new, same file)

`− value +` with a unit label, used for reps and for weight on both screens.

- Auto: the value is the suggested number at `DISABLED_ALPHA`. The first − or + pins it, starting from
  the suggestion (so the first tap lands on suggestion ± one step).
- Explicit: full-contrast number; tapping the number resets to auto (content description "Reset to
  suggested").
- Steps: reps ±1 in 1..50; weight ±2.5 in the display unit's grid, as today.
- When an explicit weight differs from the suggestion the row's subtitle line adds `suggests 35 lb` in
  `onSurfaceVariant`.

### Screens

- **Today's workout:** subtitle slot = reps stepper (or `60 s` for timed) + row flag; trailing slot =
  weight stepper, `Bodyweight`, or nothing. The inline sets stepper and the "sets × reps" text go. Swipe
  actions, tap-for-detail and whole-block drag are unchanged. Helper text drops the ⛓ clause.
- **Edit workout:** identical, with suggestions computed by a planner built with no location
  (`repository.buildPlanner(locationId = null, …)`) at the user's current session reps. The view model
  exposes `suggestions: Map<exerciseId, SuggestedValues(reps, weight)>`; while it loads, auto values
  show `–`. New view-model methods `setWeight(id, Float?)`; `setReps` keeps its signature. The
  "reps 0 = session default" convention and helper-text clause are removed. `hasUnsavedChanges` covers
  weight.

## Testing

- JVM: planner pin matrix (slider × {reps pinned, weight pinned}); `planExplicit` with weight;
  controller `adjustExerciseWeight` pins and survives the slider; `setExerciseReps` reprices auto weight
  and leaves pinned weight; resets; load → save round trip keeps only pinned values; backup v20/v21/v22 parse; editor view model weight edits and dirty check.
- Instrumented: `Migration21To22Test`, DAO/repository round trip of `weight`, `MigrationTest` lists.
- Belief/policy stack and the backtest gate are untouched.
- On-device pass (emulator) for both screens after the UI tasks: chip menu, link node hit area next to
  the drag handle, rail drawing, steppers at 360dp width with long names, dynamic-colour contrast of the
  chip and nodes.

## Out of scope

- Swipe-right-to-link (option C). Could be layered on later.
- Progressing or floor-style saved weights.
- Per-row reps/weight editing during a session.
