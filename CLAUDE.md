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

Progression is **per-exercise, in log space, and Bayesian**. Each loaded exercise carries an `ExerciseBelief(mu, sigma2, updatedAt)` — a Gaussian belief over ln(fresh 1RM, kg), where *fresh* = first-set pre-fatigue capacity. The belief map is the only durable progression state; the per-muscle display levels (`MuscleGroupStrength`), `baseline_history`, and `coefficient_history` are derived projections held in the in-memory `DerivedStateStore` (not Room entities). `WorkoutRepository.finishSession()` (and any override write) calls `replayDerivedState()`, which replays every completed session in order through `applySessionProgression`, rebuilding the derived state from scratch each time (idempotent).

For one session, `applySessionProgression`:
1. **HURT** sets produce no load observation. They are collected as muscle-level policy events during replay; `PrescriptionPolicy` applies a decaying caution multiplier (×(1 − 0.15) immediately, ~2-week half-life, floored at 0.6) at prescription time.
2. Per exercise, `SessionProgressionStepper` converts each working set to a `SetObservation` (censored interval, one-sided bound, or Gaussian point from the set's feedback), shifts to the fresh basis (−ln(1 − φ·(k−1)) per set k), and folds them into the belief one by one in set order using `BeliefUpdater`. Each fold ages the prior first: variance grows by q = 8e-5/day (clamped to [σ_min², σ_max²] = [0.02², 0.30²]) and the mean drifts down after the muscle's 14-day grace on long inactivity (detrainRatePerWeek = 0.01, cap 0.25). Gaussian updates use a Kalman step; censored updates use truncated-Gaussian moment matching. The fold is **local** — a failure on exercise *i* never touches its siblings.
3. Read-time **bridge pooling** (`MuscleStrengthProjector.project`) computes a muscle **level** as the n_eff-weighted average of seed-relative opinions against a seed anchor (levelPrior = 0.5). n_eff = max(0, (1/σ² − 1/σ_seed²) · poolObsVar) with poolObsVar = 2.0e-3. Each exercise's belief mean is then bridge-shrunk toward the level's prediction by kappa = min(poolObsVar/tauBridge², siblingExcess) with tauBridge = 0.25 — a fresh own measurement (large n_eff) is barely moved; a cold one adopts the prediction fully. The pooled mean and own σ feed `PrescriptionPolicy`. Phase 3 replaces tauBridge with per-equipment-class τ.
4. `PrescriptionPolicy.prescribe(exercise, sessionReps)` computes the session weight in this order: base target exp(μ̃ − z·σ̃ + δ + ln(1 − φ·(S−1))) with uncertaintyZ = 0.4, overloadDelta = 0.02, fatiguePerSet = 0.03, S = 3; then failure ceiling clamp (ceilingFactorClear = 0.97 for clear misses, adjudicated round-down on coarse grids); then HURT multiplier; then grid rounding. Sore-muscle cooldown (`muscleRested`) gates workout generation. If the detraining drift exceeded noticeThresholdFraction = 3%, a passive notice line is shown in PlanPreview.

The tuning constants live in `EstimatorConfig` and are pinned by `BeliefSimulationTest` (phase-2 values; re-pinned 2026-07-08). The real-history backtest (`ProdBssPrescriptionTest` + the full backtest JVM test) gates each phase's re-baseline (re-baselined to phase-2 output 2026-07-09, BAND 0.05). Manual weight overrides write per-exercise `ExerciseStrengthOverride` rows folded in during replay as belief resets at sigmaOverride = 0.10.

Session weight is scaled to the session's chosen rep target via the load-aware 1RM formula from https://arxiv.org/pdf/2603.17495 (see `DefaultProgressionEngine`). Seed coefficients come from `ExerciseCoefficients`; derived coefficients (effectiveE1rm / level) are display projections only. All exercises use a fixed `PlannedExercise.DEFAULT_SETS` (3) sets.

### Location & equipment filtering

On workout start, `LocationService` resolves GPS coordinates to a `KnownLocation`. `WorkoutRepository.buildPlanner` filters out exercises listed in `LocationExcludedExercise` for that location. If location is unknown, no exclusions are applied.

### Database

Room database (`AppDatabase`, version 17). Schema migrations live in `AppDatabase.Companion`. The app has real users — always write a proper `Migration` when bumping the version; destructive fallback is not configured.
