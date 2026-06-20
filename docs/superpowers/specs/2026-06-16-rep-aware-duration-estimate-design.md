# Rep-Aware Workout Duration Estimate — Design

**Date:** 2026-06-16
**Status:** Approved by user (awaiting spec review)
**Scope:** `domain/` — duration estimation for planned workouts
**Supersedes:** `2026-06-15-adaptive-workout-duration-estimate-design.md`

## Summary

Replace the existing duration heuristic with one that scales with the chosen
rep target. The current model uses a flat `secondsPerSet` constant per
exercise category, which produced reasonable estimates when sessions always
ran at 5/8/10 reps but is now visibly wrong with the new rep-range slider
(which can choose anywhere in `[1, 20]`).

The new model decomposes per-set time into work + rest, where work scales
linearly with `sessionReps`. It also explicitly models plate-change overhead
(a major component on barbell exercises with warmups). Per-exercise pacing is
learned from historical working-set intervals; a sane parametric default
makes day-1 estimates correct for fresh exercises.

## Motivation

`PlannedExercise.estimatedSeconds` today is:

```
estimatedSecondsOverride ?? (DEFAULT_SETS × secondsPerSet + warmups × 60)
```

with `secondsPerSet` = 135 (normal), 180 (unilateral), 90 (timed).

Two problems:

1. **Constants don't track reps.** A 1×3 session and a 3×15 session estimate
   identically per-set, but the actual work time differs by ~40s/set. With
   the new `[1, 20]` rep-range slider, the user can now pick targets that
   are far from the implicit ~8-10 reps the constants were calibrated for.

2. **`estimatedSecondsOverride` (learned average wall-clock per appearance)
   doesn't transfer across rep targets.** History collected at 10 reps is
   the wrong absolute number when the user moves to 3 reps.

Plate-change overhead on barbell exercises is a real driver of duration —
each warmup forces a plate change, and warmups concentrate them. The new
model accounts for this explicitly.

## Duration Formula

For **non-timed exercises**:

```
perRep         = learned secondsPerRep_e  (Float) or DEFAULT_SECONDS_PER_REP
sides          = 2 if exercise.isUnilateral else 1
workPerSet     = perRep × sessionReps × sides

workingTime    = numSets × (workPerSet + REST_SECONDS)
                 // No "-1": the final rest IS the inter-exercise transition.

warmupWork     = sum_over_warmups(warmup.reps × perRep × sides)
warmupRest     = warmups.size × WARMUP_REST_SECONDS

plateChanges   = warmups.size + 1               // initial load + transitions + working weight
weightChange   = plateChangeSec(equipment) × plateChanges

total = workingTime + warmupWork + warmupRest + weightChange
```

For **timed exercises** (`exercise.isTimed`), `sessionReps` is set duration in
seconds, so:

```
total = numSets × (sessionReps + REST_SECONDS)
```

(Learning is not applied to timed exercises — the user-chosen duration is
already the work time.)

### Worked example — barbell squat, 3×10, three warmups (8/5/3 reps), perRep=4s, BARBELL

- Working time: 3 × (4×10 + 90) = 3 × 130 = **390s**
- Warmup work: (8+5+3) × 4 = **64s**
- Warmup rest: 3 × 30 = **90s**
- Plate changes: 25 × (3+1) = **100s**
- **Total: 644s ≈ 10.7 min**

Same exercise at 3×3 reps: 3 × (12+90) = 306s → total ≈ 560s.
Same at 3×15 reps: 3 × (60+90) = 450s → total ≈ 704s.

The estimate visibly tracks rep-target changes, which it didn't before.

## Per-Exercise Learning

`ExercisePacingEstimator` (renamed from `ExerciseDurationEstimator`) reads
recent sessions and returns `secondsPerRep(exerciseId): Float?`.

### Sample collection

For each session, newest-first, until at most `MAX_APPEARANCES = 10` samples
per exercise have been collected:

1. Group the session's working sets by exercise.
2. Sort each exercise's sets by `setNumber`.
3. For each consecutive pair `(s_{n-1}, s_n)`:
   - If either set has `feedback == HURT` or `completedAt == null`, skip.
   - Compute `workTime = (s_n.completedAt − s_{n-1}.completedAt) − REST_SECONDS`.
   - Reps: `reps = s_n.actualReps ?? s_n.targetReps`.
   - Sides: `2 if exercise.isUnilateral else 1`.
   - `secondsPerRepSample = workTime / (reps × sides)`.
   - If `secondsPerRepSample !in 1f..30f`, skip (covers negative values from
     long pauses, accidental advances, or pathological data).
4. Average the surviving samples within the appearance → one sample for that
   appearance.
5. Average across appearances → final learned `secondsPerRep_e`.

Exercises with zero surviving samples fall back to `DEFAULT_SECONDS_PER_REP`.

### Notes

- **Warmups are not persisted to `workout_sets`**, so learning only sees
  working-set intervals. The fixed `plateChangeSec × (warmups+1)` term
  absorbs the unobserved warmup overhead.
- The `actualReps ?? targetReps` precedence relies on the existing
  `ActualRepsBackfill` job for historical sets where `actualReps` is null.
- Unilateral pacing is learned as **per-rep-per-side** to keep the formula
  symmetric: the estimate multiplies by 2 sides, so the learned value must
  also divide by 2 sides.

## Defaults

| Constant | Value | Notes |
|---|---|---|
| `DEFAULT_SECONDS_PER_REP` | `3.0f` | Single value; learning corrects per exercise |
| `REST_SECONDS` | `90` | Mirrors `WorkoutSessionController.REST_SECONDS` |
| `WARMUP_REST_SECONDS` | `30` | Brief pause between warmup work and next plate change |
| `MIN_SECONDS_PER_REP` | `1.0f` | Outlier floor for learning samples |
| `MAX_SECONDS_PER_REP` | `30.0f` | Outlier ceiling for learning samples |
| `MAX_APPEARANCES` | `10` | Per-exercise sample cap |

`plateChangeSec(equipment)`:

| Equipment | Seconds |
|---|---|
| `BARBELL` | 25 |
| `DUMBBELL` | 8 |
| `KETTLEBELL` | 5 |
| `MACHINE` | 5 |
| `CABLE_MACHINE` | 5 |
| `BODYWEIGHT` | 0 |
| `BAND` | 0 |

## Architecture

### New file: `domain/DurationCalculator.kt`

Pure object, no state:

```kotlin
object DurationCalculator {
    const val REST_SECONDS = 90
    const val WARMUP_REST_SECONDS = 30
    const val DEFAULT_SECONDS_PER_REP = 3.0f

    fun estimate(
        exercise: Exercise,
        sessionReps: Int,
        numSets: Int,
        warmupSets: List<WarmupSet>,
        secondsPerRep: Float?,
    ): Int

    fun plateChangeSec(equipment: Equipment): Int
}
```

`REST_SECONDS` is duplicated here rather than reaching across into
`ui/workout/`. The mirror is intentional; a comment in each file points at
the other.

### Renamed: `domain/ExercisePacingEstimator.kt`

Replaces `ExerciseDurationEstimator.kt`. New API:

```kotlin
class ExercisePacingEstimator(
    private val secondsPerRepByExerciseId: Map<Long, Float>,
) {
    fun secondsPerRep(exerciseId: Long): Float? = secondsPerRepByExerciseId[exerciseId]

    companion object {
        const val MAX_APPEARANCES = 10
        const val MIN_SECONDS_PER_REP = 1.0f
        const val MAX_SECONDS_PER_REP = 30.0f
        val EMPTY = ExercisePacingEstimator(emptyMap())

        fun build(
            sessionsNewestFirst: List<WorkoutSession>,
            setsBySessionId: Map<Long, List<WorkoutSet>>,
            exercisesById: Map<Long, Exercise>,        // need isUnilateral for sides
        ): ExercisePacingEstimator
    }
}
```

The `exercisesById` parameter is new — needed to apply the `sides` divisor
during sample extraction.

### Simplified: `domain/model/PlannedExercise.kt`

```kotlin
data class PlannedExercise(
    val exercise: Exercise,
    val sessionWeight: Float = 0f,
    val originalSessionWeight: Float = sessionWeight,
    val sessionReps: Int = 10,
    val warmupSets: List<WarmupSet> = emptyList(),
    val estimatedSeconds: Int = 0,        // computed by planner via DurationCalculator
) {
    companion object {
        const val DEFAULT_SETS = 3        // retained; used by UI
    }
}
```

Removed: `estimatedSecondsOverride`, `secondsPerSet`, `SECONDS_PER_SET`,
`SECONDS_PER_UNILATERAL_SET`, `SECONDS_PER_WARMUP_SET`, `SECONDS_PER_TIMED_SET`.

`WorkoutPlan.estimatedDurationSeconds` (sums `exercises.estimatedSeconds`) is
unchanged.

### Updated: `domain/WorkoutPlanner.kt`

- Constructor takes `pacingEstimator: ExercisePacingEstimator = ExercisePacingEstimator.EMPTY` (replaces `durationEstimator`).
- `withWeight()` calls `DurationCalculator.estimate(...)` with
  `pacingEstimator.secondsPerRep(pe.exercise.id)` and writes the result into
  `pe.estimatedSeconds`.
- Existing flow (`generateWorkout`, `repriceForReps`, `pickReplacement`,
  `pickAdditional`) automatically recomputes the estimate when the rep
  target changes, because they all go through `withWeight`.

### Updated: `domain/WorkoutRepository.kt`

- Replace `ExerciseDurationEstimator.build(recentSessions, recentSets)` with
  `ExercisePacingEstimator.build(recentSessions, recentSets, exercisesById)`.
- Pass the pacing estimator into the planner instead of the duration estimator.

### No database changes

All learning is derived at planner-build time from existing `workout_sets`
data. No schema bump, no migration.

## Testing

### `DurationCalculatorTest` (new, JVM)

Table-driven cases covering:

- Reps sweep: 3-set barbell exercise with `[1, 3, 5, 8, 10, 15, 20]` reps and
  fixed warmups — verify each estimate matches the formula exactly.
- Unilateral: same sweep, verify `×2` sides applied.
- Timed: `numSets × (sessionReps + REST_SECONDS)`.
- Bodyweight zero-warmup: no plate-change time, no warmup time.
- Equipment matrix: each `Equipment` value produces the expected
  `plateChangeSec`.
- Custom `secondsPerRep` (learned) shifts estimate proportionally to default.

### `ExercisePacingEstimatorTest` (replaces `ExerciseDurationEstimatorTest`)

- Empty input → `secondsPerRep(_)` returns null for every id.
- Single appearance, two-set pair → returns expected `secondsPerRep`.
- Multi-set appearance → samples averaged within the appearance.
- `HURT` feedback on either set in a pair → pair skipped.
- Null `completedAt` on either set in a pair → pair skipped.
- Outlier bounds: pair yielding < 1s/rep skipped; pair yielding > 30s/rep skipped.
- `actualReps ?? targetReps` precedence: when both differ, the `actualReps`
  value is used.
- Unilateral exercise: returned per-rep value is divided by 2 sides.
- Multiple sessions: per-appearance averages are averaged across appearances.
- `MAX_APPEARANCES`: 11 appearances → only the 10 newest contribute.

### `WorkoutPlannerTest` (extend)

- `repriceForReps` with a different rep target produces a strictly different
  `estimatedDurationSeconds` (positive correlation with reps).
- A planner constructed with a non-EMPTY `ExercisePacingEstimator` and a
  learned `secondsPerRep` different from `DEFAULT_SECONDS_PER_REP` produces
  a different estimate than the EMPTY-pacing planner for the same plan.

### Existing tests touched

Several existing tests in `PlannedExerciseTest` and `WorkoutPlanTest` assert
absolute estimates using the old constants. They will be updated to compute
expected values from the new formula. Snapshot strings in `PlanPreviewContent`
display ("$durationMin min · ...") aren't asserted in tests; no UI test
changes expected.

## Out of scope

- **No data migration.** The learned values are derived per planner build;
  the only on-disk storage of timing is `workout_sets.completedAt` which the
  estimator already consumes.
- **No UI changes.** The plan preview already reads
  `plan.estimatedDurationSeconds / 60`; that path is unchanged.
- **No real-time refresh.** The estimate is computed at plan generation /
  reprice time, as today.
- **No new equipment categories.** The `plateChangeSec` table covers all
  current `Equipment` values.
