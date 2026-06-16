# Rep Range Slider — Design

**Date:** 2026-06-16
**Status:** Approved by user (awaiting spec review)
**Scope:** Workout plan preview screen

## Summary

Add a second slider to the workout plan preview that lets the user pick a
*range* of acceptable session rep counts. Every time the range changes, the
workout picks a new rep target inside the range and re-prices all planned
exercises. The picker prefers "round" rep counts (1, 2, 3, 5, 8, 10, 12, 15,
18, 20) but always allows the chosen endpoints, so any range — even one
containing no round numbers — has at least two candidates.

The chosen range persists across sessions on `UserProfile`, mirroring the
existing `preferredExerciseCount` pattern.

## Motivation

Today, session reps are picked uniformly from a hard-coded list of three
values (`DefaultProgressionEngine.repOptions = [5, 8, 10]`) once at workout
generation. There is no user control. Letting the user steer the range is a
small UI addition that gives meaningful control over training stimulus
(strength-focused low reps vs hypertrophy-focused higher reps) without
introducing per-exercise complexity.

## UI

A second slider row sits directly below the existing "Shorter / Longer"
(exercise count) row in `PlanPreviewContent.kt`:

```
Shorter    ───●─────────●───   Longer       (existing Slider, int exercise count)
Fewer reps ───●──────●──────   More reps    (new RangeSlider, ints in [1, 20])
```

- Component: Material3 `RangeSlider`.
- `valueRange = 1f..20f`.
- `steps = 18` so each integer in `[1, 20]` is an addressable thumb position.
- Local `mutableStateOf<ClosedFloatingPointRange<Float>>` for the dragging
  thumbs.
- Fires `onSetRepRange(min: Int, max: Int)` on `onValueChangeFinished` (only
  when the user releases the thumb — same convention as the existing
  exercise-count slider).
- No live rep-count label near the slider. The per-exercise rows already show
  `3 sets × N reps` and will reflect the re-rolled value after the plan is
  re-priced. The min/max thumb positions on the slider itself communicate the
  range.

### Default and persisted state

- Default range: `[5, 10]`.
- Persisted in `UserProfile`. When both stored values are null (fresh install
  or pre-migration row), the default is used.

## Picker

New file: `app/src/main/java/io/github/fowles/stochastic_strength/domain/RepRangePicker.kt`.

```kotlin
object RepRangePicker {
    val ROUND_REPS: List<Int> = listOf(1, 2, 3, 5, 8, 10, 12, 15, 18, 20)

    fun candidates(min: Int, max: Int): List<Int> {
        val rounds = ROUND_REPS.filter { it in min..max }
        return (rounds + min + max).distinct().sorted()
    }

    fun pick(min: Int, max: Int, random: Random): Int =
        candidates(min, max).random(random)
}
```

- Candidate set = `(ROUND_REPS ∩ [min, max]) ∪ {min, max}`, deduped, sorted.
- `pick()` selects uniformly from the candidate list.
- Pure function, no dependencies — unit-testable on the JVM.

### Worked examples

| Range     | Candidate set                  | Notes                                                  |
|-----------|--------------------------------|--------------------------------------------------------|
| `[5, 10]` | `{5, 8, 10}`                   | Reproduces today's distribution exactly.               |
| `[1, 20]` | `{1, 2, 3, 5, 8, 10, 12, 15, 18, 20}` | All round numbers available.                    |
| `[1, 1]`  | `{1}`                          | Degenerate range; always returns 1.                    |
| `[4, 4]`  | `{4}`                          | Extremum kept even though 4 is not round.              |
| `[6, 7]`  | `{6, 7}`                       | No rounds in range; both endpoints serve as fallback.  |
| `[3, 5]`  | `{3, 5}`                       | Both endpoints are round; no other rounds in range.    |
| `[2, 18]` | `{2, 3, 5, 8, 10, 12, 15, 18}` | Both endpoints round; standard interior.               |

## Plumbing

### `WorkoutPlanner`

Two additions, keeping the existing single-int `generateWorkout(sessionReps:
Int)` signature so it stays directly testable:

```kotlin
fun generateWorkout(repMin: Int, repMax: Int): WorkoutPlan =
    generateWorkout(sessionReps = RepRangePicker.pick(repMin, repMax, random))

fun repriceForReps(plan: WorkoutPlan, repMin: Int, repMax: Int): WorkoutPlan {
    val sessionReps = RepRangePicker.pick(repMin, repMax, random)
    val newExercises = plan.exercises.map { withWeight(stripWeight(it), sessionReps) }
    return plan.copy(exercises = newExercises, sessionReps = sessionReps)
}
```

`withWeight` already handles the timed-exercise branch and the bodyweight
branch correctly. `stripWeight` is just a thin helper that re-bases the
`PlannedExercise` (drops `sessionWeight`, `warmupSets`) so `withWeight` can
recompute deterministically from `exercise` + `sessionReps`. (Reusing the
existing private `withWeight` is the right move — it's the one source of
truth for session-weight derivation.)

The existing default-argument form of `generateWorkout()` (which calls
`progressionEngine.repOptions.random(random)`) is removed; callers always
pass either an explicit `sessionReps` or a `[repMin, repMax]` range.
`DefaultProgressionEngine.repOptions` stays for now (used internally by
`generateWorkout(sessionReps: Int)`'s callers in tests), but its random
default usage from `WorkoutPlanner.generateWorkout` is dropped.

### `WorkoutSessionController`

- `initializeSession(...)` gains `preferredRepMin: Int` and `preferredRepMax:
  Int` parameters. The initial plan is built with
  `planner.generateWorkout(preferredRepMin, preferredRepMax)`.
- New `fun setRepRange(repMin: Int, repMax: Int)`:
  - No-op if state is not `PlanPreview` (slider only exists on that screen).
  - Cancels `addExerciseJob` if running (mirrors `adjustExerciseCount`).
  - Calls `planner.repriceForReps(preview.plan, repMin, repMax)` and emits the
    new state.
- Cached fields `preferredRepMin: Int`, `preferredRepMax: Int` are kept so the
  controller can apply the user's last range whenever the planner is rebuilt
  (e.g., on location refresh, replace-exercise, or weight-adjust paths that
  call `repository.buildPlanner(...)`).

### `WorkoutViewModel`

- Reads `profile?.preferredRepMin ?: 5` and `profile?.preferredRepMax ?: 10`
  on init.
- New `fun setRepRange(min: Int, max: Int)`:
  - Calls `controller.setRepRange(min, max)`.
  - If different from cached values, persists `profile.copy(preferredRepMin =
    min, preferredRepMax = max)` (parallels `setExerciseCount`).

### `WorkoutScreen` and `PlanPreviewContent`

- `WorkoutScreen` passes `onSetRepRange = viewModel::setRepRange` into
  `PlanPreviewContent`.
- `PlanPreviewContent` adds the `RangeSlider` row and the new callback to its
  signature.

## Persistence

`UserProfile` gains two nullable INTEGER columns:

```kotlin
@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Long = 1,
    val sex: Sex,
    val strengthLevel: StrengthLevel,
    val weightUnit: WeightUnit,
    val preferredExerciseCount: Int? = null,
    val preferredRepMin: Int? = null,
    val preferredRepMax: Int? = null,
)
```

### Migration

Schema version bumps **14 → 15** in `AppDatabase`.

```kotlin
internal val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_profile ADD COLUMN preferredRepMin INTEGER")
        db.execSQL("ALTER TABLE user_profile ADD COLUMN preferredRepMax INTEGER")
    }
}
```

Both columns are nullable; existing rows get `NULL` and the default `[5, 10]`
is applied at read time. No backfill needed.

## Tests

### `RepRangePickerTest` (new, JVM)

- `candidates([5, 10])` → `[5, 8, 10]`.
- `candidates([1, 1])` → `[1]`.
- `candidates([4, 4])` → `[4]`.
- `candidates([6, 7])` → `[6, 7]`.
- `candidates([3, 5])` → `[3, 5]`.
- `candidates([1, 20])` → all of `ROUND_REPS`.
- `candidates([2, 18])` → `[2, 3, 5, 8, 10, 12, 15, 18]`.
- `pick(min, max, Random(seed))` produces only values in `candidates(min,
  max)` over a large iteration count, and every candidate appears at least
  once for a non-degenerate range. (Distribution shape: uniform-ish — we
  do not assert exact frequencies, but assert coverage.)

### `WorkoutPlannerTest` additions

- `repriceForReps` preserves `plan.exercises` size, order, and exercise
  identity (same `Exercise.id` per slot).
- `repriceForReps` updates `sessionReps` on the plan and on each
  `PlannedExercise`, and recomputes `sessionWeight` via the same
  `progressionEngine.fromOneRepMax` formula.
- Timed exercises remain `sessionReps = 60`, `sessionWeight = 0f` (existing
  `withWeight` branch unchanged).
- Bodyweight exercises (`coeff == null` or weight 0) remain unchanged where
  the existing logic dictated.

## Edge cases and behavior notes

- **Min equals max**: `RangeSlider` permits the two thumbs to coincide.
  Candidate set is a single value, picker returns it deterministically.
- **No round value in range**: only the two endpoints are candidates. The
  effective probability of either endpoint is 50% (or 100% if they coincide).
- **Slider during workout**: only visible on the `PlanPreview` state. Once the
  user presses "Let's Go", changes are not possible. Re-pricing logic guards
  on state type, same as `adjustExerciseCount`.
- **Pre-existing exercise count slider**: untouched. The two sliders are
  independent; changing the rep range does not re-shuffle exercises, and
  changing exercise count does not re-roll reps. (`adjustExerciseCount` keeps
  the existing `plan.sessionReps` value when trimming/appending.)
- **Interactions with `pickAdditional` / `pickReplacement`**: both paths call
  the planner's existing methods which derive `sessionReps` from
  `plan.sessionReps`. No changes needed — they automatically pick up whatever
  reps the most recent re-roll produced.

## Files touched

- NEW: `app/src/main/java/io/github/fowles/stochastic_strength/domain/RepRangePicker.kt`
- NEW: `app/src/test/java/io/github/fowles/stochastic_strength/domain/RepRangePickerTest.kt`
- MODIFY: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`
- MODIFY: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/UserProfile.kt`
- MODIFY: `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt`
- MODIFY: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`
- MODIFY: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`
- MODIFY: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt`
- MODIFY: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/PlanPreviewContent.kt`
- MODIFY: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt` (existing tests covering `repriceForReps`)

## Out of scope

- Per-exercise rep targets. The slider sets one range for the whole session.
- Adapting the rep range based on history or muscle group.
- Storing per-location preferred rep ranges.
- Changing the workout in any state other than `PlanPreview`.
