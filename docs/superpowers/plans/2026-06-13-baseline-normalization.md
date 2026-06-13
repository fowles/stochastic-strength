# Baseline Normalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-attribute accumulated per-exercise coefficient drift back into per-muscle-group baselines by introducing a `BaselineNormalizer` pipeline that runs after every coefficient recompute, gated by an absolute baseline-movement threshold.

**Architecture:** A new `BaselineNormalizer` interface mirrors the existing `CoefficientHeuristic` pattern. The sole implementation, `SeedNormalizer`, finds the per-muscle-group scale `m = Σ(c·s)/Σ(c²)` that minimizes RMSE of scaled coefficients against seed coefficients, using exercises the user has actually performed. The runner inside `WorkoutRepository` applies a 2 kg / 5 lb absolute threshold on the rounded baseline change before writing both a `BaselineChangeLog` (new `NORMALIZATION` reason) and a per-in-group-exercise `CoefficientChangeLog` row. Both `applySessionProgression` and the backfill-on-upgrade path call a single new `recomputeDerivedState` entry point so neither pass can be silently skipped.

**Tech Stack:** Kotlin, Android, Room (DB), JUnit 4 (JVM tests in `src/test/`), AndroidJUnit4 (instrumented tests in `src/androidTest/`).

**Spec:** `docs/superpowers/specs/2026-06-13-baseline-normalization-design.md`

---

## File Structure

**New files:**

- `app/src/main/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizer.kt` — interface + `BaselineNormalizationInput`, `BaselineNormalizationProposal`, `ExerciseCoefficientSnapshot` data classes.
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/SeedNormalizer.kt` — single implementation of `BaselineNormalizer`.
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizationThreshold.kt` — `forUnit(WeightUnit) → Float` constant lookup.
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/SeedNormalizerTest.kt` — pure JVM unit tests for the math.
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizationThresholdTest.kt` — JVM unit test for the unit lookup.

**Modified files:**

- `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeReason.kt` — add `NORMALIZATION` enum value.
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` — add `normalizers` constructor param; add `buildNormalizationInput`, `applyBaselineNormalization`, `recomputeDerivedState`; replace the trailing `recomputeCoefficients(...)` call inside `applySessionProgression` with `recomputeDerivedState(...)`.
- `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt` — register `SeedNormalizer()` in the `WorkoutRepository` builder; switch backfill call site from `recomputeCoefficients()` to `recomputeDerivedState()`.
- `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt` — add instrumented tests for the new runner, the combined entry point, and the backfill regression guard.

---

### Task 1: Add `NORMALIZATION` enum value

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeReason.kt`

`BaselineChangeReason` is stored as `enum.name` via the existing `Converters` type adapter, so adding a value does not require a Room migration. No schema bump.

- [ ] **Step 1: Add the enum value**

Replace the file contents with:

```kotlin
package io.github.fowles.stochastic_strength.data.model

enum class BaselineChangeReason {
    MANUAL_OVERRIDE,
    PROGRESSION,
    NORMALIZATION,
}
```

- [ ] **Step 2: Build to verify the project still compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeReason.kt
git commit -m "feat(data): add BaselineChangeReason.NORMALIZATION"
```

---

### Task 2: Add `BaselineNormalizationThreshold`

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizationThreshold.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizationThresholdTest.kt`

The threshold is the absolute baseline movement (in the user's stored unit) below which a normalization proposal is dropped. 2 kg / 5 lb chosen to match the smallest common plate increment.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizationThresholdTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class BaselineNormalizationThresholdTest {

    @Test
    fun forUnit_kg_returns2() {
        assertEquals(2f, BaselineNormalizationThreshold.forUnit(WeightUnit.KG), 0f)
    }

    @Test
    fun forUnit_lb_returns5() {
        assertEquals(5f, BaselineNormalizationThreshold.forUnit(WeightUnit.LBS), 0f)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.BaselineNormalizationThresholdTest"`
Expected: FAIL with "Unresolved reference: BaselineNormalizationThreshold".

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizationThreshold.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.WeightUnit

object BaselineNormalizationThreshold {
    fun forUnit(unit: WeightUnit): Float = when (unit) {
        WeightUnit.KG -> 2f
        WeightUnit.LBS -> 5f
    }
}
```

- [ ] **Step 4: Run to verify the tests pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.BaselineNormalizationThresholdTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizationThreshold.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizationThresholdTest.kt
git commit -m "feat(domain): add BaselineNormalizationThreshold"
```

---

### Task 3: Add `BaselineNormalizer` interface and data classes

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizer.kt`

Interface-only task: introduces the contract used by the runner (Task 8) and the `SeedNormalizer` implementation (Tasks 4–6). Mirrors `CoefficientHeuristic.kt` in shape.

- [ ] **Step 1: Create the file**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizer.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

data class ExerciseCoefficientSnapshot(
    val exercise: Exercise,
    val seedCoefficient: Float,
    val currentCoefficient: Float,
)

data class BaselineNormalizationInput(
    val sets: List<WorkoutSet>,
    val exercises: List<ExerciseCoefficientSnapshot>,
    val baselines: Map<MuscleGroup, Float>,
)

data class BaselineNormalizationProposal(
    val muscleGroup: MuscleGroup,
    val scale: Float,
    val metadata: String? = null,
)

interface BaselineNormalizer {
    val name: String
    fun compute(input: BaselineNormalizationInput): List<BaselineNormalizationProposal>
}
```

- [ ] **Step 2: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/BaselineNormalizer.kt
git commit -m "feat(domain): add BaselineNormalizer interface"
```

---

### Task 4: `SeedNormalizer` — empty / insufficient-data cases (TDD)

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/SeedNormalizer.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/SeedNormalizerTest.kt`

Starts the SeedNormalizer with the trivial cases — anything that should produce no proposal. Establishes the file, the test fixture helpers, and the "return empty" early exits.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/SeedNormalizerTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedNormalizerTest {

    private val normalizer = SeedNormalizer()

    private fun exercise(id: Long, name: String, muscle: MuscleGroup) = Exercise(
        id = id,
        name = name,
        primaryMuscle = muscle,
        equipment = Equipment.BARBELL,
    )

    private fun snapshot(id: Long, muscle: MuscleGroup, seed: Float, current: Float) =
        ExerciseCoefficientSnapshot(
            exercise = exercise(id, "Ex$id", muscle),
            seedCoefficient = seed,
            currentCoefficient = current,
        )

    private fun set(exerciseId: Long) = WorkoutSet(
        sessionId = 1L,
        exerciseId = exerciseId,
        setNumber = 1,
        targetWeight = 80f,
        targetReps = 5,
        feedback = SetFeedback.RIR_2_4,
    )

    @Test
    fun compute_returnsEmpty_whenNoSetsAndNoExercises() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = emptyList(),
            exercises = emptyList(),
            baselines = emptyMap(),
        ))
        assertTrue(out.isEmpty())
    }

    @Test
    fun compute_returnsEmpty_whenNoExercisesObserved() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = emptyList(),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.0f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.85f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertTrue(out.isEmpty())
    }

    @Test
    fun compute_skipsGroup_whenFewerThanTwoObservedQualifyingExercises() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.1f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.9f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertTrue(out.isEmpty())
    }

    @Test
    fun compute_dropsObservedExercisesWithZeroCurrentCoefficient() {
        // Two observed exercises but one has currentCoefficient = 0 -> only n=1 qualifies -> skip.
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.1f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.0f, current = 0.0f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertEquals(0, out.size)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SeedNormalizerTest"`
Expected: FAIL with "Unresolved reference: SeedNormalizer".

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/SeedNormalizer.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

class SeedNormalizer : BaselineNormalizer {
    override val name: String = "seed-normalizer"

    override fun compute(input: BaselineNormalizationInput): List<BaselineNormalizationProposal> {
        val observed = input.sets.mapTo(mutableSetOf()) { it.exerciseId }
        val byMuscle = input.exercises.groupBy { it.exercise.primaryMuscle }
        return byMuscle.mapNotNull { (muscle, snaps) ->
            val qualifying = snaps.filter {
                it.exercise.id in observed && it.currentCoefficient > 0f
            }
            if (qualifying.size < 2) return@mapNotNull null
            // Real math arrives in Task 5 — for now, emit a placeholder that the existing tests don't require.
            null
        }
    }
}
```

- [ ] **Step 4: Run to verify the tests pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SeedNormalizerTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/SeedNormalizer.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/SeedNormalizerTest.kt
git commit -m "feat(domain): scaffold SeedNormalizer with no-op cases"
```

---

### Task 5: `SeedNormalizer` — least-squares math (TDD)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/SeedNormalizer.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/SeedNormalizerTest.kt`

Drives the actual `m = Σ(c·s) / Σ(c²)` math via tests that pin direction (drift up → m<1, drift down → m>1), the no-drift case, and a hand-computed optimal answer. Also asserts independence between muscle groups.

- [ ] **Step 1: Append the failing tests**

Append to `SeedNormalizerTest.kt`, inside the class:

```kotlin
    @Test
    fun compute_returnsMNearOne_whenCoefficientsMatchSeeds() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.0f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.85f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertEquals(1, out.size)
        assertEquals(MuscleGroup.CHEST, out[0].muscleGroup)
        assertEquals(1.0f, out[0].scale, 1e-4f)
    }

    @Test
    fun compute_returnsMLessThanOne_whenCoefficientsDriftedAboveSeed() {
        // c > s everywhere -> Σ(c·s) < Σ(c²) -> m < 1 -> scaling coefficients DOWN toward seed,
        // baseline = old / m > old (the system thinks the user got stronger).
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.10f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.95f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertEquals(1, out.size)
        assertTrue("m should be < 1, got ${out[0].scale}", out[0].scale < 1f)
    }

    @Test
    fun compute_returnsMGreaterThanOne_whenCoefficientsDriftedBelowSeed() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 0.90f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.75f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertEquals(1, out.size)
        assertTrue("m should be > 1, got ${out[0].scale}", out[0].scale > 1f)
    }

    @Test
    fun compute_solvesLeastSquaresOptimally_handComputed() {
        // c = [1.10, 0.95], s = [1.00, 0.85]
        // num = 1.10*1.00 + 0.95*0.85 = 1.10 + 0.8075 = 1.9075
        // den = 1.10^2 + 0.95^2 = 1.21 + 0.9025 = 2.1125
        // m = 1.9075 / 2.1125 ≈ 0.9029586
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.00f, current = 1.10f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.95f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        assertEquals(1, out.size)
        assertEquals(0.9029586f, out[0].scale, 1e-4f)
    }

    @Test
    fun compute_handlesMuscleGroupsIndependently() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L), set(3L), set(4L)),
            exercises = listOf(
                // CHEST: drifted up
                snapshot(1L, MuscleGroup.CHEST, seed = 1.0f, current = 1.10f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.95f),
                // BACK: drifted down
                snapshot(3L, MuscleGroup.BACK, seed = 1.0f, current = 0.90f),
                snapshot(4L, MuscleGroup.BACK, seed = 0.60f, current = 0.50f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f, MuscleGroup.BACK to 80f),
        ))
        assertEquals(2, out.size)
        val byMuscle = out.associateBy { it.muscleGroup }
        assertTrue(byMuscle.getValue(MuscleGroup.CHEST).scale < 1f)
        assertTrue(byMuscle.getValue(MuscleGroup.BACK).scale > 1f)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SeedNormalizerTest"`
Expected: 5 of the new tests FAIL (the existing 4 still pass).

- [ ] **Step 3: Replace `compute` with the real implementation**

Replace the `compute` body in `SeedNormalizer.kt` with:

```kotlin
    override fun compute(input: BaselineNormalizationInput): List<BaselineNormalizationProposal> {
        val observed = input.sets.mapTo(mutableSetOf()) { it.exerciseId }
        val byMuscle = input.exercises.groupBy { it.exercise.primaryMuscle }
        return byMuscle.mapNotNull { (muscle, snaps) ->
            val qualifying = snaps.filter {
                it.exercise.id in observed && it.currentCoefficient > 0f
            }
            if (qualifying.size < 2) return@mapNotNull null
            val num = qualifying.sumOf { (it.currentCoefficient * it.seedCoefficient).toDouble() }
            val den = qualifying.sumOf { (it.currentCoefficient * it.currentCoefficient).toDouble() }
            if (den <= 0.0) return@mapNotNull null
            val m = (num / den).toFloat()
            BaselineNormalizationProposal(
                muscleGroup = muscle,
                scale = m,
                metadata = null,
            )
        }
    }
```

- [ ] **Step 4: Run to verify all tests pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SeedNormalizerTest"`
Expected: PASS, 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/SeedNormalizer.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/SeedNormalizerTest.kt
git commit -m "feat(domain): compute least-squares scale in SeedNormalizer"
```

---

### Task 6: `SeedNormalizer` — metadata (TDD)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/SeedNormalizer.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/SeedNormalizerTest.kt`

Adds the metadata string so downstream `CoefficientChangeLog` / `BaselineChangeLog` rows are auditable: `n=<count>, m=<scale>, rmse_before=<…>, rmse_after=<…>`.

- [ ] **Step 1: Append the failing test**

Append to `SeedNormalizerTest.kt`, inside the class:

```kotlin
    @Test
    fun compute_metadataContainsNAndMAndRmseBeforeAndAfter() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.00f, current = 1.10f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.95f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        val meta = out.single().metadata
        assertTrue("metadata should be non-null", meta != null)
        assertTrue("metadata should contain n=2: $meta", meta!!.contains("n=2"))
        assertTrue("metadata should contain m=: $meta", meta.contains("m="))
        assertTrue("metadata should contain rmse_before=: $meta", meta.contains("rmse_before="))
        assertTrue("metadata should contain rmse_after=: $meta", meta.contains("rmse_after="))
    }

    @Test
    fun compute_metadataRmseAfterIsLowerThanRmseBefore_whenDriftExists() {
        val out = normalizer.compute(BaselineNormalizationInput(
            sets = listOf(set(1L), set(2L)),
            exercises = listOf(
                snapshot(1L, MuscleGroup.CHEST, seed = 1.00f, current = 1.10f),
                snapshot(2L, MuscleGroup.CHEST, seed = 0.85f, current = 0.95f),
            ),
            baselines = mapOf(MuscleGroup.CHEST to 100f),
        ))
        val meta = out.single().metadata!!
        val before = Regex("rmse_before=([0-9.]+)").find(meta)!!.groupValues[1].toFloat()
        val after = Regex("rmse_after=([0-9.]+)").find(meta)!!.groupValues[1].toFloat()
        assertTrue("after $after should be < before $before", after < before)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SeedNormalizerTest"`
Expected: 2 new tests FAIL (existing 9 still pass).

- [ ] **Step 3: Update `compute` to emit metadata**

Replace `SeedNormalizer.compute` with:

```kotlin
    override fun compute(input: BaselineNormalizationInput): List<BaselineNormalizationProposal> {
        val observed = input.sets.mapTo(mutableSetOf()) { it.exerciseId }
        val byMuscle = input.exercises.groupBy { it.exercise.primaryMuscle }
        return byMuscle.mapNotNull { (muscle, snaps) ->
            val qualifying = snaps.filter {
                it.exercise.id in observed && it.currentCoefficient > 0f
            }
            if (qualifying.size < 2) return@mapNotNull null
            val num = qualifying.sumOf { (it.currentCoefficient * it.seedCoefficient).toDouble() }
            val den = qualifying.sumOf { (it.currentCoefficient * it.currentCoefficient).toDouble() }
            if (den <= 0.0) return@mapNotNull null
            val m = (num / den).toFloat()
            val rmseBefore = rmse(qualifying) { c, s -> c - s }
            val rmseAfter = rmse(qualifying) { c, s -> m * c - s }
            BaselineNormalizationProposal(
                muscleGroup = muscle,
                scale = m,
                metadata = "n=${qualifying.size}, m=${formatFloat(m)}, " +
                           "rmse_before=${formatFloat(rmseBefore)}, rmse_after=${formatFloat(rmseAfter)}",
            )
        }
    }

    private inline fun rmse(
        qs: List<ExerciseCoefficientSnapshot>,
        residual: (Float, Float) -> Float,
    ): Float {
        val sumSq = qs.sumOf {
            val r = residual(it.currentCoefficient, it.seedCoefficient)
            (r * r).toDouble()
        }
        return kotlin.math.sqrt(sumSq / qs.size).toFloat()
    }

    private fun formatFloat(v: Float): String = "%.4f".format(v)
```

- [ ] **Step 4: Run all unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.SeedNormalizerTest"`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/SeedNormalizer.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/SeedNormalizerTest.kt
git commit -m "feat(domain): emit n/m/rmse metadata from SeedNormalizer"
```

---

### Task 7: Wire `normalizers` into `WorkoutRepository`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

Adds the constructor parameter so the new normalizers list reaches the repo, and adds the `buildNormalizationInput` builder. No behavior change yet — Task 8 wires it into `applyBaselineNormalization`.

- [ ] **Step 1: Add the `normalizers` parameter**

In `WorkoutRepository.kt`, change the constructor signature:

```kotlin
class WorkoutRepository(
    private val db: AppDatabase,
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
    private val heuristics: List<CoefficientHeuristic> = listOf(),
    private val normalizers: List<BaselineNormalizer> = listOf(),
) {
```

- [ ] **Step 2: Add the `buildNormalizationInput` helper**

In the same file, add this method near `buildCoefficientInput` (just below it is a natural spot):

```kotlin
    internal suspend fun buildNormalizationInput(): BaselineNormalizationInput {
        val allExercises = db.exerciseDao().getAll()
        val sets = db.workoutSetDao().getAll()
        val baselines = db.muscleGroupStrengthDao().getAll()
            .associate { it.muscleGroup to it.baselineWeight }
        val latestCoefs = db.coefficientChangeLogDao().getLatestPerExercise()
            .associate { it.exerciseId to it.coefficient }
        val snapshots = allExercises.map { ex ->
            val seed = coefficientSource.get(ex) ?: 0f
            val current = latestCoefs[ex.id] ?: seed
            ExerciseCoefficientSnapshot(ex, seed, current)
        }
        return BaselineNormalizationInput(sets = sets, exercises = snapshots, baselines = baselines)
    }
```

- [ ] **Step 3: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Existing test callers omit `normalizers`; the default `listOf()` keeps them compiling.)

- [ ] **Step 4: Run the existing unit test suite to confirm nothing regressed**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — every existing test still passes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt
git commit -m "feat(domain): add normalizers and buildNormalizationInput to WorkoutRepository"
```

---

### Task 8: `applyBaselineNormalization` runner + tests

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

Implements the runner that turns proposals into log writes. Threshold-gated, unit-aware rounding, derived `mEffective` so the session-weight invariant holds exactly.

- [ ] **Step 1: Add the failing instrumented tests**

Append the following test methods to `WorkoutRepositoryTest`. The fake normalizer pattern mirrors the existing `CoefficientHeuristic` fakes already in this file.

```kotlin
    private fun fakeNormalizer(name: String, proposals: List<BaselineNormalizationProposal>) =
        object : BaselineNormalizer {
            override val name: String = name
            override fun compute(input: BaselineNormalizationInput) = proposals
        }

    @Test
    fun applyBaselineNormalization_writesNothing_whenNoNormalizersRegistered() = runBlocking {
        seedChestSession()
        val repo = WorkoutRepository(db, normalizers = emptyList())

        repo.applyBaselineNormalization(asOf = 1_000L, sessionId = 1L)

        val baselineRows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(0, baselineRows.size)
    }

    @Test
    fun applyBaselineNormalization_writesNothing_whenBelowThreshold() = runBlocking {
        val (_, sessionId) = seedChestSession()
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        // m = 0.99 -> new baseline ≈ 101.01 -> rounded to 101 -> |101-100|=1kg < 2kg threshold
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.99f, metadata = "test")
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applyBaselineNormalization(asOf = 2_000L, sessionId = sessionId)

        val baselineRows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(0, baselineRows.size)
        // baseline unchanged
        assertEquals(100f, db.muscleGroupStrengthDao().get(MuscleGroup.CHEST)!!.baselineWeight)
    }

    @Test
    fun applyBaselineNormalization_writesBaselineAndCoefficientLogs_whenAboveThreshold() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Incline Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val benchId = db.exerciseDao().getActive().first { it.name == "Barbell Bench Press" }.id
        val inclineId = db.exerciseDao().getActive().first { it.name == "Incline Barbell Bench Press" }.id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        // m = 0.90 -> raw new = 100 / 0.90 ≈ 111.11 -> rounded to 111 -> 11 kg > 2 kg threshold
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = "n=2, m=0.9000")
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applyBaselineNormalization(asOf = 3_000L, sessionId = sessionId)

        val baselineRows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(1, baselineRows.size)
        with(baselineRows[0]) {
            assertEquals(MuscleGroup.CHEST, muscleGroup)
            assertEquals(100f, previousBaseline)
            assertTrue("new baseline should be greater than old (m<1 raises baseline)", newBaseline > 100f)
            assertEquals(sessionId, this.sessionId)
            assertEquals(3_000L, timestamp)
        }
        val coefRows = db.coefficientChangeLogDao().getAll()
            .filter { it.heuristicName == "baseline_normalization" }
        // Both chest exercises (bench + incline) have defined seed coefficients, so both get scaled.
        assertEquals(2, coefRows.size)
        assertTrue(coefRows.any { it.exerciseId == benchId })
        assertTrue(coefRows.any { it.exerciseId == inclineId })
        assertEquals("n=2, m=0.9000", coefRows[0].heuristicMetadata)
    }

    @Test
    fun applyBaselineNormalization_preservesSessionWeightWithinRoundingTolerance() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Incline Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val benchId = db.exerciseDao().getActive().first { it.name == "Barbell Bench Press" }.id
        val inclineId = db.exerciseDao().getActive().first { it.name == "Incline Barbell Bench Press" }.id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))

        // Capture seed coefficients (these become the "current" coefficients used by the runner).
        val benchSeed = ExerciseCoefficients.byName.getValue("Barbell Bench Press")
        val inclineSeed = ExerciseCoefficients.byName.getValue("Incline Barbell Bench Press")
        val benchWeightBefore = 100f * benchSeed
        val inclineWeightBefore = 100f * inclineSeed

        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applyBaselineNormalization(asOf = 4_000L, sessionId = sessionId)

        val newBaseline = db.muscleGroupStrengthDao().get(MuscleGroup.CHEST)!!.baselineWeight
        val coefs = db.coefficientChangeLogDao().getLatestPerExercise().associateBy { it.exerciseId }
        val benchWeightAfter = newBaseline * coefs.getValue(benchId).coefficient
        val inclineWeightAfter = newBaseline * coefs.getValue(inclineId).coefficient
        // Session weights are preserved exactly (mEffective is derived from the rounded baseline).
        assertEquals(benchWeightBefore, benchWeightAfter, 1e-3f)
        assertEquals(inclineWeightBefore, inclineWeightAfter, 1e-3f)
    }

    @Test
    fun applyBaselineNormalization_scalesUnobservedExercisesInGroup() = runBlocking {
        // Setup: two exercises in CHEST, only one is "observed" (has a WorkoutSet).
        // The runner doesn't look at the input set / output set distinction directly — it just scales
        // every CHEST exercise with a defined coefficient. That's what we're asserting.
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Incline Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val benchId = db.exerciseDao().getActive().first { it.name == "Barbell Bench Press" }.id
        val inclineId = db.exerciseDao().getActive().first { it.name == "Incline Barbell Bench Press" }.id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        // Only bench has any WorkoutSet on record.
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = benchId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
        ))

        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = null)
        ))
        WorkoutRepository(db, normalizers = listOf(normalizer))
            .applyBaselineNormalization(asOf = 5_000L, sessionId = sessionId)

        val coefRows = db.coefficientChangeLogDao().getAll()
            .filter { it.heuristicName == "baseline_normalization" }
        assertEquals(2, coefRows.size)
        assertTrue(coefRows.any { it.exerciseId == benchId })
        assertTrue(coefRows.any { it.exerciseId == inclineId })
    }
```

You will also need this import block near the top of `WorkoutRepositoryTest.kt` (kept separate so it's clear what's new):

```kotlin
import io.github.fowles.stochastic_strength.domain.BaselineNormalizationInput
import io.github.fowles.stochastic_strength.domain.BaselineNormalizationProposal
import io.github.fowles.stochastic_strength.domain.BaselineNormalizer
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
```

- [ ] **Step 2: Run the instrumented suite to confirm the tests fail with the expected error**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest"`
Expected: The five new tests FAIL with "Unresolved reference: applyBaselineNormalization".

- [ ] **Step 3: Implement `applyBaselineNormalization` in `WorkoutRepository`**

Add this method to `WorkoutRepository.kt`, after `recomputeCoefficients`:

```kotlin
    internal suspend fun applyBaselineNormalization(asOf: Long, sessionId: Long) {
        if (normalizers.isEmpty()) return
        val input = buildNormalizationInput()
        val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
        val threshold = BaselineNormalizationThreshold.forUnit(weightUnit)

        val proposals = normalizers.flatMap { it.compute(input) }
        if (proposals.isEmpty()) return

        db.withTransaction {
            val latestCoefByExercise = db.coefficientChangeLogDao().getLatestPerExercise()
                .associateBy { it.exerciseId }
            for (proposal in proposals) {
                val oldBaseline = input.baselines[proposal.muscleGroup] ?: continue
                if (oldBaseline <= 0f || proposal.scale <= 0f) continue
                val rawNew = oldBaseline / proposal.scale
                val newBaseline = WeightFormatter.round(rawNew, weightUnit)
                if (kotlin.math.abs(newBaseline - oldBaseline) < threshold) continue
                if (newBaseline <= 0f) continue
                val mEffective = oldBaseline / newBaseline

                db.muscleGroupStrengthDao().upsert(
                    MuscleGroupStrength(muscleGroup = proposal.muscleGroup, baselineWeight = newBaseline)
                )
                db.baselineChangeLogDao().insert(
                    BaselineChangeLog(
                        sessionId = sessionId,
                        muscleGroup = proposal.muscleGroup,
                        previousBaseline = oldBaseline,
                        newBaseline = newBaseline,
                        changeReason = BaselineChangeReason.NORMALIZATION,
                        timestamp = asOf,
                    )
                )

                val inGroup = input.exercises.filter {
                    it.exercise.primaryMuscle == proposal.muscleGroup && it.currentCoefficient > 0f
                }
                for (snap in inGroup) {
                    val newCoef = snap.currentCoefficient * mEffective
                    db.coefficientChangeLogDao().insert(
                        CoefficientChangeLog(
                            exerciseId = snap.exercise.id,
                            previousCoefficient = latestCoefByExercise[snap.exercise.id]?.coefficient
                                ?: snap.currentCoefficient,
                            coefficient = newCoef,
                            heuristicName = "baseline_normalization",
                            heuristicMetadata = proposal.metadata,
                            computedAt = asOf,
                        )
                    )
                }
            }
        }
    }
```

- [ ] **Step 4: Run the instrumented suite to verify pass**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest"`
Expected: BUILD SUCCESSFUL — all previously passing tests still pass; the five new tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt
git commit -m "feat(domain): add applyBaselineNormalization runner"
```

---

### Task 9: `recomputeDerivedState` entry point + integrate into `applySessionProgression`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

Introduces the single public entry point that both production callers will use. This is the regression guard against the "backfill forgot to run one of the passes" bug class.

- [ ] **Step 1: Add the failing tests**

Append to `WorkoutRepositoryTest`:

```kotlin
    @Test
    fun recomputeDerivedState_runsCoefficientHeuristicsAndNormalizers() = runBlocking {
        val (exerciseId, sessionId) = seedChestSession()
        // Heuristic always emits 0.85 for the seeded exercise.
        val heuristic = object : CoefficientHeuristic {
            override val name = "test-heuristic"
            override fun compute(input: CoefficientComputationInput) =
                input.sets.map { it.exerciseId }.distinct().map { CoefficientResult(it, 0.85f) }
        }
        // Normalizer emits a proposal that clears the 2 kg threshold (m=0.90 on baseline 100 → new=111).
        val normalizer = fakeNormalizer("test-normalizer", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = "test")
        ))
        val repo = WorkoutRepository(db,
            heuristics = listOf(heuristic),
            normalizers = listOf(normalizer),
        )

        repo.recomputeDerivedState(asOf = 6_000L, sessionId = sessionId)

        val heuristicRows = db.coefficientChangeLogDao().getAll()
            .filter { it.heuristicName == "test-heuristic" }
        assertEquals(1, heuristicRows.size)
        val normRows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(1, normRows.size)
    }

    @Test
    fun recomputeDerivedState_fallsBackToMostRecentSession_whenSessionIdNotProvided() = runBlocking {
        val (_, latestSessionId) = seedChestSession()
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.recomputeDerivedState(asOf = 7_000L, sessionId = null)

        val rows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(1, rows.size)
        assertEquals(latestSessionId, rows[0].sessionId)
    }

    @Test
    fun recomputeDerivedState_isNoOp_whenNoSessionsExistAndSessionIdNotProvided() = runBlocking {
        // Empty DB — no sessions, no exercises, nothing.
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.recomputeDerivedState(asOf = 8_000L, sessionId = null)

        val rows = db.baselineChangeLogDao().getAll()
        assertEquals(0, rows.size)
    }

    @Test
    fun applySessionProgression_triggersNormalizationViaDerivedState() = runBlocking {
        // End-to-end: progression + heuristics + normalizer in one applySessionProgression call.
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val exerciseId = db.exerciseDao().getActive().first().id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
            targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
        ))
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.90f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applySessionProgression(sessionId)

        val rows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertEquals(1, rows.size)
        assertEquals(sessionId, rows[0].sessionId)
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest"`
Expected: New tests FAIL with "Unresolved reference: recomputeDerivedState" (and `applySessionProgression_triggersNormalizationViaDerivedState` fails because nothing wires normalization in yet).

- [ ] **Step 3: Add `recomputeDerivedState` and route `applySessionProgression` through it**

Add to `WorkoutRepository.kt` (place just below `recomputeCoefficients`):

```kotlin
    suspend fun recomputeDerivedState(asOf: Long? = null, sessionId: Long? = null) {
        recomputeCoefficients(asOf = asOf)
        val resolvedSessionId = sessionId
            ?: db.workoutSessionDao().getAll()
                .maxByOrNull { it.endTime ?: it.startTime }?.id
            ?: return
        applyBaselineNormalization(
            asOf = asOf ?: System.currentTimeMillis(),
            sessionId = resolvedSessionId,
        )
    }
```

Replace the trailing `recomputeCoefficients(asOf = triggerTime)` line inside `applySessionProgression` with:

```kotlin
        recomputeDerivedState(asOf = triggerTime, sessionId = sessionId)
```

- [ ] **Step 4: Run the instrumented suite**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest"`
Expected: BUILD SUCCESSFUL — all old tests still pass; four new tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt
git commit -m "feat(domain): add recomputeDerivedState entry point"
```

---

### Task 10: Manual override path is unaffected

**Files:**
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

A focused regression test pinning the intended behavior that manual baseline overrides do **not** trigger normalization. Production code already has this property because `applyManualBaselineOverrides` never calls `recomputeDerivedState` — this test guards against a future refactor accidentally adding such a call.

- [ ] **Step 1: Add the test**

Append to `WorkoutRepositoryTest`:

```kotlin
    @Test
    fun applyManualBaselineOverrides_doesNotTriggerNormalization() = runBlocking {
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
        val normalizer = fakeNormalizer("test", listOf(
            BaselineNormalizationProposal(MuscleGroup.CHEST, scale = 0.50f, metadata = null)
        ))
        val repo = WorkoutRepository(db, normalizers = listOf(normalizer))

        repo.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.CHEST to 120f))

        // Only the MANUAL_OVERRIDE row should exist — no NORMALIZATION row.
        val rows = db.baselineChangeLogDao().getAll()
        assertEquals(1, rows.size)
        assertEquals(BaselineChangeReason.MANUAL_OVERRIDE, rows[0].changeReason)
    }
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest.applyManualBaselineOverrides_doesNotTriggerNormalization"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt
git commit -m "test(domain): pin manual override does not trigger normalization"
```

---

### Task 11: Wire `SeedNormalizer` in `StochasticStrengthApp`, route backfill through `recomputeDerivedState`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

Production wiring. Also adds the targeted "backfill runs both passes" regression test using the real `SeedNormalizer` and `EstCoefConsensusHeuristic`. This is the explicit guard the user called out — if either pass gets accidentally skipped in the backfill flow, this test fails.

- [ ] **Step 1: Update `StochasticStrengthApp`**

Modify `StochasticStrengthApp.kt`. Add the import:

```kotlin
import io.github.fowles.stochastic_strength.domain.SeedNormalizer
```

Change the `workoutRepository` initializer:

```kotlin
    val workoutRepository: WorkoutRepository by lazy {
        WorkoutRepository(
            database,
            heuristics = listOf(EstCoefConsensusHeuristic()),
            normalizers = listOf(SeedNormalizer()),
        )
    }
```

Change the backfill call site:

```kotlin
        applicationScope.launch(Dispatchers.IO) {
            val profile = database.userProfileDao().getProfile() ?: return@launch
            if (profile.actualRepsBackfilled) return@launch
            ActualRepsBackfill(database, profile.weightUnit).run()
            workoutRepository.recomputeDerivedState()
            database.userProfileDao().insert(profile.copy(actualRepsBackfilled = true))
        }
```

(Only the `recomputeDerivedState()` call changes — replacing the previous `recomputeCoefficients()`.)

- [ ] **Step 2: Build to verify compile**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Add the backfill regression test**

Append to `WorkoutRepositoryTest`. This uses the *real* `SeedNormalizer` and `EstCoefConsensusHeuristic` and constructs a CHEST muscle group where two exercises have drifted above seed.

```kotlin
    @Test
    fun recomputeDerivedState_realStack_writesBothCoefficientHeuristicAndNormalizationLogs() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Incline Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val benchId = db.exerciseDao().getActive().first { it.name == "Barbell Bench Press" }.id
        val inclineId = db.exerciseDao().getActive().first { it.name == "Incline Barbell Bench Press" }.id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))

        // Pre-seed inflated current coefficients (well above seed) by writing logs directly. This simulates
        // the state that arises from drift accumulated over many real sessions, which would otherwise take
        // a long sequence of sessions to produce in a test.
        val pastSessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L, endTime = 2000L))
        db.coefficientChangeLogDao().insert(CoefficientChangeLog(
            exerciseId = benchId, coefficient = 1.20f, heuristicName = "preseed",
            heuristicMetadata = null, computedAt = 1500L,
        ))
        db.coefficientChangeLogDao().insert(CoefficientChangeLog(
            exerciseId = inclineId, coefficient = 1.05f, heuristicName = "preseed",
            heuristicMetadata = null, computedAt = 1500L,
        ))
        // Seed a recent session with sets that have feedback, so the est-coef heuristic has data to act on
        // and SeedNormalizer sees both exercises as observed.
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 3000L, endTime = 4000L))
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = benchId, setNumber = 1,
            targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = 3500L,
        ))
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = inclineId, setNumber = 1,
            targetWeight = 85f, targetReps = 5, feedback = SetFeedback.RIR_2_4, completedAt = 3500L,
        ))

        val repo = WorkoutRepository(db,
            heuristics = listOf(EstCoefConsensusHeuristic()),
            normalizers = listOf(SeedNormalizer()),
        )

        repo.recomputeDerivedState()

        // Both kinds of writes must show up — this is the regression guard for "backfill ran one pass but
        // not the other".
        val coefHeuristicRows = db.coefficientChangeLogDao().getAll()
            .filter { it.heuristicName == "est-coef-consensus" }
        assertTrue("expected at least one est-coef-consensus row, got 0",
            coefHeuristicRows.isNotEmpty())
        val normRows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertTrue("expected at least one NORMALIZATION row, got 0",
            normRows.isNotEmpty())
    }
```

- [ ] **Step 4: Run the instrumented test**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest.recomputeDerivedState_realStack_writesBothCoefficientHeuristicAndNormalizationLogs"`
Expected: PASS.

- [ ] **Step 5: Run the full instrumented suite to check for regressions**

Run: `./gradlew :app:connectedAndroidTest`
Expected: BUILD SUCCESSFUL — every test passes.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt
git commit -m "feat(app): wire SeedNormalizer and route backfill through recomputeDerivedState"
```

---

### Task 12: End-to-end smoke — multi-session convergence

**Files:**
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

A higher-fidelity check that the full pipeline converges in the natural way: drift accumulates, and after enough sessions one NORMALIZATION row eventually appears. Catches subtle wiring bugs (wrong order, wrong sign) that the per-method tests might miss.

- [ ] **Step 1: Add the test**

Append to `WorkoutRepositoryTest`:

```kotlin
    @Test
    fun applySessionProgression_repeatedDriftEventuallyTriggersNormalization() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
        )
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
            Exercise(name = "Incline Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val benchId = db.exerciseDao().getActive().first { it.name == "Barbell Bench Press" }.id
        val inclineId = db.exerciseDao().getActive().first { it.name == "Incline Barbell Bench Press" }.id
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))

        val repo = WorkoutRepository(db,
            heuristics = listOf(EstCoefConsensusHeuristic()),
            normalizers = listOf(SeedNormalizer()),
        )

        // Run many sessions in which both chest exercises are completed at TOO_HARD feedback. This drives
        // both per-exercise coefficients down (above their seeds initially — they'll drift below over time),
        // and at some point the accumulated same-direction drift will trip the 2 kg normalization threshold.
        // 10 sessions should be plenty; assert that at least one NORMALIZATION row exists by the end.
        var startTime = 1_000L
        repeat(10) {
            val sessionId = db.workoutSessionDao().insert(
                WorkoutSession(startTime = startTime, endTime = startTime + 500L)
            )
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = sessionId, exerciseId = benchId, setNumber = 1,
                targetWeight = 95f, targetReps = 5, actualReps = 3,
                feedback = SetFeedback.TOO_HARD, completedAt = startTime + 100L,
            ))
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = sessionId, exerciseId = inclineId, setNumber = 1,
                targetWeight = 80f, targetReps = 5, actualReps = 3,
                feedback = SetFeedback.TOO_HARD, completedAt = startTime + 200L,
            ))
            repo.applySessionProgression(sessionId)
            startTime += 86_400_000L  // advance by ~1 day so the heuristic's recency window stays warm
        }

        val normRows = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.NORMALIZATION }
        assertTrue(
            "expected at least one NORMALIZATION baseline row after 10 drifty sessions, got ${normRows.size}",
            normRows.isNotEmpty(),
        )
    }
```

- [ ] **Step 2: Run the test**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest.applySessionProgression_repeatedDriftEventuallyTriggersNormalization"`
Expected: PASS.

- [ ] **Step 3: Run the full instrumented suite one more time**

Run: `./gradlew :app:connectedAndroidTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full JVM unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt
git commit -m "test(domain): add end-to-end normalization convergence smoke"
```

---

## Self-review notes

The following spec requirements map to tasks:

- New `BaselineChangeReason.NORMALIZATION` → Task 1.
- `BaselineNormalizationThreshold` (2 kg / 5 lb) → Task 2.
- `BaselineNormalizer` interface, `ExerciseCoefficientSnapshot`, `BaselineNormalizationInput`, `BaselineNormalizationProposal` → Task 3.
- `SeedNormalizer` empty / insufficient-data cases → Task 4.
- `SeedNormalizer` least-squares math (direction, no-drift, hand-computed optimal, per-muscle independence) → Task 5.
- `SeedNormalizer` metadata format → Task 6.
- `WorkoutRepository.normalizers` constructor param + `buildNormalizationInput` → Task 7.
- `applyBaselineNormalization` runner (threshold, rounding, mEffective, session-weight invariance, update set covers all in-group exercises) → Task 8.
- `recomputeDerivedState` single entry point; `applySessionProgression` routes through it → Task 9.
- `applyManualBaselineOverrides` does not trigger normalization → Task 10.
- `SeedNormalizer` registered in `StochasticStrengthApp`; backfill switched to `recomputeDerivedState`; backfill regression guard test → Task 11.
- End-to-end convergence smoke → Task 12.
