# Test Coverage + DI Seams Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill domain-layer test gaps and introduce constructor-injection seams for `CoefficientSource` and `ProgressionEngine` before the upcoming progression-engine + dynamic-coefficients refactor.

**Architecture:** Extract two interfaces (`CoefficientSource`, `ProgressionEngine`) from their current static `object` implementations. Both get Kotlin default parameter values on constructor params so no ViewModel callsites need updating. New tests cover `StartingWeights`, `WorkoutPlanner.recomputeExercise`, and four new `WorkoutRepository` scenarios.

**Tech Stack:** Kotlin, JUnit 4, Room (in-memory), AndroidX Test (instrumented). No new libraries.

---

## File Map

| File | Action |
|------|--------|
| `domain/CoefficientSource.kt` | **Create** — new interface |
| `domain/ExerciseCoefficients.kt` | **Modify** — implement `CoefficientSource` |
| `domain/ProgressionEngine.kt` | **Modify** — becomes interface (replaces current `object`) |
| `domain/DefaultProgressionEngine.kt` | **Create** — `object` implementing `ProgressionEngine` |
| `domain/WorkoutPlanner.kt` | **Modify** — add two constructor params, replace static calls |
| `domain/WorkoutRepository.kt` | **Modify** — add two constructor params, replace static calls |
| `test/.../ProgressionEngineTest.kt` | **Modify** — update references to `DefaultProgressionEngine` |
| `test/.../WorkoutPlannerTest.kt` | **Modify** — update static refs + add `recomputeExercise` test |
| `test/.../StartingWeightsTest.kt` | **Create** — new unit test file |
| `androidTest/.../WorkoutRepositoryTest.kt` | **Modify** — add four new instrumented tests |

---

## Task 1: Create `CoefficientSource` interface

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientSource.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseCoefficients.kt`

- [ ] **Step 1: Create the interface**

Create `domain/CoefficientSource.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise

interface CoefficientSource {
    fun get(exercise: Exercise): Float?
}
```

- [ ] **Step 2: Make `ExerciseCoefficients` implement it**

In `domain/ExerciseCoefficients.kt`, change the first line of the object declaration and add the `override fun get`:

```kotlin
object ExerciseCoefficients : CoefficientSource {
    val byName: Map<String, Float> = mapOf(
        // ... (existing map unchanged) ...
    )

    override fun get(exercise: Exercise): Float? = byName[exercise.name]
}
```

The `byName` map body stays exactly as it is — only add the `: CoefficientSource` clause and the `override fun get` at the bottom.

- [ ] **Step 3: Run unit tests to confirm no regression**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
jj commit -m "refactor: extract CoefficientSource interface from ExerciseCoefficients"
```

---

## Task 2: Wire `CoefficientSource` into `WorkoutPlanner` and `WorkoutRepository`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [ ] **Step 1: Add `coefficientSource` to `WorkoutPlanner` constructor**

In `WorkoutPlanner.kt`, add the parameter to the constructor (after `nowMs`):

```kotlin
class WorkoutPlanner(
    val availableExercises: List<Exercise>,
    private val strengths: Map<MuscleGroup, MuscleGroupStrength>,
    val recentHistory: Map<Long, List<WorkoutSet>>,
    val weightUnit: WeightUnit,
    val locationId: Long?,
    private val random: Random = Random.Default,
    private val nowMs: Long = System.currentTimeMillis(),
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
)
```

- [ ] **Step 2: Replace `ExerciseCoefficients.byName[...]` calls in `WorkoutPlanner`**

There are three callsites. Replace each `ExerciseCoefficients.byName[exercise.name]` with `coefficientSource.get(exercise)` (or `coefficientSource.get(pe.exercise)` where the variable is a `PlannedExercise`):

In `weightForExercise`:
```kotlin
private fun weightForExercise(exercise: Exercise, sessionReps: Int): Float {
    val coeff = coefficientSource.get(exercise) ?: return 0f
    if (coeff <= 0f) return 0f
    val baseline = strengths[exercise.primaryMuscle]?.baselineWeight ?: return 0f
    return WeightFormatter.round(
        ProgressionEngine.fromOneRepMax(baseline * coeff, sessionReps),
        weightUnit,
    )
}
```

In `deriveBaselineFromSessionWeight`:
```kotlin
fun deriveBaselineFromSessionWeight(sessionWeight: Float, pe: PlannedExercise): Float {
    val coeff = coefficientSource.get(pe.exercise) ?: return 0f
    if (coeff <= 0f) return 0f
    return ProgressionEngine.toOneRepMax(sessionWeight, pe.sessionReps) / coeff
}
```

In `recomputeExercise`:
```kotlin
fun recomputeExercise(pe: PlannedExercise, newBaselineKg: Float): PlannedExercise {
    val coeff = coefficientSource.get(pe.exercise) ?: return pe
    if (coeff <= 0f) return pe
    val newWeight = WeightFormatter.round(
        ProgressionEngine.fromOneRepMax(newBaselineKg * coeff, pe.sessionReps),
        weightUnit,
    )
    return pe.copy(
        sessionWeight = newWeight,
        warmupSets = if (pe.exercise.isTimed) emptyList() else computeWarmupSets(newWeight),
    )
}
```

Note: `ProgressionEngine.fromOneRepMax` and `ProgressionEngine.toOneRepMax` still refer to the current static `object ProgressionEngine` — that is correct at this stage. Task 4 will replace them.

- [ ] **Step 3: Add `coefficientSource` to `WorkoutRepository` constructor**

In `WorkoutRepository.kt`, add the parameter:

```kotlin
class WorkoutRepository(
    private val db: AppDatabase,
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
)
```

- [ ] **Step 4: Replace `ExerciseCoefficients.byName[...]` call in `WorkoutRepository`**

In `applySessionProgression`, change the filter predicate:

```kotlin
val exercisesByMuscle = exerciseById.values
    .filter { (coefficientSource.get(it) ?: 0f) > 0f }
    .groupBy { it.primaryMuscle }
```

- [ ] **Step 5: Forward `coefficientSource` when constructing `WorkoutPlanner` in `buildPlanner`**

In `buildPlanner`, the returned `WorkoutPlanner(...)` call needs the new param:

```kotlin
return WorkoutPlanner(
    availableExercises = available,
    strengths = strengths,
    recentHistory = history,
    weightUnit = weightUnit,
    locationId = locationId,
    coefficientSource = coefficientSource,
)
```

- [ ] **Step 6: Run unit and instrumented tests**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedAndroidTest
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
jj commit -m "refactor: inject CoefficientSource into WorkoutPlanner and WorkoutRepository"
```

---

## Task 3: Extract `ProgressionEngine` interface and `DefaultProgressionEngine`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionEngine.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/DefaultProgressionEngine.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionEngineTest.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt`

- [ ] **Step 1: Replace the contents of `ProgressionEngine.kt` with the interface**

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback

interface ProgressionEngine {
    fun computeNextBaseline(
        baseline: Float,
        feedbacks: List<SetFeedback>,
        minReductionFraction: Float = 0f,
        sessionReps: Int = 5,
    ): Float

    fun scoreFromFeedbacks(feedbacks: List<SetFeedback>, sessionReps: Int = 5): Float?

    fun toOneRepMax(weight: Float, reps: Int): Float
    fun fromOneRepMax(oneRepMax: Float, reps: Int): Float
    fun scaleReps(weight: Float, from: Int, to: Int): Float
}
```

- [ ] **Step 2: Create `DefaultProgressionEngine.kt` with the full implementation**

Create `domain/DefaultProgressionEngine.kt` with the entire body of the old `object ProgressionEngine`, renamed, and adding `REP_OPTIONS` to the companion:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object DefaultProgressionEngine : ProgressionEngine {
    private const val INTERNAL_INCREMENT = 0.5f

    val REP_OPTIONS = listOf(5, 8, 10)

    override fun computeNextBaseline(baseline: Float, feedbacks: List<SetFeedback>, minReductionFraction: Float, sessionReps: Int): Float {
        if (feedbacks.isEmpty() && minReductionFraction == 0f) return baseline
        if (SetFeedback.HURT in feedbacks) return weightDecreased(baseline, 0.85f)
        val score = scoreFromFeedbacks(feedbacks, sessionReps)
        val scoreResult = if (score != null) applyScoreBaseline(baseline, score) else baseline
        if (minReductionFraction > 0f) {
            val cap = maxOf(INTERNAL_INCREMENT, roundInternal(baseline * (1f - minReductionFraction)))
            return minOf(scoreResult, cap)
        }
        return scoreResult
    }

    override fun scoreFromFeedbacks(feedbacks: List<SetFeedback>, sessionReps: Int): Float? {
        val scored = feedbacks.filter { it != SetFeedback.HURT }
        if (scored.isEmpty()) return null
        if (sessionReps >= REP_OPTIONS.max()
            && scored.any { it == SetFeedback.TOO_HARD }
            && scored.any { it != SetFeedback.TOO_HARD }
        ) return 0f
        return scored.sumOf { feedbackPoints(it) }.toFloat() / scored.size
    }

    fun applyScoreBaseline(baseline: Float, score: Float): Float = when {
        score >= 2.5f  -> weightIncreasedWithFloor(baseline, 1.075f, 2.5f)
        score >= 1.5f  -> weightIncreasedWithFloor(baseline, 1.05f,  1.0f)
        score >= 0.5f  -> weightIncreasedWithFloor(baseline, 1.025f, 0.5f)
        score > -0.5f  -> baseline
        score >= -1.5f -> weightDecreasedWithFloor(baseline, 0.95f, 0.5f)
        else           -> weightDecreasedWithFloor(baseline, 0.90f, 1.0f)
    }

    override fun toOneRepMax(weight: Float, reps: Int): Float = roundInternal(rawToOneRepMax(weight, reps))

    override fun fromOneRepMax(oneRepMax: Float, reps: Int): Float = roundInternal(rawFromOneRepMax(oneRepMax, reps))

    override fun scaleReps(weight: Float, from: Int, to: Int): Float = roundInternal(rawFromOneRepMax(rawToOneRepMax(weight, from), to))

    internal fun rawToOneRepMax(weight: Float, reps: Int): Float {
        if (weight <= 0f || reps <= 1) return weight
        val denom = -2.55f + 4.58f * ln(weight)
        if (denom <= 0f) return weight * (1f + reps / 30f)
        return weight * (1f + (reps - 1).toFloat().pow(0.85f) / denom)
    }

    internal fun rawFromOneRepMax(oneRepMax: Float, reps: Int): Float {
        if (oneRepMax <= 0f || reps <= 1) return oneRepMax
        val k = (reps - 1).toFloat().pow(0.85f)
        val epley = oneRepMax / (1f + reps / 30f)
        var w = epley
        for (i in 0 until 3) {
            val denom = -2.55f + 4.58f * ln(w)
            if (denom <= 0f) return epley
            val fprime = 1f + k * (denom - 4.58f) / (denom * denom)
            if (fprime <= 0f) return epley
            w -= (w * (1f + k / denom) - oneRepMax) / fprime
        }
        return w
    }

    private fun feedbackPoints(feedback: SetFeedback): Int = when (feedback) {
        SetFeedback.RIR_5_PLUS -> 3
        SetFeedback.RIR_2_4   -> 2
        SetFeedback.RIR_0_1   -> 1
        SetFeedback.TOO_HARD  -> -2
        SetFeedback.HURT      -> error("HURT has no points")
    }

    private fun weightIncreasedWithFloor(current: Float, factor: Float, minIncrement: Float): Float {
        val scaled = roundInternal(current * factor)
        val floored = roundInternal(current + minIncrement)
        return maxOf(scaled, floored)
    }

    private fun weightDecreasedWithFloor(current: Float, factor: Float, minDecrement: Float): Float {
        val scaled = roundInternal(current * factor)
        val floored = roundInternal(current - minDecrement)
        return maxOf(INTERNAL_INCREMENT, minOf(scaled, floored))
    }

    private fun weightDecreased(current: Float, factor: Float): Float {
        val scaled = roundInternal(current * factor)
        return if (scaled < current) maxOf(INTERNAL_INCREMENT, scaled) else maxOf(INTERNAL_INCREMENT, roundInternal(current - INTERNAL_INCREMENT))
    }

    private fun roundInternal(weight: Float): Float =
        (weight / INTERNAL_INCREMENT).roundToInt() * INTERNAL_INCREMENT
}
```

- [ ] **Step 3: Update `ProgressionEngineTest.kt` to use `DefaultProgressionEngine`**

Do a search-replace of `ProgressionEngine.` → `DefaultProgressionEngine.` throughout the file. Every test method body stays identical — only the object name prefix on static calls changes. Update the import too:

```kotlin
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
// remove: import io.github.fowles.stochastic_strength.domain.ProgressionEngine (if present)
```

Example of a changed call:
```kotlin
// before:
val score = ProgressionEngine.scoreFromFeedbacks(listOf(SetFeedback.RIR_5_PLUS, ...))
// after:
val score = DefaultProgressionEngine.scoreFromFeedbacks(listOf(SetFeedback.RIR_5_PLUS, ...))
```

The `REP_OPTIONS` references become:
```kotlin
for (reps in DefaultProgressionEngine.REP_OPTIONS) { ... }
```

The two `WeightFormatter` tests at the bottom of the file reference `WeightFormatter` directly, not `ProgressionEngine` — no changes needed there.

- [ ] **Step 4: Update static `ProgressionEngine.` references in `WorkoutPlannerTest.kt`**

The `deriveBaselineFromSessionWeight_roundTrip` test uses `ProgressionEngine.fromOneRepMax`. Change to `DefaultProgressionEngine.fromOneRepMax`:

```kotlin
val pe = PlannedExercise(
    exercise = ex,
    sessionWeight = WeightFormatter.round(
        DefaultProgressionEngine.fromOneRepMax(baseline, sessionReps), WeightUnit.KG
    ),
    sessionReps = sessionReps,
)
```

```kotlin
val expected = WeightFormatter.round(ProgressionEngine.fromOneRepMax(baseline * 1.0f, sessionReps), WeightUnit.KG)
// becomes:
val expected = WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(baseline * 1.0f, sessionReps), WeightUnit.KG)
```

Also update the import in `WorkoutPlannerTest.kt` — add `DefaultProgressionEngine`, remove unused `ProgressionEngine` import if it becomes unused after Task 4.

- [ ] **Step 5: Update static `ProgressionEngine.` references in `WorkoutPlanner.kt`**

The default parameter in `generateWorkout` and the private helper methods still call `ProgressionEngine.xxx` as a static object call. Replace with `DefaultProgressionEngine.xxx`:

```kotlin
fun generateWorkout(sessionReps: Int = DefaultProgressionEngine.REP_OPTIONS.random(random)): WorkoutPlan {
```

```kotlin
private fun weightForExercise(exercise: Exercise, sessionReps: Int): Float {
    val coeff = coefficientSource.get(exercise) ?: return 0f
    if (coeff <= 0f) return 0f
    val baseline = strengths[exercise.primaryMuscle]?.baselineWeight ?: return 0f
    return WeightFormatter.round(
        DefaultProgressionEngine.fromOneRepMax(baseline * coeff, sessionReps),
        weightUnit,
    )
}
```

```kotlin
fun deriveBaselineFromSessionWeight(sessionWeight: Float, pe: PlannedExercise): Float {
    val coeff = coefficientSource.get(pe.exercise) ?: return 0f
    if (coeff <= 0f) return 0f
    return DefaultProgressionEngine.toOneRepMax(sessionWeight, pe.sessionReps) / coeff
}
```

```kotlin
fun recomputeExercise(pe: PlannedExercise, newBaselineKg: Float): PlannedExercise {
    val coeff = coefficientSource.get(pe.exercise) ?: return pe
    if (coeff <= 0f) return pe
    val newWeight = WeightFormatter.round(
        DefaultProgressionEngine.fromOneRepMax(newBaselineKg * coeff, pe.sessionReps),
        weightUnit,
    )
    return pe.copy(
        sessionWeight = newWeight,
        warmupSets = if (pe.exercise.isTimed) emptyList() else computeWarmupSets(newWeight),
    )
}
```

- [ ] **Step 6: Update static `ProgressionEngine.` reference in `WorkoutRepository.kt`**

In `applySessionProgression`:
```kotlin
val newBaseline = DefaultProgressionEngine.computeNextBaseline(current.baselineWeight, allFeedbacks, minReduction, sessionReps)
```

- [ ] **Step 7: Run unit tests to confirm no regression**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
jj commit -m "refactor: extract ProgressionEngine interface, rename impl to DefaultProgressionEngine"
```

---

## Task 4: Wire `ProgressionEngine` into `WorkoutPlanner` and `WorkoutRepository`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [ ] **Step 1: Add `progressionEngine` param to `WorkoutPlanner` constructor**

```kotlin
class WorkoutPlanner(
    val availableExercises: List<Exercise>,
    private val strengths: Map<MuscleGroup, MuscleGroupStrength>,
    val recentHistory: Map<Long, List<WorkoutSet>>,
    val weightUnit: WeightUnit,
    val locationId: Long?,
    private val random: Random = Random.Default,
    private val nowMs: Long = System.currentTimeMillis(),
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
)
```

- [ ] **Step 2: Replace `DefaultProgressionEngine.xxx` instance calls in `WorkoutPlanner`**

The `generateWorkout` default param references `DefaultProgressionEngine.REP_OPTIONS` — leave that as-is since it's a static property, not an instance call. Replace only the calls inside method bodies:

In `weightForExercise`:
```kotlin
return WeightFormatter.round(
    progressionEngine.fromOneRepMax(baseline * coeff, sessionReps),
    weightUnit,
)
```

In `deriveBaselineFromSessionWeight`:
```kotlin
return progressionEngine.toOneRepMax(sessionWeight, pe.sessionReps) / coeff
```

In `recomputeExercise`:
```kotlin
val newWeight = WeightFormatter.round(
    progressionEngine.fromOneRepMax(newBaselineKg * coeff, pe.sessionReps),
    weightUnit,
)
```

- [ ] **Step 3: Add `progressionEngine` param to `WorkoutRepository` constructor**

```kotlin
class WorkoutRepository(
    private val db: AppDatabase,
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
)
```

- [ ] **Step 4: Replace the `DefaultProgressionEngine.computeNextBaseline` call in `WorkoutRepository`**

In `applySessionProgression`:
```kotlin
val newBaseline = progressionEngine.computeNextBaseline(current.baselineWeight, allFeedbacks, minReduction, sessionReps)
```

- [ ] **Step 5: Forward `progressionEngine` in `buildPlanner`**

```kotlin
return WorkoutPlanner(
    availableExercises = available,
    strengths = strengths,
    recentHistory = history,
    weightUnit = weightUnit,
    locationId = locationId,
    coefficientSource = coefficientSource,
    progressionEngine = progressionEngine,
)
```

- [ ] **Step 6: Run all tests**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedAndroidTest
```

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
jj commit -m "refactor: inject ProgressionEngine into WorkoutPlanner and WorkoutRepository"
```

---

## Task 5: `StartingWeightsTest`

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/StartingWeightsTest.kt`

- [ ] **Step 1: Create the test file**

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import org.junit.Assert.assertTrue
import org.junit.Test

class StartingWeightsTest {

    @Test
    fun allCombinationsReturnPositive() {
        for (sex in Sex.entries) {
            for (level in StrengthLevel.entries) {
                for (muscle in MuscleGroup.entries) {
                    val baseline = StartingWeights.baseline(sex, level, muscle)
                    assertTrue("$sex/$level/$muscle should be positive, was $baseline", baseline > 0f)
                }
            }
        }
    }

    @Test
    fun femaleBaselineAtMostMaleForSameLevelAndMuscle() {
        for (level in StrengthLevel.entries) {
            for (muscle in MuscleGroup.entries) {
                val male = StartingWeights.baseline(Sex.MALE, level, muscle)
                val female = StartingWeights.baseline(Sex.FEMALE, level, muscle)
                assertTrue("$level/$muscle: female ($female) should be <= male ($male)", female <= male)
            }
        }
    }

    @Test
    fun higherStrengthLevelMeansHigherBaseline() {
        for (sex in Sex.entries) {
            for (muscle in MuscleGroup.entries) {
                val low = StartingWeights.baseline(sex, StrengthLevel.LOW, muscle)
                val medium = StartingWeights.baseline(sex, StrengthLevel.MEDIUM, muscle)
                val high = StartingWeights.baseline(sex, StrengthLevel.HIGH, muscle)
                assertTrue("$sex/$muscle: MEDIUM ($medium) should exceed LOW ($low)", medium > low)
                assertTrue("$sex/$muscle: HIGH ($high) should exceed MEDIUM ($medium)", high > medium)
            }
        }
    }
}
```

- [ ] **Step 2: Run the tests**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.StartingWeightsTest"
```

Expected: all three tests pass.

- [ ] **Step 3: Commit**

```bash
jj commit -m "test: add StartingWeightsTest covering all sex/level/muscle combinations"
```

---

## Task 6: `WorkoutPlannerTest` — `recomputeExercise` test

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt`

- [ ] **Step 1: Add the test to `WorkoutPlannerTest`**

Add this test after the existing `deriveBaselineFromSessionWeight_roundTrip` test, inside the same `// ── deriveBaseline ──` section:

```kotlin
@Test
fun recomputeExercise_appliesNewBaselineWithCoefficient() {
    val ex = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
    val newBaseline = 120f  // coeff for Barbell Bench Press = 1.0
    val sessionReps = 5
    val p = planner(listOf(ex), strengthsFor(MuscleGroup.CHEST to 100f))

    val pe = PlannedExercise(exercise = ex, sessionReps = sessionReps,
        sessionWeight = WeightFormatter.round(
            DefaultProgressionEngine.fromOneRepMax(100f, sessionReps), WeightUnit.KG))

    val recomputed = p.recomputeExercise(pe, newBaseline)

    val expected = WeightFormatter.round(
        DefaultProgressionEngine.fromOneRepMax(newBaseline * 1.0f, sessionReps), WeightUnit.KG)
    assertEquals("recomputeExercise with coeff=1.0 should apply new baseline directly",
        expected, recomputed.sessionWeight, 0.01f)
    assertTrue("new weight should differ from original when baseline changed",
        recomputed.sessionWeight != pe.sessionWeight)
}
```

Make sure `DefaultProgressionEngine` is imported at the top of the test file:
```kotlin
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
```

- [ ] **Step 2: Run the test**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest.recomputeExercise_appliesNewBaselineWithCoefficient"
```

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
jj commit -m "test: add recomputeExercise test to WorkoutPlannerTest"
```

---

## Task 7: `WorkoutRepositoryTest` — additional instrumented tests

**Files:**
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

- [ ] **Step 1: Add necessary imports to `WorkoutRepositoryTest.kt`**

Add these imports alongside the existing ones:

```kotlin
import io.github.fowles.stochastic_strength.data.model.KnownLocation
import io.github.fowles.stochastic_strength.data.model.LocationExcludedExercise
import org.junit.Assert.assertFalse
```

- [ ] **Step 2: Add the hurt-flag test**

```kotlin
@Test
fun applySessionProgression_setsHurtFlagWhenFeedbackIsHurt() = runBlocking {
    db.userProfileDao().insert(
        UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
    )
    db.exerciseDao().insertAll(listOf(
        Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL)
    ))
    val exerciseId = db.exerciseDao().getActive().first().id
    db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
    val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
    db.workoutSetDao().insert(
        WorkoutSet(
            sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.HURT,
        )
    )

    repository.applySessionProgression(sessionId)

    val exercise = db.exerciseDao().getById(exerciseId)
    assertTrue("hurtFlag must be set when any set has HURT feedback", exercise!!.hurtFlag)
}
```

- [ ] **Step 3: Add the multi-exercise muscle-group aggregation test**

```kotlin
@Test
fun applySessionProgression_aggregatesExercisesInSameMuscleGroupIntoOneLogEntry() = runBlocking {
    db.userProfileDao().insert(
        UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
    )
    db.exerciseDao().insertAll(listOf(
        Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        Exercise(name = "Machine Chest Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.MACHINE),
    ))
    val exercises = db.exerciseDao().getActive()
    val ex1 = exercises.first { it.name == "Barbell Bench Press" }
    val ex2 = exercises.first { it.name == "Machine Chest Press" }
    db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
    val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
    db.workoutSetDao().insert(
        WorkoutSet(sessionId = sessionId, exerciseId = ex1.id, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS)
    )
    db.workoutSetDao().insert(
        WorkoutSet(sessionId = sessionId, exerciseId = ex2.id, setNumber = 1,
            targetWeight = 60f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS)
    )

    repository.applySessionProgression(sessionId)

    val logs = db.baselineChangeLogDao().getForSession(sessionId)
    assertEquals("two exercises in same muscle group should produce one log entry", 1, logs.size)
    assertEquals(MuscleGroup.CHEST, logs[0].muscleGroup)
    assertTrue("combined good feedback should increase baseline", logs[0].newBaseline > 100f)
    // Both exercise feedbacks must appear in the log
    assertEquals("RIR_5_PLUS,RIR_5_PLUS", logs[0].feedbacks)
}
```

- [ ] **Step 4: Add the `minReductionFraction` cap test**

```kotlin
@Test
fun applySessionProgression_capsBaselineWhenExerciseReductionProvided() = runBlocking {
    db.userProfileDao().insert(
        UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
    )
    db.exerciseDao().insertAll(listOf(
        Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL)
    ))
    val exerciseId = db.exerciseDao().getActive().first().id
    db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
    val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
    db.workoutSetDao().insert(
        WorkoutSet(sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS)
    )

    // 10% reduction cap: new baseline must not exceed 100 × (1 − 0.10) = 90 kg
    repository.applySessionProgression(sessionId, exerciseReductions = mapOf(exerciseId to 0.10f))

    val strength = db.muscleGroupStrengthDao().get(MuscleGroup.CHEST)!!
    assertTrue(
        "10% reduction cap should hold baseline at or below 90 kg, got ${strength.baselineWeight}",
        strength.baselineWeight <= 90.5f
    )
    val log = db.baselineChangeLogDao().getForSession(sessionId).single()
    assertEquals(0.10f, log.minReductionFraction!!, 0.001f)
}
```

- [ ] **Step 5: Add the `buildPlanner` location-exclusion test**

```kotlin
@Test
fun buildPlanner_excludesExercisesMarkedForLocation() = runBlocking {
    db.exerciseDao().insertAll(listOf(
        Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        Exercise(name = "Push-Up",             primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BODYWEIGHT),
    ))
    val exercises = db.exerciseDao().getActive()
    val barbellId = exercises.first { it.name == "Barbell Bench Press" }.id

    val locationId = db.knownLocationDao().insert(KnownLocation(name = "Home", latitude = 0.0, longitude = 0.0))
    db.locationExcludedExerciseDao().insert(LocationExcludedExercise(locationId, barbellId))

    val planner = repository.buildPlanner(locationId = locationId, weightUnit = WeightUnit.KG)

    assertFalse("excluded exercise must not appear in planner",
        planner.availableExercises.any { it.id == barbellId })
    assertTrue("non-excluded exercise must be available",
        planner.availableExercises.any { it.name == "Push-Up" })
}
```

- [ ] **Step 6: Run all instrumented tests**

```bash
./gradlew :app:connectedAndroidTest
```

Expected: all tests pass, including the four new ones.

- [ ] **Step 7: Run full test suite to confirm no regressions**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedAndroidTest
```

Expected: all tests pass.

- [ ] **Step 8: Commit**

```bash
jj commit -m "test: add WorkoutRepository instrumented tests for hurt flag, aggregation, reduction cap, and buildPlanner exclusion"
```
