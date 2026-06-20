# Rest Screen: Next Exercise Preview

## Overview

During the rest between sets, the rest screen shows a small card area (20% of the screen) below the timer. Currently this area only shows `WeightReductionCard` when the user tapped "Too Heavy." After the last set of any exercise, that card area is always empty — wasted space at exactly the moment the user most needs to know what to load next.

This spec adds a `NextExerciseCard` composable that fills this area with the weight (and plate breakdown) of what comes next.

## The New Composable

`NextExerciseCard` takes:

- `title: String` — e.g., "Next up", "Warm up", "Reduced weight"
- `exerciseName: String`
- `weight: Float` — in kg
- `equipment: Equipment`
- `weightUnit: WeightUnit`

It displays the title in label style, the exercise name, the formatted weight, and — when equipment is `BARBELL` and weight exceeds the bar — the `WeightFormatter.platesPerSide()` string.

## Three Call Sites

All three live in the existing card-area `Box` inside `RestingContent`.

### "Reduced weight"

**When**: `WeightReductionCard` is `applied && weightReduced` (weight was lowered for the remaining sets of the current exercise).

**Weight shown**: `currentExercise.sessionWeight` (the already-reduced value stored in state).

**Change**: removes the bespoke overlay Column currently inside `WeightReductionCard` and replaces it with `NextExerciseCard`.

### "Warm up"

**When**: `completedSetIndex >= PlannedExercise.DEFAULT_SETS - 1` (last set) AND a next exercise exists AND `nextExercise.warmupSets.isNotEmpty()`.

**Weight shown**: `nextExercise.warmupSets[0].weight`.

### "Next up"

**When**: `completedSetIndex >= PlannedExercise.DEFAULT_SETS - 1` (last set) AND a next exercise exists AND `nextExercise.warmupSets.isEmpty()`.

**Weight shown**: `nextExercise.sessionWeight`.

## Conflict Analysis

`WeightReductionCard` (and its "Reduced weight" state) only shows when `lastFeedback == TOO_HARD && hasMoreSets`. "Warm up" and "Next up" only show when it is the last set (`!hasMoreSets`). The three call sites are mutually exclusive.

## What Doesn't Change

- `WorkoutState.Resting` — no new fields
- `WorkoutViewModel` — no changes
- `WeightFormatter` — already handles all formatting and plate math
- `PlannedExercise` / `WarmupSet` — already carry all needed data
