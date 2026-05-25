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
- **Theme**: `ui/theme/` contains `Color.kt`, `Type.kt`, and `Theme.kt` (Material3 theming)
- **Entry point**: `MainActivity` sets content via `setContent { StochasticStrengthTheme { ... } }`

Unit tests live in `src/test/` and run on the JVM. Instrumented tests live in `src/androidTest/` and require a device or emulator.
