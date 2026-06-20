# Design: Test Coverage + DI Seams

**Date:** 2026-06-10  
**Status:** Approved

## Goal

Cleanup pass before the progression engine + dynamic-coefficients refactor. Two objectives:
1. Fill test coverage gaps in the domain layer.
2. Introduce constructor-injection seams for `CoefficientSource` and `ProgressionEngine` so the upcoming refactor can swap implementations without surgery on callers.

No DI framework is introduced. `StochasticStrengthApp` wires defaults manually.

---

## 1. `CoefficientSource` Interface

Extract an interface from the current `ExerciseCoefficients` object:

```kotlin
interface CoefficientSource {
    fun get(exercise: Exercise): Float?
}
```

- The current `ExerciseCoefficients` object implements `CoefficientSource` (keeping the existing `byName` map internally). No rename needed.
- `WorkoutPlanner` currently calls `ExerciseCoefficients.byName[exercise.name]` in 3 places; replace with `coefficientSource.get(exercise)`.
- `WorkoutRepository.applySessionProgression` calls `ExerciseCoefficients.byName[it.name]` once; replace similarly.
- Both classes gain a `CoefficientSource` constructor parameter.
- `StochasticStrengthApp` passes `ExerciseCoefficients` (the existing object, which now implements the interface) when constructing `WorkoutRepository`. `WorkoutPlanner` is constructed inside `WorkoutRepository.buildPlanner`, which forwards the injected source.

---

## 2. `ProgressionEngine` Interface

Extract an interface from the current `object ProgressionEngine`:

```kotlin
interface ProgressionEngine {
    fun computeNextBaseline(baseline: Float, feedbacks: List<SetFeedback>, minReductionFraction: Float = 0f, sessionReps: Int = 5): Float
    fun scoreFromFeedbacks(feedbacks: List<SetFeedback>, sessionReps: Int = 5): Float?
    fun toOneRepMax(weight: Float, reps: Int): Float
    fun fromOneRepMax(oneRepMax: Float, reps: Int): Float
    fun scaleReps(weight: Float, from: Int, to: Int): Float
}
```

- Rename the current `object ProgressionEngine` to `class DefaultProgressionEngine` implementing the interface.
- `applyScoreBaseline` and the `raw*` internal methods remain on `DefaultProgressionEngine` (implementation detail, not part of the interface).
- `REP_OPTIONS` moves to a companion object on `DefaultProgressionEngine`.
- `WorkoutPlanner` and `WorkoutRepository` gain a `ProgressionEngine` constructor parameter.
- `StochasticStrengthApp` passes `DefaultProgressionEngine()`.
- `ProgressionEngineTest` is updated to test `DefaultProgressionEngine` directly (no behavior change).

---

## 3. Wiring in `StochasticStrengthApp`

`StochasticStrengthApp` constructs and holds:
- `val progressionEngine: ProgressionEngine = DefaultProgressionEngine()`
- `val coefficientSource: CoefficientSource = ExerciseCoefficients`

`WorkoutRepository` receives both. `WorkoutPlanner` (constructed inside `buildPlanner`) receives both forwarded from the repository.

ViewModels obtain `WorkoutRepository` via `(application as StochasticStrengthApp).workoutRepository` — no change to ViewModel code.

---

## 4. Test Coverage Gaps

### 4a. `StartingWeightsTest` (new unit test file)

- Every `(Sex, StrengthLevel, MuscleGroup)` combination returns a positive value.
- Female baselines are ≤ male baselines at the same `StrengthLevel`.
- `HIGH > MEDIUM > LOW` for each `(Sex, MuscleGroup)` pair.

### 4b. `WorkoutRepositoryTest` — additional instrumented tests

- **Hurt flag:** A session where one set has `HURT` feedback causes `exercise.hurtFlag = true` in the DB.
- **Multi-exercise muscle aggregation:** Two exercises sharing a muscle group in the same session have their feedbacks combined into one list passed to `computeNextBaseline`; a worse feedback from either exercise pulls the result down.
- **`minReductionFraction` wiring:** Passing a reduction cap via `exerciseReductions` constrains the new baseline to not exceed the cap.
- **`buildPlanner` respects location exclusions:** An exercise excluded for a location does not appear in the returned planner's `availableExercises`.

### 4c. `WorkoutPlannerTest` — `recomputeExercise`

- Given a known baseline and exercise, `recomputeExercise` produces a session weight that matches what `weightForExercise` would compute at that baseline (within one rounding unit).

---

## Out of Scope

- Decomposing `WorkoutRepository.applySessionProgression` into helpers — deferred to the next cleanup pass (see memory: `project_cleanup_next.md`).
- Any changes to the progression algorithm or coefficient values.
- Hilt or any other DI framework.
