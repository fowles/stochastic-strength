# Mid-workout corrections design

**Date:** 2026-06-20

## Problem

Partway through a workout the prescription can become impossible or unwanted:
the equipment for an exercise isn't present, the available weight is too light or
only comes in the wrong increments, the user dislikes the movement, or something
comes up and they need to stop. Today these corrections only exist on the
**PlanPreview** screen (swap / dislike / weight-adjust). Once a set is in progress
there is no unobtrusive way to fix any of it.

This feature adds an unobtrusive correction menu to the **warmup and working set
screens** (and timed sets), consistent across all set types.

## Goals

- A single, consistent correction affordance on every in-progress set screen.
- Swap the current exercise for lack of equipment (also updating the location's
  equipment list) or because the user dislikes it.
- Adjust the prescribed weight when it's off or in the wrong increments —
  **without** touching the muscle baseline; the progression engine infers the
  effect from the logged sets.
- Stop the current exercise with no negative signal (a neutral parallel to
  `HURT`).
- Stop the whole workout because something came up.
- All actions land on the rest page so an accidental tap can be undone.

## Non-goals

- No changes to the progression engine, baseline math, or `strengthOverrides`.
- No changes to the notification/bus command flow — these actions are in-app only.
- No new corrective actions on the Resting screen itself (it already has
  Undo / Skip Rest / weight-reduction).

## UI

### Shared `SetActionsMenu`

A kebab overflow icon (`Icons.Default.MoreVert`) is added to the top-right of
`ExerciseSetLayout` so warmup, working, and timed sets all expose it
identically. The timed-set layout (`TimedSetContent`) gets the same icon.

Tapping opens a `DropdownMenu` with five items:

1. **Adjust weight** — opens the weight modal (below). Disabled when the
   exercise is unweighted (`sessionWeight == 0f` / unloadable).
2. **Swap — no equipment**
3. **Swap — don't like it**
4. **End exercise**
5. **Stop workout**

### Weight modal

"Adjust weight" opens a **modal dialog** over the current view (not inline in the
menu). The dialog tracks a **local** working weight (it does not touch the
controller until committed) and shows:

- the exercise name,
- a `[−]  <weight>  [+]` stepper at the app increment (2.5 kg / 5 lb),
- the **plate breakdown** (`WeightFormatter.platesPerSide(workingWeight, unit)`)
  when available (barbell), updating live on every ± tap,
- **Done** and **Cancel**.

Each ± tap updates only the local working weight (and thus the displayed plates).
**Cancel** discards everything. **Done** calls `controller.setActiveSetWeight(newWeight)`
once, which stages the change onto the rest page (see Adjust weight, below).

## The staged-rest model

**Every menu action lands on the rest page so any accidental tap can be undone.**
A menu action does not mutate the database or commit its change at tap time;
instead it transitions the current `ActiveSet` into a `Resting` state that carries
a **staged action**. The staged change is *committed* when the rest auto-completes
(timer) or is skipped, and *discarded* when the user taps **Undo**. Deferring the
side-effects to commit means Undo needs no rollback — it simply returns to the
captured originating `ActiveSet`.

The existing post-feedback rest (from `recordFeedback`) is unchanged: it has no
staged action and keeps its current logged-row / `completedSetIndex` behavior.

### State model changes (`WorkoutState.Resting`)

`lastFeedback` becomes nullable and one field is added:

```kotlin
data class Resting(
    // ...existing fields...
    val lastFeedback: SetFeedback?,        // null when the rest logged no set
    val currentSetRowId: Long,             // NO_ROW (-1L) for staged-action rests
    val staged: StagedAction? = null,      // non-null for menu-action rests
) : WorkoutState

data class StagedAction(
    val kind: StagedKind,                  // for the rest-page label only
    val undoTarget: WorkoutState.ActiveSet,    // Undo returns here (full revert)
    val commitTarget: WorkoutState.ActiveSet?, // rest-complete goes here; null => finish workout
    val pendingSwap: PendingSwap? = null,      // DB side-effect, applied on commit (SWAP only)
)

data class PendingSwap(
    val reason: ExerciseRemovalReason,
    val exerciseId: Long,
    val locationId: Long?,
)

enum class StagedKind { SWAP, ADJUST_WEIGHT, END_EXERCISE, STOP_WORKOUT }
```

`NO_ROW = -1L` is a companion constant. For a staged rest, `Resting.plan` mirrors
`commitTarget?.plan ?: undoTarget.plan` so the remaining-exercise list shows the
post-action plan.

### `advanceAfterRest` (commit)

```
staged != null:
    staged.pendingSwap?.let { persist(it) }   // dislike / exclusion written here
    staged.commitTarget?.let { setState(it) } ?: finishWorkout(plan, sessionId)
    return
// else: existing logic (next set / next exercise / finish)
```

### `undoLastSet`

```
staged != null:  setState(staged.undoTarget); return
// else: existing logic (delete the logged row, restore from its set number)
```

Because side-effects live in `pendingSwap` and are only applied on commit, Undo
restoring `undoTarget` fully reverts the action with no database rollback.

## Behaviors

Each `controller.<action>()` runs on the current `ActiveSet`, builds a
`StagedAction`, and transitions to a staged `Resting` (with the normal
`REST_SECONDS` timer). `undoTarget` is always the originating `ActiveSet`
(captured before any mutation).

### "Has logged sets" predicate

Whether the current exercise already has logged sets this session is known
in-memory, no DB query:

```
hasLogged = (warmupSetIndex == null && setIndex > 0)
```

Each completed working set advances `setIndex` via `Resting.completedSetIndex + 1`;
warmups and set 0 mean nothing has been logged for the current exercise yet.

**Rule: leaving an exercise with zero logged sets removes it from the plan;
leaving one with logged sets keeps it (its sets happened and feed progression).**

### Swap (no equipment / dislike)

`controller.swapCurrentExercise(reason: ExerciseRemovalReason)`:

1. Build the post-action plan: add the rejected id to `plan.sessionRejectedIds`
   and call `planner.pickReplacement(plan, currentIndex, listOf(WEIGHTED_MUSCLE,
   MUSCLE, ANY))` (tiered fallback — see below). `sessionRejectedIds` keeps the
   pick from re-selecting the rejected exercise without needing a DB write yet.
   - `hasLogged` → keep original at `i` (its logged sets stay), insert replacement
     at `i + 1`; `commitTarget` = `ActiveSet(exerciseIndex = i + 1, setIndex = 0,
     warmup if any)`.
   - `!hasLogged` → replace in place: drop original at `i`, put replacement at
     `i`; `commitTarget` = `ActiveSet(exerciseIndex = i, ...)`. No 0-set ghost.
   - Replacement `null` → don't insert; if `!hasLogged` remove the original.
     `commitTarget` = `ActiveSet` at whatever now sits at `i` (or `null` to finish
     if the plan is empty / `i` past the end).
   - The replacement's `originalSessionWeight = sessionWeight` so end-of-session
     reduction tracking works.
2. `pendingSwap = PendingSwap(reason, exerciseId, locationId)`. On **commit**,
   `advanceAfterRest` persists it (mirrors PlanPreview's `replaceExercise`):
   `DISLIKE` → `exerciseDao.update(isDisliked = true)`; `NO_EQUIPMENT` →
   `repository.excludeExercise(locationId, exerciseId)` (no-op if location
   unknown); then the planner is rebuilt so later actions see the change.

### Adjust weight (plan-only)

`controller.setActiveSetWeight(newWeight: Float)` (called once on modal Done):

- `w = WeightFormatter.round(newWeight).coerceAtLeast(increment)`. Build the
  post-action plan with `plan.exercises[i].sessionWeight = w`, recomputing warmups
  **only** while still in warmup (`warmupSetIndex != null`).
- `commitTarget` = `ActiveSet` at the **same** coordinates (exerciseIndex,
  setIndex, warmupSetIndex) on the post-action plan — i.e. resume the same set at
  the new weight. `kind = ADJUST_WEIGHT`, `pendingSwap = null`.
- **No** `strengthOverrides` write and **no** baseline derivation — unlike
  PlanPreview's `adjustExerciseWeight`. The changed `targetWeight` is recorded on
  each logged set; the existing `completeWorkout` reduction map and the
  progression engine infer the implied baseline effect. A decrease below
  `originalSessionWeight` is already captured by the reduction map.

### End exercise (neutral stop)

`controller.endCurrentExercise()` — neutral parallel to `HURT`: logs **no** set,
produces **no** signal.

- `hasLogged` → original stays in plan; `commitTarget` = next exercise
  (`ActiveSet(exerciseIndex = i + 1, ...)`), or `null` to finish if it was last.
- `!hasLogged` → remove the original from the post-action plan; `commitTarget` =
  the exercise now at `i` (`ActiveSet(exerciseIndex = i, ...)`), or `null` to
  finish. `kind = END_EXERCISE`.

### Stop workout (neutral)

`controller.stopWorkout()` — `kind = STOP_WORKOUT`, `commitTarget = null` (commit
→ `finishWorkout` → Done), `pendingSwap = null`. No set logged. Undo restores the
originating set.

## `pickReplacement` tiering parameter

`WorkoutPlanner.pickReplacement` gains an ordered fallback parameter controlling
its variability:

```kotlin
enum class ReplacementTier { WEIGHTED_MUSCLE, MUSCLE, ANY }

fun pickReplacement(
    plan: WorkoutPlan,
    removedIndex: Int,
    tiers: List<ReplacementTier> = listOf(ReplacementTier.ANY),
): PlannedExercise?
```

It walks `tiers` in order and picks from the **first non-empty** tier, each tier
filtering `candidatesFor(...)` (which already excludes in-plan ids,
`sessionRejectedIds`, and recently-failed muscles) and still passing through
`WorkoutGenerator.pickReplacement` (per-muscle cap + random):

- `WEIGHTED_MUSCLE` → same `primaryMuscle` **and** same loaded-ness as the removed
  exercise. Loaded-ness: `coefficientSource.get(exercise)?.let { it > 0f } ?: false`.
- `MUSCLE` → same `primaryMuscle`.
- `ANY` → all candidates.

**Callers:**

- PlanPreview rejection (`replaceExercise`) and the location-refresh swap
  (`onLocationRefreshed`) call with the default `listOf(ANY)` — behavior is
  unchanged, deliberately keeping their high variability (no muscle / weightedness
  preservation).
- The mid-workout swap calls `listOf(WEIGHTED_MUSCLE, MUSCLE, ANY)` to realize
  "ideally same muscle + weighted-ness → same muscle → any".

`pickAdditional` is unaffected (it doesn't go through `pickReplacement`).

## Wiring

- `WorkoutViewModel` gains `swapCurrentExercise(reason)`,
  `setActiveSetWeight(newWeight)`, `endCurrentExercise()`, `stopWorkout()`,
  delegating to the controller.
- `WorkoutScreen` passes the new callbacks into `ActiveSetContent` and
  `WarmupSetContent`, which forward them to `ExerciseSetLayout` /
  `SetActionsMenu` and own the weight-modal visibility + local working weight.
- No new `WorkoutCommand`s; the notification/bus flow is unchanged.

## `RestingContent` changes

- `staged != null` → show a subtitle from `staged.kind` instead of
  "Logged: <feedback>": `STOP_WORKOUT` → "Finishing workout", `END_EXERCISE` →
  "Exercise stopped", `SWAP` → "Swapped exercise", `ADJUST_WEIGHT` →
  "Weight changed".
- Suppress the "Next up" card when `staged?.commitTarget == null` (workout
  ending); otherwise the card can be derived from `commitTarget`.
- The TOO_HARD weight-reduction cards already guard on
  `lastFeedback == SetFeedback.TOO_HARD`, so a null feedback hides them.

## Testing

Controller-level tests (JVM, no device). Each staged action asserts both the
**commit** path (advance/skip the rest) and the **undo** path (returns to the
originating `ActiveSet` with no DB side-effect written):

- Swap with logged sets keeps the original and inserts the replacement at `i+1`;
  undo restores the original set and leaves no dislike/exclusion persisted.
- Swap with no logged sets replaces in place (no 0-set ghost).
- Swap with no candidate and no logged sets removes the original and advances.
- Swap commit persists the dislike / exclusion; undo before commit does not.
- `pickReplacement` with `listOf(WEIGHTED_MUSCLE, MUSCLE, ANY)`: same-muscle+loaded
  chosen over same-muscle-unloaded over any; default `listOf(ANY)` behavior
  unchanged.
- Adjust weight stages a same-set resume at the new weight; commit changes only
  `plan.exercises[i].sessionWeight` (baseline / `strengthOverrides` untouched, the
  change flows into the logged set's `targetWeight` and reduction map); undo
  restores the original weight.
- End exercise with logged sets: logs nothing, rests, advances to next on commit;
  undo restores the originating set.
- End exercise with no logged sets: removes the exercise; commit advances to the
  shifted-in exercise; undo restores it at its index.
- Stop workout: rests, then finishes on commit; undo restores the originating set
  (including warmup-originated).
