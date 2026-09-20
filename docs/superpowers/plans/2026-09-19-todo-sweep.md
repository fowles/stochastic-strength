# TODO sweep — 2026-09-19 overnight pass

Work the open items in `CLAUDE_TODO.md` from easiest to hardest. Two items are
deliberately **not** in this plan:

- `saveSessionAsWorkout` mid-circuit-swap membership — needs a product decision
  (which row survives, what rounds it gets) that only the user can make.
- The emulator pass on the unified-row work — needs a human at the device.

Both stay in `CLAUDE_TODO.md` untouched.

## Global Constraints

- Kotlin / Jetpack Compose / Material3, single module `app/`.
- Package root `io.github.fowles.stochastic_strength`.
- No DI framework: `StochasticStrengthApp` owns the singletons; ViewModels read
  `application as StochasticStrengthApp`.
- Unit tests: `./gradlew :app:testDebugUnitTest`. Compose UI tests live in
  `src/androidTest/` and need a device (`./gradlew :app:connectedAndroidTest`).
- Run the narrowest test target after each change; the full unit suite before
  reporting DONE.
- Commit with jj (`jj commit -m ...`) at each checkpoint. Conventional-commit
  subjects, matching the repo's existing style.
- Do not change the belief/progression math, `BeliefConfig`, or any backtest
  baseline. If a change would move `BeliefScoreTest`, stop and report.
- Do not bump the Room schema version. None of these tasks need a migration.
- New TODOs discovered along the way go into `CLAUDE_TODO.md` under
  "Open — needs triage", not fixed inline.
- No subagent dispatches subagents.

## Task 1 — Rest screen "Next up" card: circuit round and reduced-weight label

File: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/RestingContent.kt`
(plus whatever feeds it in `WorkoutSessionController` / `WorkoutNotificationState`).

Two defects, both display-only:

1. The "Next up" card omits the circuit round for a circuit member. The workout
   notification already renders the round — find how the notification label
   computes it (`WorkoutNotificationState` / `WorkoutSequence.next`) and reuse
   that same derivation rather than recomputing it a second way. Solo exercises
   must keep their current round-free wording.
2. After a too-hard weight reduction inside a circuit, the card reads
   "Reduced weight: A" where A is the exercise that was just reduced, but the
   *next* set is B's. The card is about what comes next, so the reduction line
   must either name the upcoming exercise's weight or be suppressed when the
   reduction does not apply to the upcoming set. Pick whichever reads correctly
   for both the solo case (reduction does apply to the next set — it is the same
   exercise) and the circuit case, and say in the report which you picked.

Add unit tests for the label derivation if it is extractable to a pure function;
prefer extracting it over testing the composable.

## Task 2 — Extract the shared row body from `EntryRow` / `ExercisePreviewRow`

Files:
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/components/CircuitChrome.kt`
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/savedworkouts/SavedWorkoutEditScreen.kt` (`EntryRow`)
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/PlanPreviewContent.kt` (`ExercisePreviewRow`)

The two rows duplicate: the name text, the timed text, the trailing
stepper / "Bodyweight" / nothing `when`, `SuggestionNote`, and an identical
`linkedAbove = i != block.start` plus toggle-lambda construction.

Deliver, in `CircuitChrome.kt` beside the existing shared pieces:
- a shared row body/weight slot composable that both screens call, and
- a `linkAbove(block, i, onLink, onUnlink)` helper that returns the link-node
  wiring both screens build by hand today.

Pure refactor: **no visual or behavioural change**. Pinned vs auto weight, the
live `SuggestionNote`, the bodyweight case and the timed-exercise case must all
render exactly as before on both screens. Build both screens and run the full
unit suite.

## Task 3 — Rail/node glitch while a circuit member is swiped

File: `app/src/main/java/io/github/fowles/stochastic_strength/ui/components/CircuitChrome.kt`
(and the Today's-workout call site in `PlanPreviewContent.kt`).

While a circuit member on Today's workout shows its swipe `ExerciseActionRow`:
- its rail segment disappears, and
- its link node stays floating in place instead of moving with the row.

Make the rail segment and the link node track the swiped row. The link node is
owned by the *later* row of a linked pair (deliberately — no zIndex hacks), so
the node has to follow the swipe offset of the row it visually attaches to.
Keep that ownership rule; do not reintroduce zIndex.

Behaviour to preserve: the swipe gesture itself, the action row's contents, and
what happens on swipe-away.

## Task 4 — TalkBack traversal order for link nodes

File: `app/src/main/java/io/github/fowles/stochastic_strength/ui/components/CircuitChrome.kt`

The link node is owned by the later row, so TalkBack reads the node *after* its
own row. The wording currently compensates by saying "the row above". Add a
`traversalIndex` (or equivalent semantics grouping) so the node is read
*between* the two rows it links, and simplify the content description now that
position carries the meaning.

Note in the report that this is **not verifiable without a TalkBack run on a
device** — state exactly what a human should check. Keep the change small and
reversible.

## Task 5 — Fill the unit-test gaps from the unified-row work

Add the missing tests. Each is independent:

1. Pins survive `replaceExercise` (`WorkoutSessionController`).
2. The exercise-count slider's trim/restock behaviour (the count is a *minimum*;
   restock happens on swipe-away only when the plan would fall below
   `targetCount`).
3. `onLocationRefreshed` (currently verified by reading only). Test the current
   behaviour; Task 6 changes it, so keep the assertions about what the method
   is *supposed* to do.
4. `rowPlace` — no unit test at all.
5. `WorkoutRepository.rowSuggester()`'s profile rep range (only its weight unit
   is covered today).
6. The link-node content descriptions (asserted nowhere). A Robolectric-free
   unit assertion on the description-building function is preferred over an
   androidTest if the strings are derivable outside a composable; otherwise put
   it in `src/androidTest/`.

Also clean up the pre-existing redundant-`!!` warnings in
`WorkoutSessionControllerTest`.

Tests must fail against a deliberately broken implementation — verify each new
test actually catches the behaviour it claims to (state in the report how you
checked).

## Task 6 — Plan-preview edits lost across a suspend

File: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`

`applySavedWorkout`, `addExercise` and `onLocationRefreshed` each read `current`,
suspend (`buildPlanner` / `withRowFlags`), then `setState(current.copy(…))` — so
a pin, a structure edit, or any other plan edit made during that window is
silently overwritten.

Fix: after the suspend, re-read the live state and re-apply only the delta the
method actually produces, rather than writing back the stale snapshot. Define
the delta per method:
- `onLocationRefreshed` — only the location-derived row flags.
- `addExercise` — only the appended row(s).
- `applySavedWorkout` — this one replaces the plan wholesale, so the delta is
  the whole exercise list; the state to preserve is everything *outside* the
  exercise list.

Constraints:
- If the live state is no longer `PlanPreview` when the suspend returns, drop
  the update rather than forcing the state back.
- Add unit tests that interleave an edit with the suspend and assert the edit
  survives. Use the existing test seams in `WorkoutSessionControllerTest`.
- Keep the three call sites consistent — if a shared helper falls out, extract
  it; do not write the same re-read-and-merge three times.

## Task 7 — A test seam for `HistoryScreen`, and a delete UI test

Files: `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/` and
its ViewModel.

No UI test covers `HistoryScreen` delete — the 2026-09-19 stale-`rows`
key-lambda crash was caught on the emulator only. The blocker is that the screen
reaches the app's real Room database through `StochasticStrengthApp`, and there
is no DI framework.

Deliver:
1. A seam that lets a test drive `HistoryScreen` without the real database.
   There is no DI framework and the user does not want one — prefer making the
   screen composable take its state and callbacks as parameters (a stateless
   `HistoryScreenContent` the stateful `HistoryScreen` wraps), which is the
   pattern the rest of this codebase already uses (`PlanPreviewContent`,
   `RestingContent`, `DoneContent`). Only reach for a repository interface if
   the state hoisting genuinely cannot work.
2. A test that exercises delete against that seam and **fails** if the stale
   `rows` key-lambda bug is reintroduced. Verify it fails by reverting the fix
   locally, running the test, and restoring — report the failing output.

Put the test wherever it can actually run: if it needs Compose UI testing, it
goes in `src/androidTest/` and you must run it with
`./gradlew :app:connectedAndroidTest` (an emulator is typically running). If the
crash is reproducible against the state-holder alone, a JVM unit test is better.
