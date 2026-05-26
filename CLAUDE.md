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
data/       Room entities, DAOs, AppDatabase, type converters, seed data (ExerciseLibrary)
domain/     Pure business logic: WorkoutGenerator, ProgressionEngine, WorkoutRepository, WeightFormatter
ui/         Composable screens + ViewModels; one sub-package per screen (home/, workout/, summary/)
location/   GPS lookup and KnownLocation resolution
```

There is no DI framework. `StochasticStrengthApp` (the `Application` class) owns `AppDatabase` as a singleton. ViewModels obtain it via `application as StochasticStrengthApp`.

### Navigation flow

`AppNavigation.kt` wires three screens with string-based routes:

```
home → workout → summary/{sessionId} → home
```

### Workout state machine

`WorkoutState` is a sealed interface with four states managed by `WorkoutViewModel`:

```
Loading → PlanPreview → ActiveSet ⇄ Resting → Done
                                   ↑ (undo)
```

- **PlanPreview**: user reviews/edits the generated exercise list before starting
- **ActiveSet**: user performs a set (may show warmup sets first)
- **Resting**: 90-second countdown after each set; auto-advances or can be skipped/undone
- **Done**: triggers `applySessionProgression` then navigates to summary

### Progression system

After every session, `WorkoutRepository.applySessionProgression` updates the per-muscle baseline weight (`MuscleGroupStrength`) via `ProgressionEngine.applyBaselineFeedback`. Multiple exercises in the same muscle group use conservative aggregation (worst feedback wins). It also flags any exercise that caused pain (`hurtFlag = true`).

Session weight is derived as: `baselineWeight × ExerciseCoefficients[name]`, then scaled to the session's rep target (5, 8, or 10 reps, chosen randomly) using the Epley 1RM formula. All exercises use a fixed `PlannedExercise.DEFAULT_SETS` (3) sets.

### Location & equipment filtering

On workout start, `LocationService` resolves GPS coordinates to a `KnownLocation`. `WorkoutRepository.generateWorkoutForLocation` filters `Exercise` rows to those whose `equipment` appears in `LocationEquipment` for that location. If location is unknown, all equipment is assumed available.

### Database

Room database (`AppDatabase`, version 5). Schema migrations live in `AppDatabase.Companion`. `fallbackToDestructiveMigration(true)` is set as a safety net. When adding a new entity or column, add a numbered `Migration` object before bumping the version.
