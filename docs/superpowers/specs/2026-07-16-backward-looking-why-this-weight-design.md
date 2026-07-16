# Backward-looking "Why this weight" (and cross-tuning) with a synthetic next-prediction point

Date: 2026-07-16
Screen: `ui/debug/ExerciseCoefficientDetailScreen` ("Estimated 1RM over time" debug chart)

## Problem

The debug exercise-detail screen has three stacked sections under one selectable chart:

1. **Estimated 1RM over time** — own/siblings/merged estimate lines + own/sibling observation dots.
2. **Cross-tuning** — own/siblings/merged numeric header + per-sibling agreement/contribution bars.
3. **Why this weight** — the line-by-line `PrescriptionTrace` for the exercise.

Tapping a session dot pins a tooltip and time-travels the **Cross-tuning** section to that session, but the **Why this weight** section always renders a single `state.trace` fetched once via `getPrescriptionTrace(exerciseId)` — the *current/next* prescription. It ignores the selection.

We want the "Why this weight" section to follow the actively selected point, and — since the screen is best read as a history — to reframe **every** per-session point to a consistent backward-looking meaning, plus add a synthetic "predicted today" point.

## Semantics: pre-fold, "the decision entering the session"

Prescriptions are computed *before* a session is performed. The weight used **in** `Sₙ` came from the belief/facts state that existed **entering** `Sₙ` — i.e. after `Sₙ₋₁`'s fold and idle-aging, *before* `Sₙ`'s own sets fold in. `ReplayEngine.runCore` already exposes this exact moment via its `beforeSession(beliefs, asOf)` hook (runs after this session's override rows, before the fold).

Every per-session **selectable** point is redefined to that pre-fold state. When `Sₙ` is selected, all three sections describe the decision that produced `Sₙ`:

- **Why this weight**: the trace for the weight actually used in `Sₙ`, computed at `Sₙ`'s rep target.
- **Cross-tuning** bars + own/siblings/merged **numeric header**: pre-fold pooled state entering `Sₙ`.
- **Estimate lines** (own/siblings/merged/band): sampled from the pre-fold state.

The **observation dots** (own + sibling per-set implied 1RM — what was actually lifted) stay pinned to `Sₙ`'s timestamp. A selected point therefore reads as **prediction (pre-fold lines) vs. outcome (dots)**. The first session `S₁` shows its cold seed-prior decision (the belief was seeded, not yet folded).

This is largely a **re-labeling** of the sequence of states already computed: the states (cold prior, post-`S₁`, …, post-`Sₙ`) shift by one so each feeds the *next* point, and the final post-`Sₙ` state becomes the synthetic "today" point (below).

## Synthetic "next prediction" point at today

Append exactly one extra selectable point at today's date representing the current **forward-looking** prescription: the live post-final-fold belief + today's policy facts — identical in content to what `getPrescriptionTrace` returns today.

- Plotted as a visually distinct marker (hollow / "predicted") on the merged-estimate line at today's x.
- Selecting it shows today's trace + today's cross-tuning header/bars.
- **Default selection on open** = this synthetic point (most actionable; matches today's behavior).
- Edge: if the most recent session was completed *today*, the synthetic point and that session share an epoch-day key. The synthetic "today" point wins that key (its post-fold state is what a hypothetical next session would use).

## Where the traces come from

Extend the single replay pass in `ExerciseProgressionSeriesBuilder.build` (which already emits frames):

- Maintain a rolling window of prior-session completed sets across the muscle's exercises as the
  replay proceeds in `(endTime, id)` order.
- For each session `Sₙ` whose muscle is touched, build a `PrescriptionTrace` via the **unchanged**
  `PrescriptionTraceBuilder.build`, passing:
  - `beliefs` = the **pre-fold** snapshot (captured at the `beforeSession` moment for `Sₙ`),
  - `facts` = `PolicyFacts.build` over the rolling window of sets **before** `Sₙ` (the sets known
    when `Sₙ`'s weight was chosen), within `PrescriptionPolicy.FACTS_WINDOW_MS`,
  - `sessionReps` = `Sₙ`'s own target reps (read from `Sₙ`'s sets) rather than the current
    last-set heuristic,
  - `capSessionSets`, `seedCoef`, `muscleExerciseIds`, `now = asOf(Sₙ)`, `config`, `engine`.
- After the loop, build the synthetic **today** trace the same way from the live post-final-fold
  beliefs and today's facts window.

No pipeline math is duplicated — the trace is still assembled only by `PrescriptionTraceBuilder`,
reading `BeliefPooling`/`PrescriptionPolicy` outputs. `WorkoutRepository.getPrescriptionTrace` has a
single caller (this ViewModel) and no test references, so it is **removed**: the live prescription
explanation is now just the trailing synthetic frame's trace, one code path.

### Interface sketch

- `ProgressionFrame` gains `val trace: PrescriptionTrace?`.
- `ExerciseProgressionData.frames` includes the trailing synthetic today frame.
- `ExerciseProgressionSeriesBuilder` needs the extra inputs the trace requires (per-exercise
  muscle map, seed coefficients — already on `ReplaySnapshot`; weight unit — passed in). The
  rolling facts window and pre-fold capture live inside `build` / a small helper, pure and testable.

## UI wiring (`ExerciseCoefficientDetailScreen` + ViewModel)

- `FrameView` gains `val trace: PrescriptionTrace?`; `buildFrameViews` carries it through (keyed by
  epoch day, exactly like the rest of the frame).
- The screen's existing `selectedEpochDay ?: state.defaultEpochDay` now also selects the trace: the
  **Why this weight** section renders `crossTuningFrame.trace` instead of `state.trace`. One
  selection variable drives all three sections.
- `defaultEpochDay` becomes the synthetic today point's epoch day.
- `state.trace` (the standalone field) is removed; the concurrent `getPrescriptionTrace` fetch in
  the ViewModel is removed (the trace now rides on the frames from the single replay).
- The chart marks the synthetic point distinctly and keeps it selectable (add it to the plotted
  series with a "predicted" style; verify on-device that the marker + selection read correctly —
  see [[reference_dynamic_color_charts]]).

## Scope boundary

- Contained to the debug `ExerciseCoefficientDetailScreen`. The user-facing exercise-detail chart
  only borrows `getExerciseProgressionData` for `sharedProgressionYRange`; the pre-fold shift
  changes the band extrema by at most one session, so the shared Y-range is effectively unchanged
  and the user-facing chart's plotted content is untouched.
- Display-only. The `BeliefScoreTest` / `BeliefPolicyBacktestTest` gates are **not** touched.

## Testing

- `ExerciseProgressionSeriesBuilderTest`: re-baseline for pre-fold lines/frames + the trailing
  synthetic frame; add a case asserting the frame for `Sₙ` reflects the state entering `Sₙ` (equals
  the previous session's post-fold state), and that the trailing frame equals the live prescription.
- `ExerciseCoefficientDetailViewModelTest`: re-baseline `framesByEpochDay`/`defaultEpochDay`; assert
  each `FrameView` carries its trace and the default epoch day is the synthetic today point.
- New unit coverage for the per-session `PolicyFacts` rolling window (facts entering `Sₙ` exclude
  `Sₙ`'s own sets) and pre-fold belief capture.
- Full JVM suite green; instrumented suite green. Re-baselining these display tests is a deliberate,
  human-reviewed change (per [[project_estimator_rebuild]] gate discipline — but the fitness gate
  itself is untouched here).

## Non-goals

- No change to the prescription pipeline, belief fold, pooling, or policy math.
- No change to the user-facing exercise-detail chart's plotted content.
- No new persisted state; everything is recomputed on-demand in the existing replay.
