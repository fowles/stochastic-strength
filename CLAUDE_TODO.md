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
- Shared row polish (`ui/components/CircuitChrome.kt`), deferred from the unified-row review:
  - `ValueStepper` −/+ are 32dp `IconButton`s and the reset target is a bare `Text.clickable`
    (~28×20dp), both under the 48dp minimum; −/+ are never disabled at the bounds (reps 1/50, weight
    floor), so they are silent no-ops there.
  - While a circuit member on Today's workout shows its swipe `ExerciseActionRow`, its rail segment
    disappears and its link node stays floating; the node also does not move with a row mid-swipe.
  - The link node sits ~2dp from the drag handle on a 64dp row, and the hollow (unlinked) node is
    `outlineVariant` on `surface`, which can be near-invisible under some dynamic-colour palettes.
    Both need an on-device look (drag feel, contrast).
  - TalkBack reads the node after its own row; the wording now says "the row above", but a
    `traversalIndex` so it reads between the two rows would be clearer. Not tested with TalkBack.
  - `LinkState` is a data class holding a lambda, so it never compares equal and `LinkNodeHost` never
    skips recomposition.
- `EntryRow` (editor) and `ExercisePreviewRow` (Today's workout) duplicate the row body: name text,
  timed text, the trailing stepper/"Bodyweight"/nothing `when`, `SuggestionNote`, and the identical
  `LinkState(linked = i != block.start, …)` construction. Extract a shared body/weight slot and a
  `linkAbove(block, i, onLink, onUnlink)` helper before the two screens drift.
- Suggestions are computed in composition for every row on every recomposition
  (`suggester.weight(...)` in the editor, `suggestWeight(planned)` on Today's workout, including
  unpinned rows where the note can never show). Cheap arithmetic today; `remember` it by
  exercise/reps. `RowSuggester` is also declared at the top of `WorkoutRepository.kt` and wants its
  own file.
- Redundant planner rebuilds in `WorkoutSessionController`: `applySavedWorkout` still rebuilds on a
  non-append load (the override state it existed to discard is gone; `basePlan` is now just
  `current.plan`), and `persistSwap` rebuilds for `SKIP_TODAY`, which changes nothing the planner reads.
- `SavedWorkoutEditViewModel` starts `weightUnit` at KG and fills it from the profile
  asynchronously, so for an lbs user a pinned weight renders (and, if tapped in that window, steps)
  on the kg grid for the length of one DAO read.
- Test gaps from the unified-row work: no test that pins survive `replaceExercise`, the count
  slider's trim/restock, or `onLocationRefreshed` (verified by reading only); `rowPlace` has no unit
  test; `rowSuggester()`'s profile rep range is untested (only its weight unit is); the link node
  content descriptions are asserted nowhere. `WorkoutSessionControllerTest` also compiles with
  pre-existing redundant-`!!` warnings.
- Not exercised on the emulator during the unified-row pass: block drag with the new handle/node
  layout, swipe-to-reject and swipe-to-remove inside a circuit, and steppers with long exercise names
  at 360dp.

- No UI test covers `HistoryScreen` delete (the 2026-09-19 stale-`rows` key-lambda crash was verified
  on the emulator only); the screen needs a harness that doesn't use the app's real database.
