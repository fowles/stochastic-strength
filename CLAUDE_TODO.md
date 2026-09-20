# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — intended / accepted-by-design (no action needed, kept for visibility)

## Open — needs triage

- `WorkoutRepository.saveSessionAsWorkout`: after a swap inside a circuit, both the abandoned
  original and its replacement are saved as members, each at full rounds (`equalizeRounds`). That
  follows the spec ("rounds = max over members") but is probably not what the user wants.
- `WorkoutSessionController.onLocationRefreshed` sets state after the `withRowFlags` suspend, so a
  plan edit made during that suspend is overwritten. Pre-existing pattern, but with per-row sets and
  circuits more kinds of edit can now be lost.
- Plan-preview edits can be lost across a suspend: `applySavedWorkout`, `addExercise` and
  `onLocationRefreshed` read `current`, suspend (`buildPlanner` / `withRowFlags`), then
  `setState(current.copy(…))`; a pin or structure edit tapped during that window is overwritten.
  Pre-existing pattern (see the existing `onLocationRefreshed` entry); fix by re-reading state after
  the suspend and re-applying only the delta.
- Rest screen "Next up" card omits the round for a circuit member (the notification includes it),
  and after a too-hard weight reduction inside a circuit the card says "Reduced weight: A" although
  the next set is B's.

- Instrumented-suite flake: a full `connectedAndroidTest` run can abort with `attempt to re-open an already-closed object: SQLiteDatabase`, thrown by a `finishWorkout` coroutine leaked from an earlier `WorkoutSessionControllerTest` test after its fixture closed the DB, and blamed on whichever test is running. Re-run passes. Fix in the fixture teardown (cancel the controller scope before `db.close()`).
- A pinned weight on a user-created exercise is dropped: `WorkoutPlanner.isLoaded` resolves coefficients by exercise *name*, so a custom lift not in `ExerciseCoefficients.byName` counts as unloadable. Also the editor cannot create a weight pin for a loadable lift that has no estimate yet (its stepper needs a suggestion to start from).
- Editor `hasWeight` gate (`SavedWorkoutEditScreen`) trusts a stored `weight`: a hand-edited backup with a weight on a bodyweight row, or `0`/sub-floor values, shows a stepper the session then ignores or re-clamps.
