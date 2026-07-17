# Inspiration card on the finished-workout view

**Date**: 2026-07-17
**Status**: Approved

## Goal

On the workout-complete (`Done`) screen, show a `HighlightCard` between the
"Duration" line and the exercise list. The card states a fact about a lift or
muscle group *done in this session* and pairs it with a quip, reusing the
machinery already built for the History screen highlight. The finished-workout
card always tries to show a session fact and falls back to a bare quip only when
nothing qualifies.

## Background

The History screen already has this pattern:

- `WorkoutRepository.buildHighlightSeries()` produces per-lift and per-muscle
  `HighlightSeries` (progression-point trends).
- `HistoryHighlight.pick(series, weightUnit, nowMs, random, config)` selects a
  window-based "gain" fact (30/90-day) and pairs it with a quip. It has a 25%
  quip-only probability and a quip-only fallback when no fact qualifies.
- `ui/history/HighlightCard.kt` renders the string with a scramble animation.

The finished-workout view differs in two ways: the fact must be scoped to the
lifts/muscles performed in *this* session, and it should always try to show a
fact (no playful 25% quip-only).

## Design

### 1. Domain — scope + always-fact (`domain/history/HistoryHighlight.kt`)

- Add `exerciseId: Long?` (default `null`) to `HighlightSeries`. This lets lift
  series be matched to a session robustly by id rather than by display name.
- Add a pure filter:

  ```kotlin
  fun scopeToSession(
      series: List<HighlightSeries>,
      exerciseIds: Set<Long>,
      muscles: Set<MuscleGroup>,
  ): List<HighlightSeries>
  ```

  Keeps `LIFT` series whose `exerciseId` is in `exerciseIds`, and `MUSCLE`
  series whose `muscle` is in `muscles`.

- Reuse the existing `pick(...)` with `HighlightConfig(quipOnlyProbability = 0f)`
  so it always tries a fact while keeping the existing quip-only fallback when
  no candidate qualifies.

### 2. Repository (`domain/WorkoutRepository.kt`)

- `buildHighlightSeries()` fills `exerciseId` for `LIFT` series (it already has
  `exercise.id` in scope) and leaves it `null` for `MUSCLE` series.
- New method mirroring `buildHighlightSeries`, keeping the ViewModel thin:

  ```kotlin
  suspend fun buildSessionHighlight(
      sessionId: Long,
      weightUnit: WeightUnit,
      nowMs: Long,
      random: Random,
  ): String
  ```

  It builds the series, reads the session's sets → distinct exercise ids →
  their primary muscles, calls `scopeToSession`, and returns
  `HistoryHighlight.pick(scoped, weightUnit, nowMs, random, HighlightConfig(quipOnlyProbability = 0f))`.

### 3. ViewModel (`ui/workout/WorkoutViewModel.kt`)

- Add `_doneHighlight: MutableStateFlow<String?>` exposed as
  `doneHighlight: StateFlow<String?>`.
- In the existing `controller.state` collector, when the state becomes `Done`
  (same branch that loads `doneSummary`), compute the highlight on
  `Dispatchers.Default` seeded with `Random(sessionId)` (stable across
  recomposition). Clear it to `null` when leaving `Done`.

### 4. UI

- `ui/WorkoutSummaryContent.kt`: add an optional
  `belowDuration: (@Composable ColumnScope.() -> Unit)? = null` slot, rendered
  between the Duration `Text` and the exercise list. When `null`, layout is
  unchanged.
- `ui/workout/DoneContent.kt`: take `highlight: String?` and render the existing
  `HighlightCard` (from `ui.history`) in the `belowDuration` slot when non-null.
- `ui/workout/WorkoutScreen.kt`: collect `doneHighlight` and pass it to
  `DoneContent`.
- `ui/summary/SummaryScreen.kt`: unchanged (passes no `belowDuration`).

### 5. Tests

Extend `app/src/test/.../domain/history/HistoryHighlightTest.kt`:

- `scopeToSession` keeps only session lifts (by id) and session muscles, drops
  the rest.
- `pick` with `quipOnlyProbability = 0f` returns a fact (not a bare quip) when a
  qualifying candidate exists.

## Out of scope

- No change to the historical `SummaryScreen` highlight (it stays absent there).
- No ⋮ / "Inspire me" re-roll on the done view — the card is static per session.
- No schema migration and no backtest impact.
