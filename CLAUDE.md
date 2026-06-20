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
- **Done**: triggers `applySessionProgression` then navigates to summary

### Progression system

After every session, `WorkoutRepository.applySessionProgression` updates the per-muscle baseline weight (`MuscleGroupStrength`) via `LastSetAutoregulationHeuristic` (a `BaselineHeuristic`, invoked as `baselineHeuristic.compute(...)`). For each muscle, the heuristic looks at each exercise's last working set performed at full (un-reduced) weight and maps its feedback to a target percentage of the current baseline: RIR_5_PLUS → +15%, RIR_2_4 → +10%, RIR_0_1 → +5%, genuine failure (beyond a 1-rep near-miss) → −5%, HURT → ×0.85 override. Exercises whose weight was dropped mid-session contribute no up-signal. Contributing percentages are averaged, floored to 2.5 kg / 5 lb increments (toward zero), then gated by the existing mid-session reduction clamp as the authoritative downward limit. It also flags any exercise that caused pain (`hurtFlag = true`), writes a `BaselineChangeLog` row per affected muscle, and triggers `recomputeCoefficients` to run every registered `CoefficientHeuristic` (currently `EstCoefConsensusHeuristic`).

Session weight is derived as: `baselineWeight × coefficient[exercise]`, then scaled to the session's rep target (5, 8, or 10 reps, chosen randomly) using the load-aware 1RM formula from https://arxiv.org/pdf/2603.17495 (see `DefaultProgressionEngine`). Coefficients come from `UserCoefficientSource` (latest heuristic value if any, else the seed in `ExerciseCoefficients`). All exercises use a fixed `PlannedExercise.DEFAULT_SETS` (3) sets.

### Location & equipment filtering

On workout start, `LocationService` resolves GPS coordinates to a `KnownLocation`. `WorkoutRepository.buildPlanner` filters out exercises listed in `LocationExcludedExercise` for that location. If location is unknown, no exclusions are applied.

### Database

Room database (`AppDatabase`, version 11). Schema migrations live in `AppDatabase.Companion`. The app has real users — always write a proper `Migration` when bumping the version; destructive fallback is not configured.
