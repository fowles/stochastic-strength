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
domain/policy/  Prescription policy: failure ceilings, HURT caution, rest cooldown (PolicyState derived in replay)
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
- **Done**: triggers `applySessionProgression` then navigates to summary

### Progression system

Progression is **per-exercise and log-space**. Each loaded exercise carries an `ExerciseEstimate` (`lnE` = ln(estimated 1RM, kg) plus a recency-decayed `confidence`). The estimate map is the only durable progression state; the per-muscle display levels (`MuscleGroupStrength`), `baseline_history`, and `coefficient_history` are derived projections held in the in-memory `DerivedStateStore` (not Room entities). `WorkoutRepository.finishSession()` (and any override write) calls `replayDerivedState()`, which replays every completed session in order through `applySessionProgression`, rebuilding the derived state from scratch each time (idempotent).

For one session, `applySessionProgression`:
1. **HURT** never touches estimates: it becomes a muscle-level policy event; `PrescriptionPolicy` applies a decaying caution multiplier (×0.85 immediately, ~2-week half-life) at prescription time.
2. Per exercise, `SessionSignalExtractor.aggregateSession` collapses the full-weight sets into an implied 1RM (`est1RM`) plus a `bracketConfidence`. Sets aggregate via a recency EMA (`RECENCY_BETA`) so the last/most-fatigued set dominates; a session containing any full-weight failure is capped at zero deviation (it can never grow the weight). Dropped/reduced-weight sets and `HURT` carry no load signal; zero/null-coefficient (unloadable) exercises are skipped entirely.
3. `ExerciseEstimateUpdater.fold` folds that observation into the exercise's estimate as a log-space EMA. **Up-signals** get a small weight (`wUp`, gentle progressive overload); **down-signals** get a larger weight (`wDown`, interpolated toward `wDownSnap` by `bracketConfidence`) so a just-failed weight isn't re-prescribed. Confidence accumulates (capped at `confidenceCap`) and decays by a ~21-day half-life. The fold is **local** — a failure on exercise *i* never touches its siblings; cross-informing happens only at read time.
4. Read-time pooling (`MuscleStrengthProjector.project`) computes a muscle **level** L (confidence-weighted geomean of `E_j / seedCoef_j` over confident exercises), then **shrinks** each exercise's own estimate toward its sibling-implied prediction (`L × seedCoef`) by confidence (`priorStrength`). Cold/stale exercises lean on their siblings; this never mutates the stored estimates. The projection's per-muscle level is written to `MuscleGroupStrength` + a `baseline_history` row (epsilon-deduped), and the per-exercise derived coefficients to `coefficient_history`.

The estimator's tuning constants live in `EstimatorConfig` and are pinned by `ExerciseEstimatorSimulationTest`. Manual baseline edits and detraining write per-exercise `ExerciseStrengthOverride` rows (`applyManualExerciseOverrides` / `applyDetrainingReduction`) that are folded in during replay.

Session weight is derived per exercise from its **projected effective 1RM** (`MuscleStrengthProjector.project(...).effectiveE1rm`, wrapped in `PrescriptionPolicy` (failure ceiling, HURT caution, rest cooldown) and passed to the planner), scaled to the session's chosen rep target via the load-aware 1RM formula from https://arxiv.org/pdf/2603.17495 (see `DefaultProgressionEngine`). Seed coefficients come from `ExerciseCoefficients`; the planner's `coefficientSource` is the effective source (latest derived coefficient if any, else the seed). All exercises use a fixed `PlannedExercise.DEFAULT_SETS` (3) sets.

### Location & equipment filtering

On workout start, `LocationService` resolves GPS coordinates to a `KnownLocation`. `WorkoutRepository.buildPlanner` filters out exercises listed in `LocationExcludedExercise` for that location. If location is unknown, no exclusions are applied.

### Database

Room database (`AppDatabase`, version 17). Schema migrations live in `AppDatabase.Companion`. The app has real users — always write a proper `Migration` when bumping the version; destructive fallback is not configured.
