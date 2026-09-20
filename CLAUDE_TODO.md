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
- A pinned weight on a user-created exercise is dropped: `WorkoutPlanner.isLoadable` resolves
  coefficients by exercise *name*, so a custom lift not in `ExerciseCoefficients.byName` counts as
  unloadable.
- Shared row polish (`ui/components/CircuitChrome.kt`), deferred from the unified-row review:
  - While a circuit member on Today's workout shows its swipe `ExerciseActionRow`, its rail segment
    disappears and its link node stays floating; the node also does not move with a row mid-swipe.
  - The link node sits ~2dp from the drag handle on a 64dp row, and the hollow (unlinked) node is
    `outlineVariant` on `surface`, which can be near-invisible under some dynamic-colour palettes.
    Both need an on-device look (drag feel, contrast).
  - TalkBack reads the node after its own row; the wording now says "the row above", but a
    `traversalIndex` so it reads between the two rows would be clearer. Not tested with TalkBack.
- `EntryRow` (editor) and `ExercisePreviewRow` (Today's workout) duplicate the row body: name text,
  timed text, the trailing stepper/"Bodyweight"/nothing `when`, `SuggestionNote`, and the identical
  `linkedAbove = i != block.start` / toggle-lambda construction. Extract a shared body/weight slot and a
  `linkAbove(block, i, onLink, onUnlink)` helper before the two screens drift.
- The editor computes `suggester.weight(...)` in composition for every row on every recomposition
  (it feeds the stepper's own value there, so it can't just be skipped the way Today's workout now
  skips unpinned rows). Cheap arithmetic today; `remember` it by exercise/reps — keyed so a planner
  rebuild can't leave a stale number on screen.
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
