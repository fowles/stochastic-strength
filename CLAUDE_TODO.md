# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — intended / accepted-by-design (no action needed, kept for visibility)

- **Uneven circuits show their largest round count** (decided 2026-09-20). `saveSessionAsWorkout`
  keeps each member at the rounds it actually got, so a saved circuit can be uneven (a swapped-away
  member at 1, its replacement at 2, an untouched member at 3). The rounds chip shows the block
  maximum — no range, no per-member annotation: a circuit advertises its longest member. Setting the
  chip (`CircuitEdits.setRounds`) levels the whole block, which is the coherent reading of editing
  the one number on display. Still to be seen on a device — Check 7 of
  `docs/verification/2026-09-20-device-check.md`.

## Open — needs triage

- `LinkNodeHost`'s new `isTraversalGroup`/`traversalIndex = -1f` (added to place the circuit
  link-node between the two rows it links, task 4 of the 2026-09-19 todo sweep) is unverified on a
  real device — no TalkBack run was possible off-device. Needs a human to run the checklist in
  `.superpowers/sdd/2026-09-19-todo-sweep/task-4-report.md` on plan preview and the saved-workout
  editor: (1) a 3-row circuit (sibling `LinkNodeHost` groups inside one `LazyColumn` item), and
  (2) two consecutive 2-row circuits back to back (crosses a `LazyColumn` item boundary) — and
  revert per that report if either reads wrong.
- `WorkoutSessionController.addExercise` / `.applySavedWorkout` now re-read `planner` inside their
  `applyPreviewDelta` transform (2026-09-20), so the planner-read race is closed — but the change is
  **not covered by a test**. Gating it would need the planner swap to happen while `addExercise`'s
  own suspend is blocked, and `onLocationRefreshed`'s DB calls queue behind that block on the same
  single-threaded gated executor, so the swap can't be made to land in the window. Covering it needs
  a seam that swaps `planner` without touching the database.
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
- The drag-handle memoization (2026-09-20) that removed `ExercisePreviewRow`'s last never-equal
  parameter is **not measured**: no recomposition-count harness exists, so "the row now skips" is
  reasoning about parameter equality, not an observation. It is also **device-unverified** — drag
  and drop on plan preview and in the saved-workout editor should be exercised on the emulator to
  confirm the remembered `Modifier.draggableHandle()` still starts a drag. (The handle is drawn on
  the block head only — one per circuit, not one per row.)
