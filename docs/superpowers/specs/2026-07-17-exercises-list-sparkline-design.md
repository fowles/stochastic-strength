# Exercises list sparklines

**Date:** 2026-07-17
**Status:** Approved, ready for planning

## Goal

Add a small sparkline to the right side of every row in the exercises list
(`ExercisesScreen`) showing that lift's progress over the last several months, so
the user can glance down the list and see which lifts are moving.

## What it shows

- **Signal:** the lift's **merged estimated-1RM** trend — the pooled belief 1RM
  per session, the same "merged" line the exercise detail chart and the History
  highlight already draw. One value per session the lift's muscle was trained.
- **Window:** the **last 6 months** (`now − ~182 days`).
- **Sparse rows:** a row with fewer than 2 in-window points renders **nothing**
  (blank right side). New/dormant lifts stay calm; nothing is invented.
- **Scale:** each sparkline self-normalizes to its own min/max over its window
  (independent y-scale per row — standard sparkline behavior).
- **Style:** a thin trend line with a faint vertical gradient fill underneath, in
  the theme **primary** color. Neutral — no red/green trend coloring.

## Data flow

The data backbone already exists — no belief/policy/replay changes.

- `ExerciseProgressionSeriesBuilder.buildAllMergedSeries(db)` already computes
  **every** exercise's merged (pooled effective) 1RM trend in a **single** replay
  (built for the History highlight; ~fast on cold open, not a per-lift replay). It
  returns `Map<Long, List<ProgressionPoint>>`, session-ordered.
- New `WorkoutRepository` method, e.g. `buildExerciseSparklines(windowMs: Long)`:
  1. call `buildAllMergedSeries(db)`,
  2. filter each series to points with `timestampMs >= now − windowMs`,
  3. drop series with `< 2` points,
  4. return `Map<Long, List<Float>>` — bare values in session order (a sparkline
     needs shape, not timestamps).
- `ExercisesViewModel` computes this **once on init** (mirrors the History
  highlight; beliefs only change after a workout finishes, and this screen is
  entered fresh from home) and exposes it as
  `StateFlow<Map<Long, List<Float>>>`. The existing `exercises` flow is untouched.

## Rendering

- New lightweight composable `Sparkline` in `ui/components/`, drawn with Compose
  `Canvas`:
  - a `Path` stroke for the line,
  - a filled `Path` (line down to the baseline) with a vertical gradient brush.
  - Deliberately **not** Vico — a Vico chart is too heavy to instantiate per row
    in a `LazyColumn`. Canvas is cheap and sufficient for a static sparkline.
- Input: `List<Float>` (already windowed). Renders nothing for `< 2` points.

## Row layout

`ExerciseRow` becomes a three-part row:

```
[ name + equipment   — weight 1f ] · [ sparkline ~96dp × ~28dp ] · [ badges ]
```

- The `name + equipment` column keeps `weight(1f)`.
- The sparkline is a fixed width (~96dp), height ~28dp, vertically centered.
- Disliked / Hurt badges are occasional and stay to the **right** of the
  sparkline, as today.
- When a row has no sparkline (sparse), that space is simply empty — the name
  column keeps its width; nothing shifts.

## Testing

- **Unit tests** for the pure logic: the 6-month window filter, the `< 2`-point
  drop, and the value-normalization mapping used by the sparkline. Keep the pure
  transform (points → windowed floats, and floats → normalized offsets)
  extractable so it can be tested without Android.
- The `Canvas` drawing and the row layout are verified **on-device**.
- No belief/policy math is touched, so **no backtest impact**.

## Out of scope (YAGNI)

- No trend coloring (red/green), no tap/selection on the sparkline, no axis or
  labels, no live re-computation while the screen is open.
