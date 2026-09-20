# CLAUDE TODO

Bugs / cleanup ideas noticed out of scope. Triage and address when convenient.

## Open — intended / accepted-by-design (no action needed, kept for visibility)

## Open — needs triage

- `WorkoutSessionControllerTest` (the whole class) intermittently fails
  `hurtMidCircuit_dropsThatMemberFromLaterRounds` with `IllegalStateException: attempt to
  re-open an already-closed object: SQLiteDatabase: :memory:` when the full class is run
  repeatedly back to back (~1 in 6-12 runs observed 2026-09-20, task-6 review-fix pass). The
  failing test itself never flakes in isolation (8/8 clean solo runs) and doesn't touch a
  gated/fresh fixture db, so this is cross-test contention/teardown timing under load, not a
  bug in that test's own logic. Structural root (per re-review, 2026-09-20): `previewFixture`
  (`WorkoutSessionControllerTest.kt:195-242`) builds its own in-memory DB, shares the
  class-level `scope`, and closes that DB inline while its controller's coroutines may still be
  in flight — `tearDown` (:90-96) only cancels `scope` *after* each test method returns. Any
  test that throws before reaching its own `f.db.close()` leaks an in-memory DB plus its
  pending invalidation-tracker work into the shared executor pool, which can surface later as
  "attempt to re-open an already-closed object" attributed to whatever test happens to be
  running next. Suggested fix (not implemented — out of scope for task 6): track every fixture
  DB created during a test in a list, and close them all in `tearDown`, after
  `cancelAndJoin()`, instead of inline in each test body. Reproduces identically on the
  pre-review-fix commit (`111fb4a8`, 2/6 runs) so it predates the task-6 review-fix pass, but
  that pass's own exposure is **not established either way**: the measured-ordinal conversion
  makes each converted test run the controller method twice (a dry run plus the gated run —
  roughly double the writes/invalidations), and the pass added two more fixture DBs (for
  `replaceExercise` and the drop-path test), so the flake rate plausibly went up. A 2/6 vs.
  ~1/6-12 sample is too small to distinguish "unchanged" from "worse" — do not cite this as
  "pre-existing and unaffected" without a larger controlled comparison.
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
- `WorkoutSessionController.startFirstExercise` (:134-147) captures `plan` before its
  `workoutSessionDao().insert` suspend and builds the first `ActiveSet` from that stale snapshot at
  :144 — the whole session then runs on it, so a preview edit landing during the insert is lost for
  the rest of the workout. `applyPreviewDelta` does not fit (this is a deliberate exit *from*
  `PlanPreview`), but re-reading the live preview's plan after the insert would. Narrow window and
  pre-existing; deliberately left unchanged by the 2026-09-19 whole-branch fix pass.
- `HistoryScreen`'s `onExerciseTap` parameter is dead — `AppNavigation` passes a real
  `exercise/{id}` navigation lambda, but nothing in the screen body ever calls it. Either wire the
  session rows' exercise names to it or drop the parameter and the call-site lambda.
