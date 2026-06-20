# Adaptive Workout Duration Estimate

## Problem

`WorkoutPlan.estimatedDurationSeconds` (shown on the plan-preview screen as
`"$durationMin min · …"`) is computed from three fixed constants:

```kotlin
// domain/model/WorkoutPlan.kt
val estimatedDurationSeconds: Int
    get() = exercises.sumOf {
        PlannedExercise.DEFAULT_SETS * it.secondsPerSet +
            it.warmupSets.size * PlannedExercise.SECONDS_PER_WARMUP_SET
    }

// domain/model/PlannedExercise.kt
val secondsPerSet: Int = when {
    exercise.isTimed -> 90
    exercise.isUnilateral -> 180
    else -> 135
}
```

These constants approximate the active-set + rest time, but in practice the
wall-clock time of an exercise is dominated by *setup* — collecting plates,
adjusting a machine, finding a bench. That setup varies a lot by exercise and
not at all by the constants above. The displayed estimate is frequently off.

We already store enough history to do better: every working set is logged with
a `completedAt` timestamp, so we can derive how long each past appearance of an
exercise actually took.

## Goal

Replace the constant-driven per-exercise estimate with a per-exercise average
of the wall-clock time observed over recent history. Fall back to the existing
formula when there's no usable history for an exercise.

## Non-goals

- No new heuristic-engine wiring (no persistence to a new table, no debug
  screen, no scheduling around session completion). The estimate is recomputed
  on the fly when the planner is built.
- No change to UI — the plan-preview header keeps reading
  `plan.estimatedDurationSeconds`; it just gets a better number.
- No change to warmup-set or rest-timer behavior.

## Measurement: wall-clock per appearance

For each completed past session and each exercise that appeared in it, derive a
single "wall-clock seconds for this appearance" value:

```
end             = max(completedAt) among this exercise's sets in the session
firstCompleted  = min(completedAt) among this exercise's sets in the session
predecessorEnd  = max(completedAt) across ALL sets in the session whose
                  completedAt < firstCompleted
                  (fall back to session.startTime if no such set exists)
appearanceSeconds = (end − predecessorEnd) / 1000
```

This attributes setup, warmups, working sets, and inter-set rest to the
exercise. Transition time (the gap between finishing the prior exercise and
starting this one) attributes to whichever exercise came next — consistently,
so it averages out.

**Skip rules** (an appearance is dropped from the sample if any holds):

- Any of the exercise's sets has `feedback = SetFeedback.HURT`. A HURT set
  terminates the exercise early (see `WorkoutSessionController.recordFeedback`,
  line ~253), so its wall-clock is artificially short.
- Any of the exercise's sets has a null `completedAt`.
- `appearanceSeconds` is outside `[60, 1200]`. 60 s is a sanity floor (no real
  exercise takes < a minute); 1200 s (20 min) is the upper cap — no real
  exercise takes that long, so an apparent 20 + minute appearance almost
  certainly means the user paused for a phone call or food break and isn't a
  useful sample.

## Aggregation per exercise

Iterate past completed sessions newest-first; for each exercise, collect
appearances until **10** have been gathered. Then:

```
if (samples.isEmpty()) -> no learned value (planner falls back to current formula)
else                   -> learnedSeconds[e] = round(mean(samples))
```

Simple mean — the clamp + HURT skip already drop the worst outliers, and a
small window means any drift gets corrected quickly by subsequent sessions.

## Plumbing

### New DAO query

Add to `WorkoutSessionDao`:

```kotlin
@Query("""
    SELECT * FROM workout_sessions
    WHERE endTime IS NOT NULL
    ORDER BY startTime DESC
    LIMIT :limit
""")
suspend fun getRecentCompletedSessions(limit: Int): List<WorkoutSession>
```

Add to `WorkoutSetDao`:

```kotlin
@Query("""
    SELECT * FROM workout_sets
    WHERE sessionId IN (:sessionIds)
      AND completedAt IS NOT NULL
""")
suspend fun getSetsForSessions(sessionIds: List<Long>): List<WorkoutSet>
```

These are pure additions — no migration needed.

### New domain class

```kotlin
// domain/ExerciseDurationEstimator.kt
class ExerciseDurationEstimator(
    private val secondsByExerciseId: Map<Long, Int>,
) {
    fun secondsFor(exerciseId: Long): Int? = secondsByExerciseId[exerciseId]

    companion object {
        const val MAX_APPEARANCES = 10
        const val MIN_SECONDS = 60
        const val MAX_SECONDS = 1200

        fun build(
            sessionsNewestFirst: List<WorkoutSession>,
            setsBySessionId: Map<Long, List<WorkoutSet>>,
        ): ExerciseDurationEstimator { /* see below */ }

        val EMPTY = ExerciseDurationEstimator(emptyMap())
    }
}
```

The `build` factory:

1. Iterates `sessionsNewestFirst`.
2. For each session, groups its sets by `exerciseId`.
3. For each `(session, exerciseId)` pair, applies the measurement above and
   the skip rules.
4. Appends qualifying samples to a `MutableMap<Long, MutableList<Int>>`,
   stopping appending for an exercise once it has `MAX_APPEARANCES`.
5. Reduces to `Map<Long, Int>` of rounded means.

Pure function, no IO — directly unit-testable.

### Wire-up

In `WorkoutRepository.buildPlanner` (currently lines 43–69):

```kotlin
val recentSessions = db.workoutSessionDao().getRecentCompletedSessions(limit = 50)
val recentSets = if (recentSessions.isNotEmpty())
    db.workoutSetDao().getSetsForSessions(recentSessions.map { it.id })
        .groupBy { it.sessionId }
else emptyMap()
val durationEstimator = ExerciseDurationEstimator.build(recentSessions, recentSets)
```

50 sessions is wide enough that a steadily-rotated exercise should reach the
`MAX_APPEARANCES = 10` cap, while a less-frequent one still contributes
whatever samples it has.

Pass `durationEstimator` into `WorkoutPlanner`'s constructor.

In `WorkoutPlanner.withWeight` (and any other site that constructs a
`PlannedExercise` — `pickReplacement` and `pickAdditional` both route through
`withWeight`), set the new field:

```kotlin
private fun withWeight(pe: PlannedExercise, sessionReps: Int): PlannedExercise {
    val learned = durationEstimator.secondsFor(pe.exercise.id)
    // ... existing weight/warmup computation ...
    return pe.copy(
        sessionWeight = weight,
        sessionReps = sessionReps,
        warmupSets = computeWarmupSets(weight),
        estimatedSecondsOverride = learned,
    )
}
```

### `PlannedExercise` and `WorkoutPlan`

```kotlin
data class PlannedExercise(
    val exercise: Exercise,
    val sessionWeight: Float = 0f,
    val originalSessionWeight: Float = sessionWeight,
    val sessionReps: Int = 10,
    val warmupSets: List<WarmupSet> = emptyList(),
    val estimatedSecondsOverride: Int? = null,
) {
    val secondsPerSet: Int = when { /* unchanged */ }

    val estimatedSeconds: Int
        get() = estimatedSecondsOverride
            ?: (DEFAULT_SETS * secondsPerSet + warmupSets.size * SECONDS_PER_WARMUP_SET)

    companion object { /* unchanged */ }
}

data class WorkoutPlan(/* unchanged fields */) {
    val estimatedDurationSeconds: Int
        get() = exercises.sumOf { it.estimatedSeconds }
}
```

`adjustExerciseWeight` already calls `pe.copy(sessionWeight = …, warmupSets =
…)` — the `estimatedSecondsOverride` field is preserved by `copy`, so weight
adjustments don't blow away the learned value.

## Tests

`ExerciseDurationEstimatorTest`:

- `build` with empty input → `secondsFor(any) == null`.
- Single appearance with three sets → returns rounded wall-clock from
  predecessor's `completedAt` to this exercise's last set.
- First exercise in session → predecessor falls back to `session.startTime`.
- Predecessor across exercises: previous exercise's last set ends at T,
  current exercise's last set ends at T+300 → 300 s.
- HURT in any set → appearance skipped; if it was the only one, exercise has
  no learned value.
- Clamp floor: appearance computes to 30 s → skipped.
- Clamp ceiling: appearance computes to 1500 s → skipped.
- Multiple appearances → mean rounded.
- More than `MAX_APPEARANCES` appearances → only the 10 most recent counted.
- Null `completedAt` on any set in the appearance → skipped.

`WorkoutPlanTest` (light):

- `estimatedDurationSeconds` sums per-exercise; uses override where set, falls
  back to formula otherwise.

`PlannedExerciseTest` (light):

- `estimatedSeconds` returns override when non-null; falls back to existing
  formula when null.

## Risk and rollback

- All changes are additive (one new domain class, one new field on
  `PlannedExercise`, two new DAO queries). No schema migration.
- If something goes wrong with the estimator, the fallback path is the
  existing formula — already exercised in tests.
- Rollback = revert the commit. No persisted state to clean up.

## Out of scope (for follow-up)

- Showing the learned per-exercise duration on the exercise-detail or debug
  screens. The plan-preview total is enough for the initial cut.
- Adapting warmup-set duration similarly — current data doesn't record warmup
  timings, and they're a small fraction of the wall-clock.
- Modeling between-exercise transition time as its own bucket. Currently it
  rolls into whichever exercise comes next; that's consistent and good enough.
