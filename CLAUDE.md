# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./gradlew :app:assembleDebug

# Unit tests (runs on JVM, no device needed)
./gradlew :app:testDebugUnitTest

# Run a single unit test class
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ExampleUnitTest"

# Instrumented tests (requires connected device/emulator)
./gradlew :app:connectedAndroidTest

# Lint
./gradlew :app:lint
```

## Architecture

Single-module Android app (`app/`) using Kotlin and Jetpack Compose with Material3.

- **Package**: `io.github.fowles.stochastic_strength`
- **Min SDK**: 33 (Android 13), **Target SDK**: 36
- **UI**: Jetpack Compose — all UI is written in Kotlin composables, no XML layouts
- **Theme**: `ui/theme/` — Material3 theming
- **Entry point**: `MainActivity` sets content via `setContent { StochasticStrengthTheme { ... } }`

Unit tests live in `src/test/` and run on the JVM. Instrumented tests live in `src/androidTest/` and require a device or emulator.

### Layers

```
data/           Room entities, DAOs, AppDatabase, type converters, seed data (ExerciseLibrary)
domain/         Pure business logic: WorkoutPlanner, ProgressionEngine, WorkoutRepository, coefficient heuristics
domain/strava/  Strava OAuth + JSON export
ui/             Composable screens + ViewModels; one sub-package per screen (home/, workout/, history/, debug/, etc.)
ui/components/  Shared composables (SectionHeader, StrengthGrid, LoadingBox, formatDateTime)
location/       GPS lookup and KnownLocation resolution
notification/   Workout foreground notification service
```

There is no DI framework. `StochasticStrengthApp` (the `Application` class) owns `AppDatabase`, `workoutRepository`, `stravaExporter`, and `workoutSessionBus` as singletons. ViewModels obtain them via `application as StochasticStrengthApp`.

### Navigation

`AppNavigation.kt` wires the app's screens with string-based routes. The primary flow is `home → workout → summary/{sessionId} → home`; secondary screens (history, locations, exercises, about, debug detail screens) are reachable from home.

### Workout state machine

`WorkoutState` is a sealed interface with five states. State is owned by `WorkoutSessionController` (in `ui/workout/`); `WorkoutViewModel` is a thin delegation layer.

```
Loading → PlanPreview → ActiveSet ⇄ Resting → Done
                                   ↑ (undo)
```

- **PlanPreview**: user reviews/edits the generated exercise list before starting
- **ActiveSet**: user performs a set (may show warmup sets first)
- **Resting**: 90-second countdown after each set; auto-advances or can be skipped/undone
- **Done**: triggers `replayDerivedState` then navigates to summary

### Progression system (belief stack + policy layer)

Progression is **per-exercise, log-space, Bayesian**. Each loaded exercise carries a `Belief` (`mu` = ln(fresh 1RM, kg), `sigma2` = variance in ln-units², `updatedAt`), held in `domain/belief/`. The belief map is the only durable progression state; the per-muscle display levels (`MuscleGroupStrength`), `baseline_history`, and `coefficient_history` are derived projections held in the in-memory `DerivedStateStore` (not Room entities). `WorkoutRepository.finishSession()` (and any override write) calls `replayDerivedState()`, which replays every completed session in order through `ReplayEngine` → `BeliefSessionStep`, rebuilding the derived state from scratch each time (idempotent). All tuning constants live in `BeliefConfig`, each labeled `semantic`/`fitted`/`flat` (constitution rule 2); the fitted values are pinned by the backtest gate (`BeliefScoreTest`, held-out score on real history).

For one session, `BeliefSessionStep.step`:
1. **Pre-fold pooling** (`BeliefPooling.effective`) over the session's muscles at `asOf` — the held-out state for scoring and the cold prior for first-time exercises.
2. **Per-exercise fold** (`BeliefFold.foldSession`): age `sigma2` by idle days (`qPerDay`), then fold each set in id order. Each set implies a model-free ln-1RM interval (`SetIntervals`, from the rep-max formula + feedback bucket), shifted up by a fatigue term `phi·(rank−1)`. The fold is a boundary-pull Gaussian/Tobit update: mu inside the interval → confirmation (sigma shrinks, mu unmoved); outside → one Kalman step at the violated boundary. `HURT` and feedback-less sets carry no interval (but count toward rank); zero-coefficient (unloadable) exercises are skipped. The fold is **local** — cross-informing happens only at read time.
3. **Post-fold pooling** for the touched muscles: each exercise with a belief votes `mu_j − ln(coef_j)` with precision `1/(sigma_j² + tau²)`; the effective belief blends the own aged belief with the leave-one-out sibling prediction by precision. Fresh tight evidence outvotes siblings; stale/cold exercises lean on them; never mutates stored beliefs. The muscle level goes to `MuscleGroupStrength` + `baseline_history` (epsilon-deduped), derived coefficients to `coefficient_history`. The pooling result exposes its per-exercise breakdown (`own`/`sibling`/`siblingShare`/`voterWeight`) — consumers (trace, cross-tuning, charts) must read that, never re-derive the math.

Manual baseline edits and detraining write per-exercise `ExerciseStrengthOverride` rows that seed/reset beliefs during replay (`sigmaSeed` for initial rows, `sigmaOverride` for deliberate edits).

**Prescription** is estimator → prescriber → policy, in that order:
- `BeliefPooling.effective` → `BeliefPrescriber.targetE1rm` (30th-percentile of the effective belief, `Z`) gives the raw target.
- `PrescriptionPolicy.prescribe` clamps it: HURT backoff (15% per event, 14-day half-life, floor 0.6, muscle-level), unconditional overload nudge (+1 grid increment when the last feedback session was all RIR ≥ 2), then the **demonstrated-capacity cap** (a failed weight from the most recent feedback session cannot be re-prescribed for 28 days; the cap binds on the final *rounded* weight and floor-rounds at the grid). Policy rules are plain set-log arithmetic (`PolicyFacts`, built over a **time window** `FACTS_WINDOW_MS` — never a row-count limit) with `semantic` constants only, invisible to the backtest fitness function.
- The load-aware 1RM formula is https://arxiv.org/pdf/2603.17495 (`DefaultProgressionEngine`). Seed coefficients come from `ExerciseCoefficients`; the planner's `coefficientSource` is the effective source (latest derived coefficient if any, else the seed). All exercises use a fixed `PlannedExercise.DEFAULT_SETS` (3) sets.

The debug "why this weight" trace (`PrescriptionTraceBuilder`) and the planner share `WorkoutRepository.prescriptionContext`; the trace reports what `prescribe()` did via the `Prescription` fields — do not re-implement pipeline math in display code.

The backtest tree (`app/src/test/.../backtest/`) replays real history (`src/test/resources/backtest/history.json`) through the same `BeliefSessionStep`; `BeliefScoreTest` pins the held-out score and `BeliefPolicyBacktestTest` certifies the failed-weight invariant. Changes to fold/pooling/config must keep the gate green (re-baselining is a human decision).

### Location & equipment filtering

On workout start, `LocationService` resolves GPS coordinates to a `KnownLocation`. `WorkoutRepository.buildPlanner` filters out exercises listed in `LocationExcludedExercise` for that location. If location is unknown, no exclusions are applied.

### Database

Room database (`AppDatabase`, version 17). Schema migrations live in `AppDatabase.Companion`. The app has real users — always write a proper `Migration` when bumping the version; destructive fallback is not configured.
