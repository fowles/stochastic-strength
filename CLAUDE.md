# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew :app:assembleDebug

# Unit tests (runs on JVM, no device needed)
./gradlew :app:testDebugUnitTest

# Run a single unit test class
./gradlew :app:testDebugUnitTest --tests "*WorkoutPlannerTest"

# Instrumented tests (requires connected device/emulator; one class via
# -Pandroid.testInstrumentationRunnerArguments.class=<fqcn>)
./gradlew :app:connectedAndroidTest

# Lint
./gradlew :app:lint
```

`JAVA_HOME` is not set on the dev machine: export
`JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` before any gradle call.
Most ViewModel/controller/repository/DAO/migration tests are **instrumented** (`src/androidTest`);
`src/test` holds the pure-domain tests and the backtest.

## Version control

This repo is managed with **jj (Jujutsu)** on a git backend (`.jj/` present; git sits at a
detached HEAD that jj drives — `git branch --show-current` printing nothing is normal). Work
jj-natively; do not create git branches or `git commit` onto the detached HEAD.

- **Finish a change with `jj commit -m "..."`** — it describes the working copy and opens a fresh
  empty change on top, in one step. Do NOT use `jj describe` alone, and do NOT "open" a commit
  ahead of the work with `jj new -m`: in jj the working copy *is* a commit, so edits land in `@`
  as they are made, and the next task's edits silently land in the previous commit.
- **Commit finished work by default** — when a change is complete and tests pass, commit it in the
  same turn. Don't ask first. Commit at every checkpoint a skill would call a `git commit`
  (TDD red/green/refactor, each plan/subagent task).
- **Stop at commits.** The user owns the upstream side: never `jj git push`, `git push`, move a
  bookmark, or open a PR. Report "commits are on `@-`" and let them reshape.
- **Sync by rebasing, never merging**: `jj rebase -d <trunk change>` when trunk moves underneath.
- **For isolation use `jj workspace add`, not `git worktree`** — a git worktree on a colocated repo
  is the split-brain colocation exists to avoid. The one sanctioned exception is a *read-only*
  worktree at an old commit to build historical code (e.g. the backtest baseline): copy
  `local.properties` in, `git worktree remove --force` after, never edit there.
- `jj commit` snapshots the **entire** working copy — don't touch repo files while a subagent is
  running, or your edits get swept into that subagent's commit. (`.superpowers/` is gitignored for
  this reason; keep it that way.) `jj op log` undoes jj mistakes.

## Architecture

Single-module Android app (`app/`), Kotlin + Jetpack Compose + Material3 (dynamic color only).
Package `io.github.fowles.stochastic_strength`; min SDK 33, target 36. No XML layouts, no DI
framework: `StochasticStrengthApp` owns the singletons (`database`, `workoutRepository`,
`derivedStateStore`, `backupManager`, `stravaExporter`, `workoutSessionBus`, `applicationScope`)
and ViewModels reach them via `application as StochasticStrengthApp`.

```
data/           Room entities, DAOs, AppDatabase + migrations, seed data (ExerciseLibrary)
domain/         WorkoutRepository, WorkoutPlanner, circuit structure, sequencing, formatting
domain/belief/  per-exercise estimates: fold, pooling, prescriber, debug trace
domain/policy/  PrescriptionPolicy + PolicyFacts (set-log rules on top of the estimate)
domain/progression/  ReplayEngine, BeliefSessionStep, chart series
domain/derived/ DerivedStateStore (in-memory projections)   domain/history/  highlights, quips
domain/backup/  JSON export/import                          domain/strava/   OAuth + upload
ui/<screen>/    composables + ViewModels; ui/components/ shared (CircuitChrome, pickers, charts)
location/  notification/   GPS → KnownLocation; workout foreground service
```

**Navigation** (`AppNavigation.kt`, string routes): `home → workout → home`. The workout screen
renders its own Done summary; `summary/{sessionId}` is reached from history. Home also leads to
`workouts` / `workout-edit/{id}`, history, locations, exercises (→ `exercise/{id}` →
`debug/coefficient/{id}`), about.

**Compose stability**: `app/compose-stability.conf` declares Kotlin's collection interfaces
stable, and model classes used as composable parameters (`Exercise`, `WarmupSet`,
`PlannedExercise`, `SavedWorkoutEntry`, `RowSuggester`) carry `@Immutable`. Skippability is a
**compile-time** property: read `app/build/compose_reports/app-composables.txt` after a build (a
parameter with no `stable` prefix blocks skipping). Never write a runtime recomposition test.

### Workout session

`WorkoutState` (sealed) is owned by `WorkoutSessionController`; `WorkoutViewModel` delegates.

```
Loading → PlanPreview → ActiveSet ⇄ Resting → Done
```

- **Position is derived, never stored.** `ActiveSet`/`Resting` carry `done` (completed working
  sets per exercise id); `WorkoutSequence.next(plan.exercises, done)` is the single rule for what
  comes next (controller, notification, rest screen). An exercise id appears at most once in a plan.
- Warmups are a sub-state of `ActiveSet` (`warmupSetIndex`), entered only at an exercise's first
  set. A 90 s rest (`DurationCalculator.REST_SECONDS`) follows every set.
- **Staged rests**: swap, adjust weight, end exercise, stop, and warmups-done pass through
  `Resting(staged = StagedAction(undoTarget, commitTarget))`. Undo restores `undoTarget`; the
  commit applies the state **first**, then persists side effects (`persistSwap`).
- HURT ends the exercise (it drops out of remaining circuit rounds). A mid-exercise swap gives the
  replacement only the remaining sets. In-session removal (`withoutRow`) must **not** renumber
  circuit ids — logged rows already carry them — so a lone tagged row is legal mid-session.
- Preview edits that suspend go through `applyPreviewDelta` (a non-suspend transform over the
  *live* state); never write back a snapshot taken before a suspend. Taps that write before moving
  state go through `launchOnce`.
- Ending the workout sets `endTime`, shows `Done`, and runs `repository.finishSession()` (replay)
  itself; the Done button only waits for that and navigates.
- No restore after process death: the foreground service keeps the process alive. If it dies
  anyway, `WorkoutRepository.closeOrphanedSessions()` runs at the next process start, ahead of the
  startup replay: a session with `endTime IS NULL` and no logged sets is deleted, otherwise
  `endTime` becomes its latest set's `completedAt` (falling back to `startTime`) so the sets it did
  log still fold into replay. It never resumes a session.

### Plans, saved workouts, circuits

- Rows carry `sets` (1–10) and a nullable `circuitId`; **adjacent** rows sharing an id are a
  circuit done round-robin. Storage, `WorkoutPlan.exercises` and ViewModel state stay flat; logic
  reads `CircuitStructure.blocks` (a solo row is a block of one; `rounds` = largest member `sets`;
  `Block.roundsDone` is the late-joiner rule). Never branch on `circuitId != null`.
- `CircuitEdits` (link / unlink / moveBlock / remove / setRounds) is the shared editing vocabulary
  for the editor and the plan preview. Link nodes toggle membership; drags move whole blocks.
  Circuits may be uneven (`saveSessionAsWorkout` saves each member at the rounds it got); the
  round chip shows the block maximum and `setRounds` re-levels the block.
- The shared row is `ui/components/CircuitChrome.kt` (`ExerciseRowScaffold`, `LinkNodeHost`,
  `ValueStepper`, `keyedBlocks`); reordering uses `sh.calvin.reorderable`. `CircuitBlockList` is
  the one list both the editor and the plan preview render: it owns the reorder state, the
  block-per-item structure, the drag elevation and the link-node wiring, and each screen supplies
  only its row body. A swiped row reports its live offset back through `SwipeOffsetRelay` so its
  link node follows it; the two screens' swipe *meanings* (preview: reveal the reason row; editor:
  remove) stay in the row body.
- `PlannedExercise.repsPinned` / `weightPinned` mark user-set values. `WorkoutPlanner.reprice`
  (private `withWeight`) is the **single pricing rule** and the only place that honours pins: a
  pinned weight bypasses `PrescriptionPolicy` and nothing reprices it; the rep-range slider
  reprices only unpinned reps. The UI shows the live suggestion beside a differing pin
  (`SuggestionNote`). Stepper taps move whole grid increments via `WeightFormatter.step`
  (2.5 kg / 5 lb); `clampToGrid` is the floor rule.
- `WorkoutPlanner.planExplicit` prices user-chosen rows: they bypass the rested-muscle and
  location filters, are flagged in the UI and never dropped, and raw stored values are clamped
  there (imports are not sanitized on write). The controller tracks which ids are explicit; rows
  carry no origin flag. The exercise-count slider is a **minimum** (restock on swipe-away only
  below `targetCount`).
- `saved_workout` / `saved_workout_exercise` hold user-authored workouts with optional per-row
  `reps` and literal `weight` (kg, never progresses). An unnamed workout is stored with an empty
  name and shown under a derived one (`SavedWorkoutNaming`). The editor saves only on Done (back
  discards, with a confirm); a new workout (`NEW_WORKOUT_ID`) is inserted on its first non-empty
  save and the editor never deletes a row. Off-session suggestions come from
  `WorkoutRepository.rowSuggester()`.
- `workout_sets.circuitId` records a finished session's structure (summary, Strava description,
  save-as-workout); `setNumber` stays per-exercise and dense.
- Location: `LocationService` resolves GPS to the nearest `KnownLocation`; `buildPlanner` filters
  out that location's `LocationExcludedExercise` rows (none when unknown). Excluded siblings still
  vote in pooling, so a prescription never depends on where the user stands.

### Progression (belief stack + policy)

Each loaded exercise has a `Belief` — `bestGuessLn` (ln of fresh 1RM, kg), `uncertainty`
(ln-units²), `updatedAt`. These are **estimates, not measurements**. All progression state is
derived: `DerivedStateStore` (in memory) holds beliefs, `MuscleGroupStrength`, `baseline_history`
and `coefficient_history`, rebuilt from scratch by `WorkoutRepository.replayDerivedState()`
(`ReplayEngine` → `BeliefSessionStep`, idempotent) on app start, finish, session delete and
import. Fitted constants live in `BeliefConfig` (in `Belief.kt`), each labeled
`semantic`/`fitted`/`flat`, and are pinned by the backtest gate.

Per session, `BeliefSessionStep.step`:
1. **Pre-fold pooling** (`BeliefPooling.effective`) — the held-out state for scoring and the cold
   prior for first-time exercises.
2. **Fold** (`BeliefFold.foldSession`): age uncertainty by idle days, then fold each set in id
   order. A set implies a ln-1RM interval (`SetIntervals`: rep-max formula + feedback bucket),
   shifted by `fatiguePerSetEstimate·(rank−1)` where rank is **per exercise** (unaffected by
   circuit interleaving). Inside the interval confirms (uncertainty shrinks); outside takes one
   step toward the violated boundary. `HURT`/feedback-less sets carry no interval but count toward
   rank; zero-coefficient exercises are skipped. The fold is local.
3. **Post-fold pooling**: each exercise votes `bestGuessLn − ln(coef)`, weighted
   `1/(uncertainty + crossLiftIndependenceEstimate²)`; the effective estimate blends own with the
   leave-one-out sibling prediction. Never mutates stored beliefs. Consumers (trace, charts) read
   the result's breakdown (`own`/`sibling`/`siblingShare`/`voterWeight`) — never re-derive it.

Cold-start seeds are synthesized live during replay (`ExerciseSeedExpansion`): each per-muscle
`BaselineOverride` (only migration/import write these; otherwise `StartingWeights` for the user's
sex/level) × the *current* `ExerciseCoefficients`. A new coefficient table needs no migration.

**Prescription** = estimator → prescriber → policy:
- `BeliefPrescriber.targetE1rm` backs off `cautionMargin` standard deviations (≈ 70%
  `targetSuccessChance`).
- `PrescriptionPolicy.prescribe`: HURT backoff (15%/event, 14-day half-life, floor 0.6,
  muscle-level) → grid round + overload nudge (+1 increment when the last feedback session was all
  RIR ≥ 2) → **demonstrated-capacity cap** on the rounded weight (a failed weight can't be
  re-prescribed for 28 days). `COOLDOWN_MS` (2 days) is the planner's rested-muscle filter.
  Policy is plain set-log arithmetic (`PolicyFacts`, over a **time window** `FACTS_WINDOW_MS`,
  never a row count) with `semantic` constants, invisible to the backtest.
- 1RM formula: https://arxiv.org/pdf/2603.17495 (`DefaultProgressionEngine`).
- The debug trace (`PrescriptionTraceBuilder`) and the planner share
  `WorkoutRepository.prescriptionContext`; display code reports `Prescription` fields and never
  re-implements pipeline math.

`ExerciseCoefficients.byName` is a baked artifact (`guess^0.75`; anchors 1.0 and 0.0 unchanged).
The guesses and compression live in the **test tree** (`CoefficientGuesses`,
`CoefficientCompression`); `ExerciseCoefficientsTest` guards exact equality. Re-fitting means
re-baking the table.

The backtest (`app/src/test/.../backtest/`, real history in `resources/backtest/history.json`)
replays through the same `BeliefSessionStep`. `BeliefScoreTest` pins the held-out score;
`BeliefPolicyBacktestTest` certifies the failed-weight invariant. Fold/pooling/config changes must
keep the gate green; **re-baselining is a human decision**.

### Database

Room, version 21, schemas exported to `app/schemas/`. The app has real users: every version bump
gets a `Migration` in `AppDatabase.Companion` plus a `MigrationNToMTest`; there is no destructive
fallback of any kind (a failed open must crash, never reset). No foreign keys or unique
constraints: integrity lives in `WorkoutRepository` transactions. Backup accepts
`MIN_DB_VERSION..DB_VERSION` and defaults missing keys; additive import matches exercises and
locations by name.

`espresso-core` is pinned in the build although nothing imports it: compose ui-test's transitive
version crashes on current API levels.
