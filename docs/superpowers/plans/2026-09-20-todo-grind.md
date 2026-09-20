# TODO grind — 2026-09-20

Source: every open item in `CLAUDE_TODO.md` (from the 2026-09-20 whole-project review). No separate
spec; the TODO entry plus this plan's ruling is the requirement. Line numbers below were read on
2026-09-20 at commit `8253b22d` and may drift — locate by symbol.

## Global Constraints

- Read the repo's `CLAUDE.md` first; its architecture rules bind every task (never branch on
  `circuitId != null`, position is derived, `applyPreviewDelta`, `launchOnce`, no destructive DB
  fallback, display code never re-implements pipeline math, etc.).
- `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before gradle.
  `adb` lives at `~/Library/Android/sdk/platform-tools/adb`; an emulator is attached.
- TDD: write the failing test first, watch it fail, then fix. Run the most specific test target
  after each change (`./gradlew :app:testDebugUnitTest --tests "*Foo"`, or
  `./gradlew :app:connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>`).
  Before reporting, run the whole unit suite (`./gradlew :app:testDebugUnitTest`).
- Version control is **jj**. Finish with `jj commit -m "..."` (never `jj describe`, `jj new -m`,
  `git commit`, never push, never move bookmarks). End every commit message with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- No DB schema change in this plan: the DB stays at version 21.
- The backtest gate (`BeliefScoreTest`, `BeliefPolicyBacktestTest`) must stay green and must not be
  re-baselined.
- Each task deletes the TODO entries it closes from `CLAUDE_TODO.md` (delete, don't strike
  through; remove a section heading when its last entry goes). If a task changes a fact stated in
  `CLAUDE.md`, update that sentence in the same commit.
- A bug noticed outside the task's scope is logged to `CLAUDE_TODO.md`, not fixed.
- Match the surrounding code's comment density and naming. No runtime recomposition tests.
- Stay inside the repo: do not touch `~/Library`, `~/.gradle`, `/tmp`, and do not launch or
  restart the emulator.

## Task 1 — Additive backup import skips sessions that already exist

`BackupManager.importAdditive` (`domain/backup/BackupManager.kt`) inserts every backup session
unconditionally, so importing the same file twice doubles every set.

- Before the session loop, read the local sessions once and build a set of
  `(startTime, endTime)` pairs. Skip a backup session whose pair is already present (its sets are
  skipped with it). Add each inserted session's pair to the set, so a file that itself holds a
  duplicate also inserts it once.
- `sessionsAdded` counts only inserted sessions. Do not add a "skipped" count unless the result
  type already has an obvious place for one that the UI shows.
- Tests (`androidTest/.../domain/backup/BackupManagerTest.kt`): importing the same backup twice
  leaves session and set counts unchanged after the second import and reports 0 sessions added;
  a backup holding one known and one new session adds only the new one.
- Closes TODO "Additive backup import duplicates sessions."

## Task 2 — Replay reads the set log once

`ReplayEngine.run` and `ExerciseProgressionSeriesBuilder.build` wire
`setsForSession = { db.workoutSetDao().getSetsForSession(it) }`, which `runCore` calls once per
session. `HistoryViewModel.reloadInternal` calls `repository.getSessionExerciseNames(id)` per
session, and `domain/ActualRepsBackfill.kt` loops sessions the same way.

- Ruling: fetch once and group in memory; **no index, no migration**.
- In each of those entry points, load all sets with one query and serve `setsForSession` from a
  `groupBy { it.sessionId }` map. The per-session list must be identical (same rows, same order)
  to what `getSetsForSession` returns — check that query's `WHERE`/`ORDER BY` and either add a
  DAO query with the same ordering or sort in memory. Note `getSetsForSessions` filters
  `completedAt IS NOT NULL` and is therefore not a drop-in.
- `runCore`'s `setsForSession` lambda seam stays (the core tests use it).
- For History, add one repository function that returns exercise names for all sessions in one
  pass (one sets query + one exercises query) and use it in `HistoryViewModel`.
- `buildAllMergedSeries` must not re-read the set log per exercise if it currently does.
- Tests: existing replay/series/history tests stay green; add a test that the grouped path
  yields the same per-session lists as `getSetsForSession` for a fixture with interleaved
  sessions and an uncompleted set. The backtest gate must not move.
- Closes TODO "Replay is sessions × sets."

## Task 3 — Orphaned sessions are closed at startup; the service survives its timeout

Process death mid-workout leaves a `workout_sessions` row with `endTime` NULL whose sets never
reach replay. Separately, `WorkoutNotificationService` is a `dataSync` foreground service (capped
at ~6 h/day on target SDK 36) with no `onTimeout`, which crashes the app when the cap hits.

- Ruling: do **not** resume (CLAUDE.md: "No restore after process death"). Instead, at process
  start — before the startup `replayDerivedState()` — close every session with `endTime IS NULL`:
  a session with no logged sets is deleted; otherwise `endTime` = its latest `completedAt`
  (fall back to `startTime` if no set has one). A fresh process has no live controller, so no
  in-progress session can be hit. Put it in `WorkoutRepository` (e.g. `closeOrphanedSessions()`),
  in one transaction, and call it from wherever the app-start replay is triggered, ahead of it.
- Ruling: keep `dataSync` (the `health` type needs a runtime `ACTIVITY_RECOGNITION` grant).
  Override `Service.onTimeout(startId, fgsType)` (API 35) to `stopSelf()`. Guard the
  `startForeground` call: if it throws because the quota is exhausted
  (`ForegroundServiceStartNotAllowedException`), log with `Log.w` and `stopSelf()` instead of
  crashing. The workout itself continues in the ViewModel; if the process later dies, the
  startup close above keeps the sets.
- Tests (androidTest, in-memory Room, next to the other repository tests): an orphan with sets
  gets `endTime` = last `completedAt` and its sets are in the replayed state afterwards; an
  orphan with no sets is deleted; a finished session is untouched. The service change has no
  test harness — say so in the report rather than inventing one.
- Update CLAUDE.md's "No restore after process death" bullet to mention the startup close.
- Closes TODO "An abandoned session is orphaned." and "Foreground service type is `dataSync`".

## Task 4 — `saveSessionAsWorkout` keeps an uneven circuit's member order

`WorkoutRepository.saveSessionAsWorkout` orders exercises by first logged set. In an uneven
circuit the late joiner logs its first set later even when it sits first in the block
(`WorkoutSequence.next` picks the member with most sets left; ties go to the earliest slot), so
saved `[A(2), B(3)]` logs B,A,B,A,B and saves back as `[B, A]`.

- Ruling: blocks keep first-logged-set order. **Within a run of exercises that share a non-null
  `circuitId`, order members by their *last* logged set** (`completedAt`, then `id`): rounds are
  aligned from the end (the late-joiner rule), so the final round is the one every member shares
  and it runs in slot order.
- Tests (`androidTest/.../domain/SavedWorkoutRepositoryTest.kt`): log a session for
  `[A(2), B(3)]` in the order `WorkoutSequence.next` produces (B,A,B,A,B) and assert the saved
  workout is `[A(2), B(3)]` in one circuit; an even circuit and a solo-rows session keep their
  order; a circuit followed by a solo row keeps the solo row last.
- Closes TODO "`saveSessionAsWorkout` reorders uneven circuits."

## Task 5 — Logged-set readers use block structure, and Strava names a circuit's members

Three readers of a finished session's sets re-derive circuit grouping by hand.

- Give `CircuitStructure` a way to compute blocks for rows that are not `CircuitRow`s — e.g.
  `blocksBy(rows, circuitIdOf)` that the existing `blocks(rows: List<CircuitRow>)` delegates to,
  so the adjacency rule lives in one place. Keep `Block` as is (`rounds` can be supplied by a
  second selector, or default however reads cleanest).
- `summaryBlocks` (`ui/WorkoutSummaryData.kt`) groups via that function instead of its own loop.
  `SummaryBlocksTest` stays green.
- `ExercisePacingEstimator.appearanceAverage` currently skips any pair where
  `circuitId != null`. Replace with block membership: a set's gaps hold other members' work only
  if **another exercise in the same session logged sets with the same `circuitId`**. A lone
  tagged row (legal mid-session: its partners were removed or never logged) contributes pacing
  samples. Keep `circuitSets_areSkipped_…` green (give its fixture a second member if it needs
  one to still mean "a circuit") and add a test for the lone tagged row.
- `StravaExporter.buildDescription`: "Circuit ×3 / Curl / Kickback / Squat" reads as three
  members when Squat is solo. Ruling on wording: the heading names its members —
  `Circuit ×3: Curl + Kickback` — followed by the exercise sections exactly as today. No
  indentation (Strava may trim it). A lone member still gets no heading. Derive membership via
  the shared block function, not a fresh `filter`. Update `StravaDescriptionTest` (assert the
  full heading line and that a following solo exercise is not named in it).
- Closes TODO "`ExercisePacingEstimator` tests `circuitId != null`", "`summaryBlocks` re-derives
  block grouping", and "Strava description doesn't mark where a circuit ends".

## Task 6 — Controller: the count slider never cuts user-authored rows; HURT undo restores hurt state

Both in `ui/workout/WorkoutSessionController.kt`, tests in
`androidTest/.../ui/workout/WorkoutSessionControllerTest.kt`.

**Slider.** `adjustExerciseCount` trims with `current.take(targetCount)`, which can drop
explicit or pinned rows and split a circuit. CLAUDE.md already says explicit rows are "never
dropped" and the slider "is a minimum".

- Ruling: lowering the slider removes only *plain* rows — not in `explicitIds`, neither
  `repsPinned` nor `weightPinned`, and not a member of a circuit block
  (`CircuitStructure.blocks(...).isCircuit`, never `circuitId != null`) — latest first, until the
  plan is down to `targetCount` or no plain row is left. The plan may therefore stay longer than
  the target; `targetCount` is still stored as asked. Remove via the same path other removals
  use so bookkeeping (`prunedToPlanRows`) stays right.
- The existing tests `lowerCount_trimsFromTailRegardlessOfOrigin` and
  `lowerCount_trimmingACircuitToOneMember_collapsesItToSolo` assert the old behaviour: rewrite
  them to the new rule (explicit tail row survives and an earlier plain row goes; a circuit is
  left whole). Add: a pinned row survives; with no plain rows nothing is removed.

**HURT undo.** `recordFeedback` upserts `ExerciseHurtState(isHurt = true)` on HURT;
`undoLastSet` deletes the set row but leaves the hurt state.

- Undo of a HURT set restores the exercise's hurt-state row to exactly what it was before that
  set (the previous row, or no row). Capture the previous row when recording HURT and carry it
  with the state that undo reads (or a controller field cleared on the next set) — do not guess
  it at undo time. Add the DAO delete this needs.
- Tests: HURT then undo on an exercise with no prior row leaves no row; with a prior
  `isHurt = true` row from earlier, undo restores that row (same `asOf`); HURT without undo
  still leaves `isHurt = true`.
- Closes TODO "Count slider can cut a circuit." and "Undoing a HURT set leaves
  `ExerciseHurtState` set".

## Task 7 — Mechanical batch: forward-nav guard, lifecycle-aware collection, IDE files

- `ui/AppNavigation.kt`: add a `navigateIfResumed` sibling of `popBackStackIfResumed` (same
  RESUMED check on `currentBackStackEntry`; keep an overload/lambda for the two `"home"` calls
  that pass `popUpTo`). Route **every** forward `navController.navigate(...)` through it so a
  double tap pushes once. No test harness exists for the nav graph; do not build one.
- Replace every `collectAsState()` under `ui/` (24 sites) with
  `collectAsStateWithLifecycle()` (`androidx.lifecycle.compose`; the dependency
  `lifecycle-runtime-compose` is already declared). Build must pass; run the instrumented UI
  tests that render these screens (grep `androidTest` for `createComposeRule` /
  `createAndroidComposeRule`) and keep them green.
- Stop tracking machine-local IDE state: add `/.idea/deploymentTargetSelector.xml`,
  `/.idea/deviceManager.xml`, `/.idea/androidTestResultsUserPreferences.xml`,
  `/.idea/studiobot.xml` to `.gitignore` and `jj file untrack` them (files stay on disk).
  Verify with `jj file list .idea`.
- Closes TODO "Forward navigation has no resumed guard.", "Screens use `collectAsState()`", and
  the "Repo" section.

## Task 8 — The saved-workout editor survives process death

`SavedWorkoutEditViewModel` (`ui/savedworkouts/`) keeps name + entries only in memory, while the
screen's two dialog flags are `rememberSaveable`.

- Take a `SavedStateHandle` (the factory gets it via `extras.createSavedStateHandle()`). After
  the initial load and on every edit, save a compact snapshot: name, and per entry
  `exerciseId, reps?, sets, circuitId?, weight?` (primitive arrays or one encoded string — no
  Parcelable on domain classes), plus whatever "dirty" notion the discard-confirm uses.
- On creation, if the handle holds a snapshot, restore from it (re-reading `Exercise` rows by
  id through the repository; an id that no longer exists is dropped) instead of loading the
  stored workout, and the editor is dirty exactly as it was. Otherwise load as today.
- `NEW_WORKOUT_ID` must keep working: a restored new workout is still inserted on first
  non-empty save.
- Tests (androidTest, alongside `SavedWorkoutsViewModelsTest`): edit → build a second ViewModel
  from the same `SavedStateHandle` → same name/entries/dirty state; no snapshot → loads from
  the DB; restoring a snapshot that names a deleted exercise drops that row.
- Closes TODO "Editor state is lost on process death".

## Task 9 — One `CircuitBlockList` for the editor and the plan preview

`ui/workout/PlanPreviewContent.kt` (~205–263) and `ui/savedworkouts/SavedWorkoutEditScreen.kt`
(~136–180) duplicate the block list: `rememberLazyListState` + `rememberReorderableLazyListState`,
`keyedBlocks`, `items` by block key, `ReorderableItem` + drag elevation, per-row link-node wiring
(`linkAbove`, `LinkNodeHost`). The editor also omits `swipeOffsetPx`, so its link node stays put
while a swiped row slides away.

- Extract `CircuitBlockList` into `ui/components/CircuitChrome.kt`, generic over
  `T : CircuitRow<T>`. It owns the list state, reorder state, block items, elevation, and the
  `LinkNodeHost` wrapping of each row (link/unlink callbacks in, `linkedAbove` derived inside).
  The row content is a slot that receives what it needs (the row, its `RowPlace`, the drag-handle
  modifier, and a way to report its live swipe offset so `LinkNodeHost` can follow it).
  Header/footer content of each screen's `LazyColumn` stays possible (slots or `LazyListScope`
  lambdas — whichever keeps both call sites simple).
- Both screens use it. The two swipe behaviours stay different (preview: reveal the three-reason
  action row; editor: remove immediately) — they live in the row slot. The editor now reports
  its swipe offset, fixing the stuck link node.
- Preserve: the drag-handle modifier stays remembered per item; row composables stay skippable —
  after building, read `app/build/compose_reports/app-composables.txt` and confirm the row
  composables and `CircuitBlockList`'s slot parameters did not lose `stable`/skippable status
  relative to before the change (record before/after in the report).
- No behaviour change beyond the editor's link node. Existing instrumented UI tests stay green.
  `docs/verification/2026-09-20-device-check.md` is the manual checklist for these files; note in
  the report that it needs a human re-run.
- Closes TODO "The editor and plan preview still duplicate the block list".

## Task 10 — Accessible alternatives to swipe and drag

No row sets `customActions`; swipe-to-remove and drag-to-reorder are unreachable from TalkBack.
Builds on Task 9's `CircuitBlockList`.

- Each row in both screens gets semantics `customActions`:
  - "Move up" / "Move down": move the row's **block** one block position
    (`CircuitEdits.moveBlock` takes block indices) — omitted at the list edge. Wire once inside
    `CircuitBlockList` using the same move callback drags use (check whether that callback takes
    item indices or block indices and convert correctly).
  - Editor: "Remove" → the same callback the swipe calls.
  - Plan preview: one action per removal reason, labelled as the action-row buttons are
    ("No gear", "Hate it", "Not today" — reuse the existing strings) → `onReplace(reason)`.
- Strings go wherever the file's existing content descriptions live (inline or resources —
  match it).
- Tests: a Compose UI test (androidTest) per screen-level row or for `CircuitBlockList` with a
  fake row list: the custom actions exist with those labels, invoking "Move down" on the first
  block calls the move callback with the right indices, edge rows omit the impossible move, and
  "Remove" calls remove. Use `SemanticsActions.CustomActions`.
- Closes TODO "Swipe and drag have no accessible alternative".

## Task 11 — The exercise-detail chart plots the pipeline, not the old model

`buildPrescribedPoints` (`ui/exercises/ExerciseDetailViewModel.kt`) plots
`baseline × coefficient` from `baseline_history`/`coefficient_history` — the pre-belief model —
and the chart scales sibling dots by seed coefficients. The debug chart
(`ui/debug/ExerciseCoefficientDetailViewModel.kt`) already plots
`ExerciseProgressionSeries.merged` / `.siblingObservations` from
`ExerciseProgressionSeriesBuilder`.

- The user-facing chart's estimate line becomes `series.merged`, and its sibling dots become
  `series.siblingObservations`, read from the same builder call the debug screen uses (share the
  loading path; do not build the series twice per screen if one call already exists in this
  ViewModel for the selectable chart / cross-tuning frames). Own-session dots stay whatever the
  pipeline series provides for them (`ownObservations`) — the two charts must agree point for
  point (see memory of "chart parity": same session dot, same sibling filter, end-time keying,
  shared Y range).
- Delete `buildPrescribedPoints`, its tests, and any state/DAO reads that only fed it. If
  `baseline_history`/`coefficient_history` reads in this ViewModel become unused, remove them
  (not the store itself — other consumers may exist; check).
- Zero-coefficient (bodyweight) exercises still show no estimate line.
- Tests: a ViewModel-level or mapping-level unit test that the chart state's line equals the
  series' `merged` points and the sibling dots equal `siblingObservations` for a small fixture.
- Closes TODO "The exercise-detail chart re-derives prescription math".
