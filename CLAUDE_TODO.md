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
- `WorkoutRepository.saveSessionAsWorkout`: after a swap inside a circuit, both the abandoned
  original and its replacement are saved as members, each at full rounds (`equalizeRounds`). That
  follows the spec ("rounds = max over members") but is probably not what the user wants.
- `WorkoutSessionController.addExercise` and `.applySavedWorkout` capture `p = planner` before
  their suspends; a concurrent `onLocationRefreshed` can swap `planner` to a fresh instance in that
  window, so the add/load prices its row(s) against a superseded planner instead of the latest one.
  Pre-existing (predates and is unchanged by the 2026-09-19 todo-sweep task-6 fix, which addressed
  the *state*-merge race but not this planner-read race); needs its own design pass (e.g. re-reading
  `planner` right before pricing, or making the planner swap itself suspend-safe).
- Not exercised on the emulator during the unified-row pass: block drag with the new handle/node
  layout, swipe-to-reject and swipe-to-remove inside a circuit, and steppers with long exercise names
  at 360dp.
- The circuit rail / link-node swipe fix (2026-09-19 todo sweep, task 3 — `CircuitRailGutter`,
  `LinkNodeHost.swipeOffsetPx`, and the action-row gutter) is **device-unverified**: it was written
  and reviewed off-device. Needs an emulator pass on plan preview covering (1) the rail staying
  continuous through a circuit member that is showing its swipe `ExerciseActionRow`, (2) the link
  node tracking a row's live swipe offset and snapping back to 0 once the action row takes over,
  and (3) the action row's own layout now that the gutter is drawn only for circuit members (a solo
  row's action row keeps the full width; a circuit member's is inset by the 36dp gutter).
- `ExercisePreviewRow` still recomposes on every pass, so the `remember(block, i) { linkAbove(...) }`
  memoization added in the 2026-09-19 sweep buys subtree skipping (`LinkNodeHost`) rather than the
  whole-row skipping it was aimed at. The remaining never-equal parameter is
  `dragHandleModifier = Modifier.draggableHandle()` (reorderable 2.4.0), which is built with an
  unkeyed `Modifier.composed { … }` and so has no `equals`. Fixing it means keying the composed
  modifier or hoisting the handle out of the row's parameter list.
