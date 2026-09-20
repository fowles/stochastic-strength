# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

Only open work belongs here. Something decided, accepted or finished is not a todo — it goes in
`CLAUDE.md` if the next reader needs it, and nowhere if they don't.

From the 2026-09-20 whole-project review. Each needs a decision or is bigger than a drive-by.

## Data
- **Replay is sessions × sets.** `ReplayEngine`, `ExerciseProgressionSeriesBuilder` and
  `HistoryViewModel` call `getSetsForSession` per session, and `workout_sets` has no index on
  `sessionId`/`exerciseId`. Fetch once and group, or add the indices (needs a v22 migration).
- **An abandoned session is orphaned.** Process death mid-workout leaves a `workout_sessions` row
  with `endTime` NULL whose sets never reach replay; nothing resumes or cleans it. Resuming needs
  the session id in a `SavedStateHandle`.

## Workout session
- **`saveSessionAsWorkout` reorders uneven circuits.** It orders by first logged set, but
  `WorkoutSequence.next` starts an uneven circuit with the member that has most sets left:
  saved `[A(2), B(3)]` runs B first and saves back as `[B, A]`.
- **Count slider can cut a circuit.** `adjustExerciseCount` trims with `take(targetCount)`, which
  can drop explicit/pinned rows and split a block (`normalize` then dissolves the remainder).
- **Undoing a HURT set leaves `ExerciseHurtState` set** (display-only; policy reads the set log).
- **`ExercisePacingEstimator` tests `circuitId != null`** rather than block membership, so a lone
  tagged row (legal mid-session) loses its pacing samples.
- **Foreground service type is `dataSync`**, capped at ~6 h/day on target SDK 36, with no
  `onTimeout`. An abandoned workout can hit the cap. Consider `health`, or stop on timeout.

## UI
- **Editor state is lost on process death** (`SavedWorkoutEditViewModel` has no
  `SavedStateHandle`), while its two dialog flags are `rememberSaveable` and do survive.
- **Forward navigation has no resumed guard.** A double-tapped card or FAB pushes the route twice
  (`workout-edit/0` twice = two editors). Only pops go through `popBackStackIfResumed`.
- **The editor and plan preview still duplicate the block list** (reorderable state, `items` by
  block, elevation, link-node wiring, swipe background). Extract a `CircuitBlockList` into
  `CircuitChrome.kt`. The editor also omits `swipeOffsetPx`, so its link node stays put while a
  swiped row slides away.
- **Swipe and drag have no accessible alternative**: add `customActions` (Remove / Move up /
  Move down) wired to `CircuitEdits`.
- **`summaryBlocks` re-derives block grouping** instead of reading `CircuitStructure.blocks`.
- **The exercise-detail chart re-derives prescription math**: `buildPrescribedPoints` plots
  baseline × coefficient (the old model) and scales sibling dots by seed coefficients. Plot
  `series.merged` / `series.siblingObservations` from the pipeline, as the debug chart does.
- **Strava description doesn't mark where a circuit ends**: "Circuit ×3 / Curl / Kickback / Squat"
  reads as three members when Squat is solo. Needs a wording decision (indent members?).
- Screens use `collectAsState()`; `collectAsStateWithLifecycle` would stop DB observation in the
  background.

## Repo
- Machine-local IDE state is tracked: `.idea/deploymentTargetSelector.xml`, `deviceManager.xml`,
  `androidTestResultsUserPreferences.xml`, `studiobot.xml`.
