# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — intended / accepted-by-design (no action needed, kept for visibility)

## Open — needs triage

- `LinkNodeHost`'s new `isTraversalGroup`/`traversalIndex = -1f` (added to place the circuit
  link-node between the two rows it links, task 4 of the 2026-09-19 todo sweep) is unverified on a
  real device — no TalkBack run was possible off-device. Needs a human to run the checklist in
  `.superpowers/sdd/2026-09-19-todo-sweep/task-4-report.md` on plan preview and the saved-workout
  editor: (1) a 3-row circuit (sibling `LinkNodeHost` groups inside one `LazyColumn` item), and
  (2) two consecutive 2-row circuits back to back (crosses a `LazyColumn` item boundary) — and
  revert per that report if either reads wrong.
- `RestingContent`'s staged-action branch (`state.staged != null` — swap / end-exercise / adjust-
  weight / warmup-done) titles its card "Up next" / "Warm up" / "First set" for the commit target
  but never shows a circuit round, even when that commit target is a circuit member. Same class of
  omission as the "Next up" round fix in this pass, but a different code path (keyed off
  `staged.commitTarget`, not `WorkoutSequence.next`); out of scope for this task.
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
- Shared row polish (`ui/components/CircuitChrome.kt`), deferred from the unified-row review:
  - While a circuit member on Today's workout shows its swipe `ExerciseActionRow`, its rail segment
    disappears and its link node stays floating; the node also does not move with a row mid-swipe.
  - TalkBack reads the node after its own row; the wording now says "the row above", but a
    `traversalIndex` so it reads between the two rows would be clearer. Not tested with TalkBack.
- `EntryRow` (editor) and `ExercisePreviewRow` (Today's workout) duplicate the row body: name text,
  timed text, the trailing stepper/"Bodyweight"/nothing `when`, `SuggestionNote`, and the identical
  `linkedAbove = i != block.start` / toggle-lambda construction. Extract a shared body/weight slot and a
  `linkAbove(block, i, onLink, onUnlink)` helper before the two screens drift.
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
