# Exercises "Recent" section — design

## Goal

Add a **Recent** section pinned at the top of the Exercises list showing the 7
exercises the user has performed most recently. Rows are duplicates of the same
exercises that continue to appear in their muscle-group sections below.

## Behavior

- Contents: the 7 exercises with the most recent completed set (`MAX(completedAt)`),
  restricted to exercises that have a sparkline (present in the existing
  `sparklines` map — i.e. ≥2 windowed points). Ordered most-recent-first.
- **Respects the active filters.** When a muscle and/or equipment chip is selected,
  Recent is drawn from the same filtered pool, so it stays consistent with the list
  below. May show fewer than 7 if the filtered pool is smaller.
- Rows reuse the existing `ExerciseRow` composable: same tap → detail navigation,
  same Disliked/Hurt badges and sparkline.
- The exercise still also appears in its muscle-group section below (intentional
  duplication).
- **Sticky "Recent" header**, identical treatment to the muscle-group headers
  (`surfaceContainer` bar, `primary` text).

## Implementation

1. **DAO** (`WorkoutSetDao`): add
   `getLastCompletedAtByExercise(): List<ExerciseLastCompleted>` where
   `ExerciseLastCompleted(exerciseId, lastCompletedAt)`, selecting
   `MAX(completedAt)` grouped by `exerciseId` over non-null `completedAt`.
   Mirrors the existing `getFirstCompletedAtByExercise`.
2. **Repository** (`WorkoutRepository`): add
   `suspend fun getLastPerformedByExercise(): Map<Long, Long>`.
3. **ViewModel** (`ExercisesViewModel`): load `lastPerformed: Map<Long, Long>` into a
   `StateFlow`, folded into the same one-shot `withContext(Dispatchers.Default)`
   block that builds the sparklines.
4. **Screen** (`ExercisesScreen`): derive `recentExercises` via
   `remember(exercises, selectedFilter, selectedEquipmentFilter, sparklines, lastPerformed)`
   = filtered exercises that are in `sparklines.keys`, sorted by `lastPerformed`
   descending, `take(7)`. Render a `stickyHeader("Recent")` + `items(...)` block
   before the muscle-group loop. **Recent row keys are prefixed** (`"recent-${id}"`)
   to avoid LazyColumn key collisions with the muscle-section rows.

## Non-goals / impact

- No Room schema change or migration (read-only aggregate query).
- No belief/progression or backtest impact.
