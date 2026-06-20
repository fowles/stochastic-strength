# Detraining Baseline Reduction Prompt

**Date:** 2026-06-20
**Status:** Approved design

## Problem

When a user returns after a layoff, their stored per-muscle baselines no longer
reflect their current strength. Starting a workout at pre-layoff weights is
discouraging and risks injury. We want to offer — not force — a baseline
reduction that accounts for time off when the user starts a workout after a gap.

## Summary

On entering the workout flow, if the most recent completed session is at least
one full week in the past, show a dialog over the plan preview. The dialog
suggests a uniform baseline reduction (`5% × whole weeks off`, capped at 50%),
lets the user fine-tune it with a slider, and live-previews the resulting
per-muscle baselines via the existing `StrengthGrid`. Applying it lowers every
muscle baseline by the chosen fraction for this session; skipping changes
nothing.

## Decisions

- **Decay model:** suggested fraction = `min(0.50, 0.05 × floor(weeksOff))`.
  Sub-week gaps suggest 0% (no prompt).
- **Trigger:** offer the dialog when `weeksOff >= 1` (suggestion ≥ 5%).
- **Scope:** uniform — one slider value applied to every muscle baseline.
- **Gap source:** the single most recent *completed* workout session
  (global, not per-muscle).
- **Tag:** persisted/replayed under a new `BaselineChangeReason.DETRAIN`,
  distinct from manual `OVERRIDE`.

## Components

### 1. `DetrainingModel` (pure, JVM-testable)

New object in `domain/`. No Android or DB dependencies.

```kotlin
object DetrainingModel {
    const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val PER_WEEK = 0.05f
    const val MAX_FRACTION = 0.50f

    fun weeksOff(lastEndTime: Long, now: Long): Int =
        ((now - lastEndTime) / WEEK_MILLIS).toInt().coerceAtLeast(0)

    fun suggestedFraction(weeksOff: Int): Float =
        (PER_WEEK * weeksOff).coerceAtMost(MAX_FRACTION)

    fun qualifies(weeksOff: Int): Boolean = weeksOff >= 1
}
```

Reduction applied per muscle: `newBaseline = currentBaseline * (1 - fraction)`.
Baselines are not grid-rounded here (consistent with the unrounded-baseline
controller); rounding happens only at planner weight selection.

### 2. Trigger & gap computation

In `WorkoutSessionController.initializeSession` / preview load:

1. Build the planner and generate the preview as today.
2. Query `workoutSessionDao().getRecentCompletedSessions(limit = 1)`.
3. If a session exists and `DetrainingModel.qualifies(weeksOff(lastEnd, now))`,
   surface the dialog with the suggested fraction as its default. Otherwise
   proceed straight to the normal preview.

The dialog is presented over the existing `PlanPreview` state (see Flow below),
so the documented 5-state machine is unchanged.

### 3. Dialog UI

A Material3 dialog (`ui/workout/`), shown over the dimmed plan preview:

- Headline: *"Welcome back — it's been N weeks."*
- One-line explanation that baselines can be eased down for time off.
- **Live `StrengthGrid`** fed a derived list:
  `currentStrengths.map { it.copy(baselineWeight = it.baselineWeight * (1 - fraction)) }`,
  `tapTargets = emptyMap()`, `onTap = {}`. Recomposes as the slider moves so the
  user sees the exact post-reduction baselines.
- Slider `0f..0.50f`, `steps` for 5% increments, defaulted to the suggested
  fraction; current % shown as a label.
- **Apply** (uses the slider value) and **Skip** (no change; dismiss = Skip).
- Content column scrolls vertically if the grid is tall.

Inputs needed: a snapshot of current `MuscleGroupStrength` list (from
`derivedState`/the planner) and `weightUnit` — both available at preview time.

### 4. Persistence

- **Enum:** add `DETRAIN` to `BaselineChangeReason`.
- **Entity:** add `reason: BaselineChangeReason = BaselineChangeReason.OVERRIDE`
  to `BaselineOverride`. Today the row carries no reason and replay infers it
  (`sessionId == null` → `INITIAL`, else → `OVERRIDE`); the discriminator lets
  detrain rows be distinguished from manual edits, which share `sessionId != null`.
- **Migration:** `AppDatabase` v15 → v16, `MIGRATION_15_16`:
  `ALTER TABLE baseline_override ADD COLUMN reason TEXT NOT NULL DEFAULT 'OVERRIDE'`.
  Proper migration — no destructive fallback (real-users policy). `INITIAL` rows
  have `sessionId == null` and are still treated as initial regardless of the
  defaulted reason.
- **Plan model:** `WorkoutPlan` gains
  `detrainOverrides: Map<MuscleGroup, Float> = emptyMap()` alongside the existing
  manual `strengthOverrides`. `buildPlanner` is called with the two merged
  (manual takes precedence) so previewed and performed weights reflect the
  reduction.
- **Repository:** new
  `applyDetrainingReduction(sessionId, overrides: Map<MuscleGroup, Float>)`
  mirroring `applyManualBaselineOverrides` but inserting rows with
  `reason = DETRAIN`. `applyManualBaselineOverrides` inserts `reason = OVERRIDE`.
- **Session start (`startFirstExercise`):** after inserting the session, call
  both `applyDetrainingReduction(sessionId, plan.detrainOverrides)` and the
  existing `applyManualBaselineOverrides(sessionId, plan.strengthOverrides)`.
- **Replay:** `getNonInitials()` rows now carry `reason`; the emitted
  `BaselineHistory` row is tagged from `row.reason` instead of the hardcoded
  `OVERRIDE`. Within a session, apply `DETRAIN` rows before `OVERRIDE` rows so a
  manual edit to the same muscle wins deterministically.

## Flow

```
Loading → PlanPreview ─(qualifying gap)→ [Detraining dialog over preview]
                                              │ Apply → rebuild planner with
                                              │         detrainOverrides merged
                                              │         in; weights reduced
                                              │ Skip  → unchanged preview
                                              ↓
                                          ActiveSet ⇄ Resting → Done
```

On **Apply**, the controller rebuilds the planner with the detrain map merged
into the strength overrides (the same rebuild-with-overrides pattern
`replaceExercise` already uses) and stores `detrainOverrides` on the plan for
persistence at session start.

## Error / edge handling

- No prior completed session → no prompt.
- Gap < 1 week → no prompt.
- User skips → no rows written, baselines untouched.
- Slider at 0% on Apply → treated as Skip (no rows).
- Manual in-session baseline edit to a detrained muscle → manual wins (planner
  merge precedence and replay ordering).

## Testing

- **`DetrainingModelTest`** (JVM): week flooring, 5%/week, 50% cap, sub-week → 0,
  `qualifies` threshold.
- **Controller test:** qualifying gap surfaces the prompt with the correct
  default fraction; Apply merges overrides so reduced weights reach the plan and
  `detrainOverrides` is populated; Skip leaves baselines and the plan untouched;
  no prior session → no prompt.
- **Replay / repository test:** a `DETRAIN` `BaselineOverride` row replays into a
  `BaselineHistory` tagged `DETRAIN` and lowers the baseline before that
  session's progression; manual `OVERRIDE` on the same muscle/session wins.
- **Migration test:** `MIGRATION_15_16` preserves existing rows and defaults
  `reason` to `OVERRIDE`.
```
