# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — intended / accepted-by-design (no action needed, kept for visibility)

- **Skippability is checked by reading the Compose compiler report, not by a runtime test**
  (2026-09-20). `app/build/compose_reports/app-composables.txt` lists every composable's
  parameters; one with no `stable` prefix is what stops it skipping. That is a compile-time
  property, so a recomposition-counting harness would cost real work to answer a question the
  build already answers — and when the question was first asked, the answer was "it doesn't skip".
  `ExercisePreviewRow` and `EntryRow` now report every parameter `stable`.

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
