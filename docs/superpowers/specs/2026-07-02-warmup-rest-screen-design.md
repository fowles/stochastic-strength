# Warmup-to-Working-Set Rest Screen

**Date:** 2026-07-02

## Problem

After the last warmup set the user taps "Start Working Sets" and is immediately shown the first working set. There is no rest period, even though the user may want 90 seconds to recover before their first heavy set.

## Goal

Show the standard 90-second rest screen between the final warmup set and the first working set.

## Approach: Reuse staged-rest mechanism with `StagedKind.WARMUP_DONE`

The existing staged-rest path (used for swap/adjust-weight/end-exercise/stop-workout) already provides:
- A timed `Resting` state that auto-advances or can be skipped
- Undo that returns to the `undoTarget` `ActiveSet`
- Notification state derivation
- `advanceAfterRest()` that commits to `commitTarget`

Adding `WARMUP_DONE` as a new `StagedKind` value plugs into all of this for free.

## Changes

### 1. `WorkoutState.kt` — new `StagedKind` value

```kotlin
enum class StagedKind { SWAP, ADJUST_WEIGHT, END_EXERCISE, STOP_WORKOUT, WARMUP_DONE }
```

### 2. `WorkoutSessionController.completeWarmupSet()`

Currently: when the last warmup is done, sets `warmupSetIndex = null` (goes straight to working set).

New: calls `stageRest()` instead.

```kotlin
fun completeWarmupSet() {
    val current = _state.value as? WorkoutState.ActiveSet ?: return
    val warmupIdx = current.warmupSetIndex ?: return
    val nextIdx = warmupIdx + 1
    if (nextIdx < current.plannedExercise.warmupSets.size) {
        setState(current.copy(warmupSetIndex = nextIdx))
    } else {
        val commitTarget = WorkoutState.ActiveSet(
            plan = current.plan,
            exerciseIndex = current.exerciseIndex,
            setIndex = 0,
            sessionId = current.sessionId,
            warmupSetIndex = null,
        )
        stageRest(current, StagedAction(
            kind = StagedKind.WARMUP_DONE,
            undoTarget = current,
            commitTarget = commitTarget,
        ))
    }
}
```

**Undo behavior:** `undoLastSet()` on a staged `Resting` already calls `setState(staged.undoTarget)` — returns to the last warmup `ActiveSet`. No DB row exists for warmup sets so no deletion is needed.

**Notification state:** The `else` branch in `deriveNotificationState()` already handles this: "Next: [exercise name]".

**Timer:** `stageRest()` calls `startRestTimer()` — identical 90-second countdown.

### 3. `RestingContent.kt` — subtitle and up-next card

**Subtitle** (`when (state.staged?.kind)` block):
```kotlin
StagedKind.WARMUP_DONE -> "Warmup complete"
```

**Up-next card** (inside the `state.staged != null` branch):

The existing code calls `up.warmupSets.firstOrNull()` to pick title/weight. For `WARMUP_DONE` the commit target is the same exercise still carrying its `warmupSets` list, so without special-casing it would wrongly show "Warm up" + warmup weight. Fix:

```kotlin
state.staged != null -> {
    val up = state.staged.commitTarget
        ?.let { it.plan.exercises.getOrNull(it.exerciseIndex) }
    if (up != null) {
        val isWarmupDone = state.staged.kind == StagedKind.WARMUP_DONE
        val warmup = if (isWarmupDone) null else up.warmupSets.firstOrNull()
        NextExerciseCard(
            title = if (isWarmupDone) "First set"
                    else if (warmup != null) "Warm up"
                    else "Up next",
            exerciseName = up.exercise.name,
            weight = warmup?.weight ?: up.sessionWeight,
            equipment = up.exercise.equipment,
            weightUnit = weightUnit,
        )
    }
}
```

**Remaining-exercise list:** already reads `commitTarget.setIndex = 0` → shows 3 sets remaining for the current exercise. No change needed.

## What does NOT change

- `WarmupSetContent` button text ("Start Working Sets") — the intent is the same; the rest is now inserted after the tap
- `advanceAfterRest()` — the staged path handles `WARMUP_DONE` without modification
- `deriveNotificationState()` — the `else` branch covers the new kind
- `undoLastSet()` — staged undo path already works
- Rest duration — same `REST_SECONDS` (90 s)
- No new composables, no new states, no new DB changes

## Testing

- Unit test: `completeWarmupSet()` on last warmup transitions to `WorkoutState.Resting` with `staged.kind == WARMUP_DONE` and correct `undoTarget`/`commitTarget`
- Unit test: `advanceAfterRest()` from WARMUP_DONE resting state transitions to `ActiveSet(setIndex=0, warmupSetIndex=null)`
- Unit test: `undoLastSet()` from WARMUP_DONE resting state returns to last warmup `ActiveSet`
- Manual: tap "Start Working Sets" → rest screen shows "Warmup complete" + "First set: [exercise] [weight]" + 90 s countdown
- Manual: skip rest → first working set
- Manual: undo → last warmup set
