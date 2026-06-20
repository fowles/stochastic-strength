# Per-Set Log: actualReps capture + workflow restructure

**Date:** 2026-06-11
**Status:** Approved (pending plan)

## Problem

The app already records one row per completed set in `workout_sets`
(`sessionId`, `exerciseId`, `setNumber`, `targetWeight`, `targetReps`, `feedback`,
`completedAt`, `durationSeconds`). What is missing is **actualReps** — the number
of reps the user actually completed when a set went sideways.

Today, after `TOO_HARD`, the rest screen shows a `WeightReductionCard` asking
"How many reps did you complete?" The user's tap drives an in-memory
`sessionWeight` reduction for the next set, but the rep count itself is never
persisted. We want to:

1. Capture `actualReps` going forward via that same prompt.
2. Show the prompt uniformly — including on the final set of an exercise and
   the final set of the workout.
3. Backfill `actualReps` on historical data by inverting the
   `DefaultProgressionEngine.scaleReps` formula when a TOO_HARD set is followed
   by a same-exercise set with a lower `targetWeight`.

## Decisions

- **Storage**: add a nullable `actualReps: Int?` column to `workout_sets`. No
  new entity. `feedback` (the `SetFeedback` enum) continues to carry the RIR
  signal — no separate RIR column.
- **Going-forward capture**: every TOO_HARD shows the rep prompt (including
  bodyweight and final sets). The weight-reduction side effect only fires when
  another same-exercise set remains.
- **Workflow restructure**: the last set of a workout transitions
  `ActiveSet → Resting → Done`, matching every other set. `Done` becomes
  terminal — the Undo button is removed.
- **Backfill**: app-side Kotlin job that runs once on first launch after the
  migration, idempotent, using `DefaultProgressionEngine.scaleReps` directly.
  Gated by a `UserProfile.actualRepsBackfilled` flag.

## Schema changes

### `WorkoutSet`

```kotlin
@Entity(tableName = "workout_sets")
data class WorkoutSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val setNumber: Int,
    val targetWeight: Float,
    val targetReps: Int,
    val actualReps: Int? = null,        // NEW
    val feedback: SetFeedback? = null,
    val completedAt: Long? = null,
    val durationSeconds: Int? = null,
)
```

### `UserProfile`

Add a single boolean to mark the backfill as complete:

```kotlin
val actualRepsBackfilled: Boolean = false   // NEW
```

### Migrations

`AppDatabase` version bumps from 9 to **11**. Two migrations:

```kotlin
private val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE workout_sets ADD COLUMN actualReps INTEGER")
    }
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE user_profile ADD COLUMN actualRepsBackfilled INTEGER NOT NULL DEFAULT 0")
    }
}
```

Both register via `.addMigrations(...)` in `buildDatabase`. The migrations do
not touch data; the backfill runs in Kotlin (see below).

## Semantics: how `actualReps` is set

| `feedback` value | `actualReps` |
|---|---|
| `RIR_0_1`, `RIR_2_4`, `RIR_5_PLUS` | `targetReps` (user hit the prescription) |
| `TOO_HARD` | value from the rep prompt; inferred for backfill; `null` if neither |
| `HURT` | `null` (unknown stop point before pain) |
| `null` (set never completed) | `null` |

The same rules apply to both going-forward writes and backfill.

## Going-forward capture

### Workflow restructure

In `WorkoutSessionController`:

- `recordFeedback` no longer branches on `isLastSet`. It always transitions to
  `WorkoutState.Resting`. The single entry to `finishWorkout` is
  `advanceAfterRest` when `(exerciseIndex, completedSetIndex)` is the final
  position in the plan.
- `WorkoutState.Resting` gains a `currentSetRowId: Long` field — the autogen
  primary key returned by `workoutSetDao().insert(...)`. This row id is what
  the rep prompt updates.
- `WorkoutState.Done` loses `lastExerciseIndex` and `lastRecordedSetIndex`
  (they only existed to support Undo from Done).
- `undoLastSetFromDone` and the Undo button on `DoneContent` are removed.
  Undo from Resting (which deletes by `(sessionId, exerciseId, setNumber)`)
  remains and is the only undo path.

### Insert flow

`recordFeedback` inserts the row, capturing its id:

- For non-TOO_HARD feedback (`RIR_*` or `HURT`): the row is inserted with
  `actualReps = targetReps` for `RIR_*`, `null` for `HURT`.
- For `TOO_HARD`: the row is inserted with `actualReps = null`. If the user
  later taps a rep count on the `WeightReductionCard`, the controller calls
  `workoutSetDao().updateActualReps(currentSetRowId, completedReps)`.

If the user skips rest before tapping a rep count, the row remains with
`actualReps = null`. That correctly represents "TOO_HARD, but reps unknown".

### Rep prompt — gating

Old gating in `RestingContent`:

```kotlin
if (state.lastFeedback == SetFeedback.TOO_HARD && hasMoreSets && isWeighted) { ... }
```

New gating:

```kotlin
if (state.lastFeedback == SetFeedback.TOO_HARD) {
    val moreSetsForThisExercise =
        state.completedSetIndex < PlannedExercise.DEFAULT_SETS - 1
    val isWeighted = plannedExercise.exercise.equipment != Equipment.BODYWEIGHT
        && plannedExercise.sessionWeight > 0f
    val showWeightDelta = moreSetsForThisExercise && isWeighted
    WeightReductionCard(
        sessionReps = plannedExercise.sessionReps,
        sessionWeight = plannedExercise.sessionWeight,
        weightUnit = weightUnit,
        applied = state.weightReductionApplied,
        showWeightDelta = showWeightDelta,
        onRepsSelected = onReduceWeight,
    )
}
```

`WeightReductionCard` gains a `showWeightDelta: Boolean` param. When `false`,
the "↓ X kg" line below each rep number is omitted; the button shows only
the rep count.

### Weight-reduction side effect

`reduceExerciseWeight` in `WorkoutSessionController`:

```kotlin
fun reduceExerciseWeight(completedReps: Int) {
    val resting = _state.value as? WorkoutState.Resting ?: return
    scope.launch {
        database.workoutSetDao().updateActualReps(resting.currentSetRowId, completedReps)
    }
    val moreSetsForThisExercise =
        resting.completedSetIndex < PlannedExercise.DEFAULT_SETS - 1
    val exercise = resting.plan.exercises[resting.exerciseIndex]
    if (!moreSetsForThisExercise || exercise.sessionWeight <= 0f) {
        // capture only; no weight change
        setState(resting.copy(weightReductionApplied = true))
        return
    }
    // existing weight-reduction logic unchanged from here
    ...
}
```

### DAO addition

```kotlin
@Query("UPDATE workout_sets SET actualReps = :reps WHERE id = :id")
suspend fun updateActualReps(id: Long, reps: Int?)
```

## Backfill

### Trigger

`StochasticStrengthApp.onCreate` launches a coroutine on `applicationScope`
(IO dispatcher) after the database is built:

```kotlin
applicationScope.launch(Dispatchers.IO) {
    val profile = database.userProfileDao().getProfile() ?: return@launch
    if (profile.actualRepsBackfilled) return@launch
    val weightUnit = profile.weightUnit
    ActualRepsBackfill(database, weightUnit).run()
    database.userProfileDao().insert(profile.copy(actualRepsBackfilled = true))
}
```

Startup is not blocked. If the job throws partway, the flag stays false and
the next launch retries. Idempotency comes from skipping rows where
`actualReps IS NOT NULL`.

### Algorithm

New file `domain/ActualRepsBackfill.kt`:

```kotlin
class ActualRepsBackfill(
    private val database: AppDatabase,
    private val weightUnit: WeightUnit,
) {
    suspend fun run() {
        val sessions = database.workoutSessionDao().getAll()
        for (session in sessions) {
            val sets = database.workoutSetDao().getSetsForSession(session.id)
            val bySetKey = sets.associateBy { it.exerciseId to it.setNumber }
            for (s in sets) {
                if (s.actualReps != null) continue
                val newReps = when (s.feedback) {
                    null, SetFeedback.HURT -> null
                    SetFeedback.RIR_0_1, SetFeedback.RIR_2_4, SetFeedback.RIR_5_PLUS ->
                        s.targetReps
                    SetFeedback.TOO_HARD -> {
                        val next = bySetKey[s.exerciseId to (s.setNumber + 1)]
                        if (next != null && next.targetWeight < s.targetWeight)
                            inferReps(s.targetWeight, next.targetWeight, s.targetReps)
                        else null
                    }
                } ?: continue
                database.workoutSetDao().updateActualReps(s.id, newReps)
            }
        }
    }

    private fun inferReps(from: Float, to: Float, targetReps: Int): Int? {
        for (candidate in (targetReps - 1) downTo 0) {
            val predicted = DefaultProgressionEngine.scaleReps(
                from,
                from = maxOf(1, candidate),
                to = targetReps,
            )
            val rounded = WeightFormatter.round(predicted, weightUnit)
            if (kotlin.math.abs(rounded - to) <= 0.5f) return candidate
        }
        return null
    }
}
```

Iterating `candidate` from high to low biases toward higher completed-rep
counts when rounding ties — matching the more conservative interpretation
the user would have most plausibly chosen had they tapped the prompt.

The 0.5 kg tolerance matches `DefaultProgressionEngine.INTERNAL_INCREMENT`
and the rounding actually emitted by the live code at write time.

### Edge cases handled by the algorithm

- **Manual mid-session weight adjustments** (e.g., from `PlanPreview`'s
  weight increment buttons) won't carry `feedback = TOO_HARD`, so the
  TOO_HARD branch is not reached and `actualReps` stays null.
- **Last set of an exercise marked TOO_HARD**: no `setNumber + 1` row for
  the same exercise; `actualReps` stays null.
- **Last set of the workout marked TOO_HARD**: same — no next row; null.
- **Manual weight nudge after a TOO_HARD that doesn't match any rep
  count**: `inferReps` returns null; `actualReps` stays null.
- **`WeightUnit` historical drift**: project has never changed
  representation. Calling out as a known non-issue.

### Where it lives

`domain/ActualRepsBackfill.kt`. Unit-testable with an in-memory Room
database.

## Testing

### Unit tests (`src/test/`)

- **`DefaultProgressionEngineTest`** — add a round-trip sanity test: for
  `targetReps ∈ {5, 8, 10}` and `completedReps ∈ 0..targetReps-1`,
  `inferReps(W, scaleReps(W, completedReps, target), target)` returns
  `completedReps` for representative `W` (e.g., 20, 40, 60, 80, 100 kg).
  Confirms the inversion is consistent with the forward formula at the
  expected tolerance.
- **`ActualRepsBackfillTest`** (new). Seed an in-memory Room DB with the
  following scenarios and assert post-run state:
  - `RIR_*` row → `actualReps = targetReps`
  - `TOO_HARD` row followed by `scaleReps(W, k, target)`-matching next row
    → `actualReps = k`
  - `TOO_HARD` row where two adjacent rep counts round to the same drop →
    `actualReps` is the higher of the two
  - `TOO_HARD` row as last set of exercise → null
  - `HURT` row → null
  - Manual mid-session weight nudge with no `TOO_HARD` → null
  - Row with `actualReps` already populated → unchanged
  - Re-run after partial pass → idempotent, no double-writes
- **`WorkoutSessionControllerTest`** (extend; create if absent):
  - Final set of workout flows `ActiveSet → Resting → Done` (was
    `ActiveSet → Done`)
  - `TOO_HARD` on final set of an exercise: rep buttons render, weight
    unchanged
  - `TOO_HARD` on non-final set of an exercise: weight reduction still
    applied
  - Rep tap on `WeightReductionCard` calls
    `WorkoutSetDao.updateActualReps`
  - `RIR_*` feedback inserts row with `actualReps = targetReps`
  - `HURT` feedback inserts row with `actualReps = null`
  - Undo from Resting deletes the row including any written `actualReps`

### Instrumented tests (`src/androidTest/`)

Migration test: `MIGRATION_9_10` and `MIGRATION_10_11` applied in sequence
to a v9 DB preserve existing rows and add the new nullable / default
columns.

### Manual verification

Run on emulator, complete a workout containing:

- one `TOO_HARD` on a middle set,
- one `TOO_HARD` on the last set of an exercise (but not last of workout),
- one `TOO_HARD` on the last set of the workout,
- at least one `RIR_*` and one `HURT`.

Use Android Studio's database inspector to confirm `actualReps` is
populated as expected (rep value for the first three, `targetReps` for
`RIR_*`, `null` for `HURT`).

## File-by-file impact

**Data layer**
- `data/model/WorkoutSet.kt` — add `actualReps: Int?`
- `data/model/UserProfile.kt` — add `actualRepsBackfilled: Boolean = false`
- `data/dao/WorkoutSetDao.kt` — add `updateActualReps(id, reps)`
- `data/AppDatabase.kt` — bump version to 11; add `MIGRATION_9_10` and
  `MIGRATION_10_11`; register both

**Domain**
- `domain/ActualRepsBackfill.kt` — new

**Workout flow**
- `ui/workout/WorkoutState.kt` — `Resting.currentSetRowId: Long`; drop
  `Done.lastExerciseIndex` and `Done.lastRecordedSetIndex`
- `ui/workout/WorkoutSessionController.kt` — restructure (see Going-forward
  capture above); insert returns id stored on `Resting`; new gating in
  `reduceExerciseWeight`; delete `undoLastSetFromDone`; `advanceAfterRest`
  is the sole entry to `finishWorkout`
- `ui/workout/WorkoutViewModel.kt` — drop `undoLastSetFromDone` plumbing
- `ui/workout/WorkoutScreen.kt` — new gating in `RestingContent`;
  `WeightReductionCard` gains `showWeightDelta: Boolean`; `DoneContent`
  drops `onUndo`

**App wiring**
- `StochasticStrengthApp.kt` — launch `ActualRepsBackfill` once, gated by
  `UserProfile.actualRepsBackfilled`

**Tests**
- `src/test/.../DefaultProgressionEngineTest.kt` — extend
- `src/test/.../ActualRepsBackfillTest.kt` — new
- `src/test/.../WorkoutSessionControllerTest.kt` — extend or create
- `src/androidTest/.../MigrationTest.kt` — new or extended

No changes to: `domain/ProgressionEngine.kt`,
`domain/WorkoutRepository.applySessionProgression`,
`domain/strava/StravaJsonBuilder.kt`.

## Out of scope

- Surfacing `actualReps` in Strava exports.
- Showing `actualReps` on the workout summary or history screens.
- Editing `actualReps` after the fact.
- Using `actualReps` in the progression engine (could enable finer
  feedback in the future, but the engine currently consumes `feedback`
  only).
