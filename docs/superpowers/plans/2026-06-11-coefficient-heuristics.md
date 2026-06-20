# Coefficient Heuristics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the first concrete `CoefficientHeuristic` — `EstCoefConsensusHeuristic` — that derives per-exercise coefficients from session history (set-level signals → H1 per-exercise → H2 muscle-group consensus → damping) and register it on `WorkoutRepository`.

**Architecture:** First refactor `CoefficientComputationInput` to a raw-data shape (`List<WorkoutSet>` + lookup maps). Then build `EstCoefConsensusHeuristic` as a pure function with internal helpers per design section — each helper individually testable on the JVM. Finally register the heuristic in `StochasticStrengthApp` so `recomputeCoefficients()` actually does something.

**Tech Stack:** Kotlin, Android Room (existing), JUnit 4 JVM tests, `./gradlew :app:testDebugUnitTest` for unit tests, `./gradlew :app:connectedAndroidTest` for instrumented tests.

**Spec:** `docs/superpowers/specs/2026-06-11-coefficient-heuristics-design.md`

---

## File Structure

- `app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientHeuristic.kt` — restructured input/output types
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` — `buildCoefficientInput()` rewritten to populate new shape
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt` — **new**, implements `CoefficientHeuristic`
- `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt` — register heuristic when constructing `WorkoutRepository`
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt` — **new**, JVM unit tests
- `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt` — update tests that reference the old snapshot shape

---

## Task 1: Restructure `CoefficientComputationInput`

**Why:** The current `CoefficientComputationInput` carries pre-joined `ExerciseSessionSnapshot`s containing `SetSnapshot`s. The design replaces these with raw `WorkoutSet`s + four lookup maps. This task is a self-contained refactor — no new behavior — that unblocks the heuristic.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientHeuristic.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (lines 138–178)
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt` (lines 202–254, plus heuristic-stub usages on lines 290–391)

- [x] **Step 1.1: Replace `CoefficientHeuristic.kt` body**

Overwrite the file with:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

data class CoefficientComputationInput(
    val sets: List<WorkoutSet>,
    val sessionTimes: Map<Long, Long>,
    val exerciseMuscle: Map<Long, MuscleGroup>,
    val baselines: Map<Pair<Long, MuscleGroup>, Float>,
    val currentCoefficients: Map<Long, Float>,
)

data class CoefficientResult(
    val exerciseId: Long,
    val coefficient: Float,
    val metadata: String? = null,
)

interface CoefficientHeuristic {
    val name: String
    fun compute(input: CoefficientComputationInput): List<CoefficientResult>
}
```

- [x] **Step 1.2: Rewrite `WorkoutRepository.buildCoefficientInput()`**

Replace lines 138–178 in `WorkoutRepository.kt` with:

```kotlin
    internal suspend fun buildCoefficientInput(): CoefficientComputationInput {
        val allExercises = db.exerciseDao().getAll()
        val activeExercises = db.exerciseDao().getActive()
        val exerciseMuscle = allExercises.associate { it.id to it.primaryMuscle }
        val sessionTimes = db.workoutSessionDao().getAll().associate { it.id to it.startTime }
        val baselines = db.baselineChangeLogDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.PROGRESSION }
            .associate { (it.sessionId to it.muscleGroup) to it.previousBaseline }
        val sets = db.workoutSetDao().getAll()
        val latestUserCoefficients = db.coefficientChangeLogDao().getLatestPerExercise()
            .associate { it.exerciseId to it.coefficient }
        val currentCoefficients = activeExercises.associate { exercise ->
            exercise.id to (latestUserCoefficients[exercise.id]
                ?: coefficientSource.get(exercise)
                ?: 0f)
        }
        return CoefficientComputationInput(
            sets = sets,
            sessionTimes = sessionTimes,
            exerciseMuscle = exerciseMuscle,
            baselines = baselines,
            currentCoefficients = currentCoefficients,
        )
    }
```

- [x] **Step 1.3: Update the existing `buildCoefficientInput_assembles_snapshots_from_sets_and_baseline_log` test**

In `WorkoutRepositoryTest.kt`, replace the assertions block (lines 241–253) with assertions against the new input shape:

```kotlin
        val input = repository.buildCoefficientInput()

        assertEquals(2, input.sets.size)
        val firstSet = input.sets.first { it.setNumber == 1 }
        val secondSet = input.sets.first { it.setNumber == 2 }
        assertEquals(exerciseId, firstSet.exerciseId)
        assertEquals(sessionId, firstSet.sessionId)
        assertEquals(80f, firstSet.targetWeight, 0.001f)
        assertEquals(SetFeedback.RIR_2_4, firstSet.feedback)
        assertEquals(75f, secondSet.targetWeight, 0.001f)
        assertEquals(SetFeedback.TOO_HARD, secondSet.feedback)
        assertEquals(5000L, input.sessionTimes[sessionId])
        assertEquals(MuscleGroup.CHEST, input.exerciseMuscle[exerciseId])
        assertEquals(100f, input.baselines[sessionId to MuscleGroup.CHEST]!!, 0.001f)
        assertEquals(1.0f, input.currentCoefficients[exerciseId]!!, 0.001f)
```

Also rename the test (function name only) to: `buildCoefficientInput_populates_sets_sessionTimes_exerciseMuscle_baselines_and_currentCoefficients`.

- [x] **Step 1.4: Update the test-heuristic stubs in `WorkoutRepositoryTest.kt`**

The stub heuristics on lines 290–391 reference `input.history.map { CoefficientResult(it.exerciseId, ...) }`. Since `history` no longer exists, change each occurrence:

```kotlin
override fun compute(input: CoefficientComputationInput) =
    input.history.map { CoefficientResult(it.exerciseId, 0.9f, "meta") }
```

becomes:

```kotlin
override fun compute(input: CoefficientComputationInput) =
    input.sets.map { it.exerciseId }.distinct()
        .map { CoefficientResult(it, 0.9f, "meta") }
```

Do the equivalent substitution everywhere the pattern appears (6 stubs — at approximately lines 292–293, 313–314, 320–321, 356–357, 374–375, 379–380; use grep to confirm). Keep the same coefficient and metadata values per stub.

- [x] **Step 1.5: Run the build and the existing tests**

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedAndroidTest
```

Expected: all compile, all pass. The instrumented test suite will exercise `buildCoefficientInput` against the new shape.

- [x] **Step 1.6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientHeuristic.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt
git commit -m "refactor: restructure CoefficientComputationInput to raw sets + lookup maps

Drop SetSnapshot and ExerciseSessionSnapshot; CoefficientComputationInput now
exposes sets + sessionTimes + exerciseMuscle + baselines + currentCoefficients.
Heuristics group as they need.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: `EstCoefConsensusHeuristic` skeleton + set-level signal extraction

**Why:** Start the heuristic with the smallest pure unit — turning a `WorkoutSet` into an `(est_1RM, confidence)` tuple per Section 2 of the spec. The class skeleton is checked in at the same time so subsequent tasks have a place to add code.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt`

- [x] **Step 2.1: Write the failing test file**

Create `EstCoefConsensusHeuristicTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EstCoefConsensusHeuristicTest {

    private val heuristic = EstCoefConsensusHeuristic()

    private fun set(
        targetWeight: Float = 80f,
        targetReps: Int = 5,
        actualReps: Int? = null,
        feedback: SetFeedback? = null,
    ) = WorkoutSet(
        sessionId = 1L,
        exerciseId = 1L,
        setNumber = 1,
        targetWeight = targetWeight,
        targetReps = targetReps,
        actualReps = actualReps,
        feedback = feedback,
    )

    @Test
    fun setSignal_returnsNullForNullFeedback() {
        assertNull(heuristic.setSignal(set(feedback = null)))
    }

    @Test
    fun setSignal_returnsNullForHurt() {
        assertNull(heuristic.setSignal(set(feedback = SetFeedback.HURT)))
    }

    @Test
    fun setSignal_rir5Plus_isWeakSignal() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS))!!
        // toOneRepMax(80, 5 + 7 = 12)
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 12)
        assertEquals(expected, s.est1RM, 0.5f)
        assertEquals(0.4f, s.confidence, 0.001f)
        assertTrue(s.isUpperBound.not())
        assertTrue(s.isDefinite.not())
    }

    @Test
    fun setSignal_rir2_4_isMidConfidence() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 8)
        assertEquals(expected, s.est1RM, 0.5f)
        assertEquals(0.7f, s.confidence, 0.001f)
    }

    @Test
    fun setSignal_rir0_1_isHighConfidence() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, feedback = SetFeedback.RIR_0_1))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 9)
        assertEquals(expected, s.est1RM, 0.5f)
        assertEquals(0.85f, s.confidence, 0.001f)
    }

    @Test
    fun setSignal_tooHardWithActualReps_isDefinite() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, actualReps = 3, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 3)
        assertEquals(expected, s.est1RM, 0.5f)
        assertEquals(0.95f, s.confidence, 0.001f)
        assertTrue(s.isDefinite)
        assertTrue(s.isUpperBound.not())
    }

    @Test
    fun setSignal_tooHardWithoutActualReps_isUpperBound() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 8, actualReps = null, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 7)
        assertEquals(expected, s.est1RM, 0.5f)
        assertEquals(0.5f, s.confidence, 0.001f)
        assertTrue(s.isUpperBound)
        assertTrue(s.isDefinite.not())
    }

    @Test
    fun setSignal_tooHardWithoutActualReps_targetReps1_clampsTo1() {
        val s = heuristic.setSignal(set(targetWeight = 80f, targetReps = 1, actualReps = null, feedback = SetFeedback.TOO_HARD))!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 1)
        assertEquals(expected, s.est1RM, 0.5f)
    }
}
```

- [x] **Step 2.2: Run test, verify it fails to compile**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest" 2>&1 | tail -20
```

Expected: compile error — `EstCoefConsensusHeuristic` not defined.

- [x] **Step 2.3: Create the heuristic file with skeleton + `setSignal`**

Create `EstCoefConsensusHeuristic.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.ln

class EstCoefConsensusHeuristic(
    private val now: () -> Long = System::currentTimeMillis,
    private val tauHalfMs: Long = 14L * 24 * 60 * 60 * 1000,
    private val minEvidenceWeight: Float = 1.5f,
    private val minOutlierSessions: Int = 2,
    private val tauConsensusThreshold: Float = LN_105,
    private val tauOutlierThreshold: Float = LN_110,
    private val alpha: Float = 0.2f,
    private val maxLogStep: Float = LN_105,
    private val minChangeThreshold: Float = 0.005f,
) : CoefficientHeuristic {

    override val name: String = "est-coef-consensus"

    data class SetSignal(
        val est1RM: Float,
        val confidence: Float,
        val isUpperBound: Boolean,
        val isDefinite: Boolean,
    )

    override fun compute(input: CoefficientComputationInput): List<CoefficientResult> {
        // filled in by later tasks
        return emptyList()
    }

    internal fun setSignal(set: WorkoutSet): SetSignal? {
        val feedback = set.feedback ?: return null
        return when (feedback) {
            SetFeedback.HURT -> null
            SetFeedback.RIR_5_PLUS -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 7),
                confidence = 0.4f, isUpperBound = false, isDefinite = false,
            )
            SetFeedback.RIR_2_4 -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 3),
                confidence = 0.7f, isUpperBound = false, isDefinite = false,
            )
            SetFeedback.RIR_0_1 -> SetSignal(
                est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, set.targetReps + 1),
                confidence = 0.85f, isUpperBound = false, isDefinite = false,
            )
            SetFeedback.TOO_HARD -> {
                val reps = set.actualReps
                if (reps != null) SetSignal(
                    est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, reps),
                    confidence = 0.95f, isUpperBound = false, isDefinite = true,
                ) else SetSignal(
                    est1RM = DefaultProgressionEngine.toOneRepMax(set.targetWeight, maxOf(1, set.targetReps - 1)),
                    confidence = 0.5f, isUpperBound = true, isDefinite = false,
                )
            }
        }
    }

    companion object {
        private val LN_105 = ln(1.05f)
        private val LN_110 = ln(1.10f)
    }
}
```

- [x] **Step 2.4: Run test, verify it passes**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"
```

Expected: all 7 set-level tests pass.

- [x] **Step 2.5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt
git commit -m "feat: EstCoefConsensusHeuristic skeleton with set-level signal extraction

Per spec Section 2: per-set (est_1RM, confidence) extraction with branches
for null/HURT/RIR_*/TOO_HARD ± actualReps.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: Session aggregation

**Why:** Section 2 "Session aggregation" — combine the set-level signals in one (session, exercise) bucket into a single `(est_1RM, sessionConfidence, hasDefinite)`. Includes the asymmetric upper-bound rule.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt`

- [x] **Step 3.1: Add failing tests**

Append to `EstCoefConsensusHeuristicTest.kt`:

```kotlin
    @Test
    fun aggregateSession_returnsNullForAllNullSets() {
        val agg = heuristic.aggregateSession(listOf(
            set(feedback = null),
            set(feedback = SetFeedback.HURT),
        ))
        assertNull(agg)
    }

    @Test
    fun aggregateSession_twoRir2_4Sets_returnsWeightedMean() {
        val sets = listOf(
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
        )
        val agg = heuristic.aggregateSession(sets)!!
        val expected = DefaultProgressionEngine.toOneRepMax(80f, 8)
        assertEquals(expected, agg.est1RM, 0.5f)
        assertEquals(0.7f, agg.sessionConfidence, 0.001f)
        assertTrue(agg.hasDefinite.not())
    }

    @Test
    fun aggregateSession_includesReducedWeightSets() {
        // Original RIR_2_4 at 80 followed by reduced-weight RIR_0_1 at 70 (post-failure backoff).
        val sets = listOf(
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            set(targetWeight = 70f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
        )
        val agg = heuristic.aggregateSession(sets)!!
        val a = DefaultProgressionEngine.toOneRepMax(80f, 8)
        val b = DefaultProgressionEngine.toOneRepMax(70f, 6)
        val expectedEst1RM = (a * 0.7f + b * 0.85f) / (0.7f + 0.85f)
        assertEquals(expectedEst1RM, agg.est1RM, 0.5f)
    }

    @Test
    fun aggregateSession_definiteFlagSetWhenAnySetIsTooHardWithActualReps() {
        val sets = listOf(
            set(targetWeight = 80f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            set(targetWeight = 75f, targetReps = 5, actualReps = 3, feedback = SetFeedback.TOO_HARD),
        )
        val agg = heuristic.aggregateSession(sets)!!
        assertTrue(agg.hasDefinite)
    }

    @Test
    fun aggregateSession_upperBoundOmittedWhenOtherPointsLower() {
        // Upper-bound est_1RM is high (TOO_HARD w/o actualReps => toOneRepMax(80, 4) per default branch).
        // The RIR_2_4 set's est_1RM at the SAME weight 80 with targetReps=5 => toOneRepMax(80, 8) — that's
        // actually higher than toOneRepMax(80, 4). Use a config where the upper-bound clearly exceeds the
        // RIR signal: targetReps for the RIR set is small (so its inferred 1RM stays low), and the upper
        // bound is built from a higher targetReps.
        val sets = listOf(
            set(targetWeight = 60f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS), // est_1RM = toOneRepMax(60, 12)
            set(targetWeight = 100f, targetReps = 5, actualReps = null, feedback = SetFeedback.TOO_HARD), // upper bound = toOneRepMax(100, 4)
        )
        // The RIR_5_PLUS estimate at (60, 12) is much less than the upper bound at (100, 4) — upper bound
        // would dominate if included. Spec says omit it when other-feedback est_1RM is below the bound.
        val agg = heuristic.aggregateSession(sets)!!
        val rirEst = DefaultProgressionEngine.toOneRepMax(60f, 12)
        assertEquals(rirEst, agg.est1RM, 0.5f)
    }

    @Test
    fun aggregateSession_upperBoundIncludedWhenOtherPointsAgreeAbove() {
        // Other-feedback est_1RM exceeds upper bound — bound is in agreement (below or equal) → included.
        val sets = listOf(
            set(targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_5_PLUS), // est_1RM = toOneRepMax(100, 12) — very high
            set(targetWeight = 100f, targetReps = 5, actualReps = null, feedback = SetFeedback.TOO_HARD), // upper bound = toOneRepMax(100, 4) — much lower
        )
        val agg = heuristic.aggregateSession(sets)!!
        val rirEst = DefaultProgressionEngine.toOneRepMax(100f, 12)
        val upperBound = DefaultProgressionEngine.toOneRepMax(100f, 4)
        // When upper bound is *below* the other-feedback estimate, it's a ceiling that pulls the agg down.
        // Confidence-weighted mean of (rirEst @ 0.4, upperBound @ 0.5).
        val expected = (rirEst * 0.4f + upperBound * 0.5f) / (0.4f + 0.5f)
        assertEquals(expected, agg.est1RM, 0.5f)
    }
```

- [x] **Step 3.2: Run, verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest" 2>&1 | tail -20
```

Expected: compile error — `aggregateSession` not defined.

- [x] **Step 3.3: Implement `aggregateSession`**

Add this nested class and method to `EstCoefConsensusHeuristic.kt`, inside the class body (after `setSignal`):

```kotlin
    data class SessionAggregate(
        val est1RM: Float,
        val sessionConfidence: Float,
        val hasDefinite: Boolean,
    )

    internal fun aggregateSession(sets: List<WorkoutSet>): SessionAggregate? {
        val signals = sets.mapNotNull { setSignal(it) }
        if (signals.isEmpty()) return null

        val nonUpperBound = signals.filter { !it.isUpperBound }
        val included = if (nonUpperBound.isEmpty()) {
            signals
        } else {
            val nonBoundMean = nonUpperBound.sumOf { (it.est1RM * it.confidence).toDouble() }
                .toFloat() / nonUpperBound.sumOf { it.confidence.toDouble() }.toFloat()
            signals.filter { sig ->
                if (!sig.isUpperBound) true
                else nonBoundMean > sig.est1RM
            }
        }
        if (included.isEmpty()) return null

        val totalConf = included.sumOf { it.confidence.toDouble() }.toFloat()
        val weighted1RM = included.sumOf { (it.est1RM * it.confidence).toDouble() }.toFloat() / totalConf
        val avgConf = totalConf / included.size
        return SessionAggregate(
            est1RM = weighted1RM,
            sessionConfidence = avgConf,
            hasDefinite = signals.any { it.isDefinite },
        )
    }
```

- [x] **Step 3.4: Run tests, verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"
```

Expected: all session-aggregation tests pass alongside the set-level tests.

- [x] **Step 3.5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt
git commit -m "feat: EstCoefConsensusHeuristic session aggregation

Per spec Section 2 'Session aggregation': confidence-weighted mean of set
signals; asymmetric upper-bound (TOO_HARD without actualReps) omitted when
other points already agree below it.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: H1 — per-exercise aggregation

**Why:** Section 3 of the spec. Pulls session signals together per exercise, applies recency decay, computes the weighted median, and emits an `H1Proposal` (or nothing) subject to the `min_evidence_weight` / `has_definite_e` gates.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt`

- [x] **Step 4.1: Add failing tests**

Append to `EstCoefConsensusHeuristicTest.kt`:

```kotlin
    // Synthetic sessions to drive computeH1 directly.
    private fun sessionSignal(
        sessionId: Long,
        sessionTime: Long,
        estCoef: Float,
        sessionConfidence: Float,
        hasDefinite: Boolean = false,
    ) = EstCoefConsensusHeuristic.SessionSignal(
        sessionId = sessionId,
        sessionTime = sessionTime,
        estCoef = estCoef,
        sessionConfidence = sessionConfidence,
        hasDefinite = hasDefinite,
    )

    @Test
    fun computeH1_empty_returnsNull() {
        val h = EstCoefConsensusHeuristic(now = { 1000L })
        assertNull(h.computeH1(emptyList()))
    }

    @Test
    fun computeH1_belowMinEvidenceAndNoDefinite_returnsNull() {
        // One RIR_2_4-like session, recency ~1.0, confidence 0.7 -> weight 0.7 < min_evidence_weight = 1.5.
        val h = EstCoefConsensusHeuristic(now = { 1000L })
        val signals = listOf(sessionSignal(1L, 1000L, 1.25f, 0.7f, hasDefinite = false))
        assertNull(h.computeH1(signals))
    }

    @Test
    fun computeH1_singleDefinitePointBypassesMinEvidence() {
        val h = EstCoefConsensusHeuristic(now = { 1000L })
        val signals = listOf(sessionSignal(1L, 1000L, 1.25f, 0.95f, hasDefinite = true))
        val proposal = h.computeH1(signals)!!
        assertEquals(1.25f, proposal.proposal, 0.001f)
        assertEquals(1, proposal.sessionCount)
        assertTrue(proposal.hasDefinite)
    }

    @Test
    fun computeH1_weightedMedianIgnoresSingleOutlier() {
        // Three near-1.0 + one freak — median picks the cluster.
        val h = EstCoefConsensusHeuristic(now = { 1000L })
        val signals = listOf(
            sessionSignal(1L, 1000L, 1.00f, 0.7f),
            sessionSignal(2L, 1000L, 1.00f, 0.7f),
            sessionSignal(3L, 1000L, 1.05f, 0.7f),
            sessionSignal(4L, 1000L, 1.80f, 0.4f), // freak, low confidence
        )
        val proposal = h.computeH1(signals)!!
        assertTrue("median should sit in the 1.0–1.05 cluster, got ${proposal.proposal}",
            proposal.proposal in 1.00f..1.05f)
        assertEquals(4, proposal.sessionCount)
    }

    @Test
    fun computeH1_recencyDecayMakesRecentLowConfWeighComparableToOldHighConf() {
        // tauHalf = 14d = 14*24*60*60*1000 ms. Two sessions:
        // Recent low-confidence (0.4) at full recency, old high-confidence (0.85) at 28d (recency = 0.25).
        val tauHalfMs = 14L * 24 * 60 * 60 * 1000
        val nowT = 100_000_000L
        val h = EstCoefConsensusHeuristic(now = { nowT })
        val signals = listOf(
            sessionSignal(1L, nowT, 1.10f, 0.4f),          // weight ≈ 1.0 × 0.4 = 0.40
            sessionSignal(2L, nowT - 2 * tauHalfMs, 1.30f, 0.85f), // weight ≈ 0.25 × 0.85 = 0.2125
        )
        val proposal = h.computeH1(signals)
        // total_weight ≈ 0.61 < 1.5 and no definite point → expect null
        assertNull(proposal)
    }
```

- [x] **Step 4.2: Run, verify compile failure**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest" 2>&1 | tail -10
```

Expected: compile error — `SessionSignal`, `computeH1`, `H1Proposal` missing.

- [x] **Step 4.3: Implement `SessionSignal`, `H1Proposal`, `weightedMedian`, and `computeH1`**

Add to `EstCoefConsensusHeuristic.kt`, inside the class body:

```kotlin
    data class SessionSignal(
        val sessionId: Long,
        val sessionTime: Long,
        val estCoef: Float,
        val sessionConfidence: Float,
        val hasDefinite: Boolean,
    )

    data class H1Proposal(
        val proposal: Float,
        val totalWeight: Float,
        val proposalConfidence: Float,
        val hasDefinite: Boolean,
        val sessionCount: Int,
    )

    internal fun computeH1(signals: List<SessionSignal>): H1Proposal? {
        if (signals.isEmpty()) return null
        val nowT = now()
        val ln2OverHalf = ln(2.0) / tauHalfMs
        val weighted = signals.map { s ->
            val recency = kotlin.math.exp(-(nowT - s.sessionTime).coerceAtLeast(0L) * ln2OverHalf).toFloat()
            Triple(s, recency, recency * s.sessionConfidence)
        }
        val totalWeight = weighted.sumOf { it.third.toDouble() }.toFloat()
        val hasDefinite = signals.any { it.hasDefinite }
        if (totalWeight < minEvidenceWeight && !hasDefinite) return null

        val median = weightedMedian(weighted.map { it.first.estCoef to it.third })
        val recencySum = weighted.sumOf { it.second.toDouble() }.toFloat()
        val confSum = weighted.sumOf { (it.second * it.first.sessionConfidence).toDouble() }.toFloat()
        val proposalConfidence = if (recencySum > 0f) confSum / recencySum else 0f

        return H1Proposal(
            proposal = median,
            totalWeight = totalWeight,
            proposalConfidence = proposalConfidence,
            hasDefinite = hasDefinite,
            sessionCount = signals.size,
        )
    }

    private fun weightedMedian(valueWeights: List<Pair<Float, Float>>): Float {
        val sorted = valueWeights.sortedBy { it.first }
        val total = sorted.sumOf { it.second.toDouble() }.toFloat()
        val half = total / 2f
        var cum = 0f
        for ((v, w) in sorted) {
            cum += w
            if (cum >= half) return v
        }
        return sorted.last().first
    }
```

- [x] **Step 4.4: Run tests, verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"
```

Expected: all H1 tests pass.

- [x] **Step 4.5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt
git commit -m "feat: EstCoefConsensusHeuristic H1 per-exercise aggregation

Weighted-median over recency-weighted (est_coef, confidence) tuples; gated
by min_evidence_weight unless any contributing point is TOO_HARD+actualReps.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: H2 — cross-exercise consensus

**Why:** Section 4 of the spec. Decides per muscle group whether each H1 proposal passes through, gets suppressed (uniform drift), or gets boosted to confidence 1.0 (outlier). Includes the new `session_count_e* ≥ 2` gate.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt`

- [x] **Step 5.1: Add failing tests**

Append to `EstCoefConsensusHeuristicTest.kt`:

```kotlin
    private fun proposal(
        proposal: Float,
        sessionCount: Int = 3,
        confidence: Float = 0.8f,
    ) = EstCoefConsensusHeuristic.H1Proposal(
        proposal = proposal,
        totalWeight = 3f,
        proposalConfidence = confidence,
        hasDefinite = false,
        sessionCount = sessionCount,
    )

    @Test
    fun applyH2_singleExercise_passesThrough() {
        val h = EstCoefConsensusHeuristic()
        val result = h.applyH2(
            mapOf(1L to proposal(1.10f, confidence = 0.7f)),
            currentCoefficients = mapOf(1L to 1.00f),
        )
        val emit = result.getValue(1L)
        assertEquals(1.10f, emit.proposal, 0.001f)
        assertEquals(0.7f, emit.confidence, 0.001f)
    }

    @Test
    fun applyH2_uniformDriftAboveThreshold_suppressesAll() {
        val h = EstCoefConsensusHeuristic()
        val result = h.applyH2(
            mapOf(
                1L to proposal(1.07f),
                2L to proposal(1.08f),
                3L to proposal(1.06f),
            ),
            currentCoefficients = mapOf(1L to 1.00f, 2L to 1.00f, 3L to 1.00f),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun applyH2_uniformDriftBelowThreshold_passesThroughAll() {
        val h = EstCoefConsensusHeuristic()
        val result = h.applyH2(
            mapOf(
                1L to proposal(1.02f),
                2L to proposal(1.01f),
                3L to proposal(1.03f),
            ),
            currentCoefficients = mapOf(1L to 1.00f, 2L to 1.00f, 3L to 1.00f),
        )
        assertEquals(3, result.size)
        result.values.forEach { emit ->
            assertEquals(0.8f, emit.confidence, 0.001f) // H1's native confidence
        }
    }

    @Test
    fun applyH2_outlierWithMultipleSessions_emitsBoostedConfidence() {
        val h = EstCoefConsensusHeuristic()
        val result = h.applyH2(
            mapOf(
                1L to proposal(1.01f), // sibling, flat
                2L to proposal(0.99f), // sibling, flat
                3L to proposal(0.96f, sessionCount = 3), // outlier
            ),
            currentCoefficients = mapOf(1L to 1.00f, 2L to 1.00f, 3L to 0.75f),
        )
        assertEquals(1, result.size)
        val outlier = result.getValue(3L)
        assertEquals(0.96f, outlier.proposal, 0.001f)
        assertEquals(1.0f, outlier.confidence, 0.001f)
        assertTrue(outlier.metadata?.startsWith("consensus_outlier") == true)
    }

    @Test
    fun applyH2_outlierWithSingleSession_fallsThroughToMixedPath() {
        val h = EstCoefConsensusHeuristic()
        val result = h.applyH2(
            mapOf(
                1L to proposal(1.01f, sessionCount = 3),
                2L to proposal(0.99f, sessionCount = 3),
                3L to proposal(0.96f, sessionCount = 1, confidence = 0.95f), // freak single
            ),
            currentCoefficients = mapOf(1L to 1.00f, 2L to 1.00f, 3L to 0.75f),
        )
        assertEquals(3, result.size)
        // Outlier emits at H1's native confidence, not 1.0
        assertEquals(0.95f, result.getValue(3L).confidence, 0.001f)
    }
```

To call `applyH2` directly, it needs to be `internal`. The signature uses `EmitProposal` as the value type — define it on the class.

- [x] **Step 5.2: Run, verify compile failure**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest" 2>&1 | tail -10
```

Expected: compile error — `applyH2`, `EmitProposal` missing.

- [x] **Step 5.3: Implement `EmitProposal` and `applyH2`**

Add to `EstCoefConsensusHeuristic.kt` inside the class body:

```kotlin
    data class EmitProposal(
        val proposal: Float,
        val confidence: Float,
        val metadata: String?,
    )

    internal fun applyH2(
        proposals: Map<Long, H1Proposal>,
        currentCoefficients: Map<Long, Float>,
        exerciseMuscle: Map<Long, io.github.fowles.stochastic_strength.data.model.MuscleGroup> = emptyMap(),
    ): Map<Long, EmitProposal> {
        val out = mutableMapOf<Long, EmitProposal>()
        // If exerciseMuscle is empty (test convenience), treat all as one synthetic group.
        val groups = if (exerciseMuscle.isEmpty()) {
            mapOf("ALL" to proposals.keys.toList())
        } else {
            proposals.keys.groupBy { exerciseMuscle[it]?.name ?: "UNKNOWN" }
        }

        for ((muscleName, exerciseIds) in groups) {
            val entries = exerciseIds.map { id ->
                val p = proposals.getValue(id)
                val cur = currentCoefficients[id] ?: 0f
                if (cur <= 0f) return@map null
                Triple(id, p, ln((p.proposal / cur).toDouble()).toFloat())
            }.filterNotNull()

            val n = entries.size
            when {
                n == 0 -> { /* nothing */ }
                n == 1 -> {
                    val (id, p, _) = entries.single()
                    out[id] = EmitProposal(p.proposal, p.proposalConfidence, null)
                }
                else -> {
                    val mean = entries.sumOf { it.third.toDouble() }.toFloat() / n
                    val sameSign = entries.all { it.third >= 0f } || entries.all { it.third <= 0f }
                    if (sameSign && kotlin.math.abs(mean) > tauConsensusThreshold) {
                        // suppress all
                    } else {
                        val outlierCandidates = entries.filter { kotlin.math.abs(it.third) > tauOutlierThreshold }
                        val siblings = entries - outlierCandidates.toSet()
                        val siblingsCalm = siblings.all { kotlin.math.abs(it.third) < tauConsensusThreshold }
                        if (n >= 3 && outlierCandidates.size == 1 && siblingsCalm
                            && outlierCandidates.single().second.sessionCount >= minOutlierSessions) {
                            val (id, p, _) = outlierCandidates.single()
                            out[id] = EmitProposal(p.proposal, 1.0f, "consensus_outlier:m=$muscleName,sibling_count=${n - 1}")
                        } else {
                            for ((id, p, _) in entries) {
                                val meta = "consensus_mixed:m=$muscleName,n=$n"
                                out[id] = EmitProposal(p.proposal, p.proposalConfidence, meta)
                            }
                        }
                    }
                }
            }
        }
        return out
    }
```

- [x] **Step 5.4: Run tests, verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"
```

Expected: all H2 tests pass alongside earlier ones.

- [x] **Step 5.5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt
git commit -m "feat: EstCoefConsensusHeuristic H2 cross-exercise consensus

Per spec Section 4: pass-through (n=1), suppression (uniform drift over
threshold), outlier boost (n≥3 with session_count≥2 gate), or mixed-signal.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Damping

**Why:** Section 5 of the spec. Converts an `EmitProposal` + current coefficient into a `CoefficientResult` (or drops it below the change threshold), with the log-space damping and per-update cap.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt`

- [x] **Step 6.1: Add failing tests**

Append to `EstCoefConsensusHeuristicTest.kt`:

```kotlin
    @Test
    fun damp_proposalEqualsCurrent_emitsNothing() {
        val h = EstCoefConsensusHeuristic()
        val result = h.damp(
            exerciseId = 1L,
            emit = EstCoefConsensusHeuristic.EmitProposal(1.00f, 0.8f, null),
            currentCoef = 1.00f,
        )
        assertNull(result)
    }

    @Test
    fun damp_smallChangeProportionalToConfidenceAndDistance() {
        val h = EstCoefConsensusHeuristic()
        // log(1.10/1.00) = 0.0953. α=0.2 × conf 0.5 × 0.0953 = 0.00953. Under cap (0.0488).
        val result = h.damp(
            exerciseId = 1L,
            emit = EstCoefConsensusHeuristic.EmitProposal(1.10f, 0.5f, "m"),
            currentCoef = 1.00f,
        )!!
        val expected = 1.00f * kotlin.math.exp(0.2f * 0.5f * kotlin.math.ln(1.10f))
        assertEquals(expected, result.coefficient, 0.001f)
        assertEquals(1L, result.exerciseId)
        assertEquals("m", result.metadata)
    }

    @Test
    fun damp_largeChangeIsClampedToMaxLogStep() {
        val h = EstCoefConsensusHeuristic()
        // Confidence 1.0 + huge gap → log step clamped to ln(1.05).
        val result = h.damp(
            exerciseId = 1L,
            emit = EstCoefConsensusHeuristic.EmitProposal(2.00f, 1.0f, null),
            currentCoef = 1.00f,
        )!!
        val expected = 1.00f * 1.05f
        assertEquals(expected, result.coefficient, 0.001f)
    }
```

- [x] **Step 6.2: Run, verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest" 2>&1 | tail -10
```

Expected: compile error — `damp` not defined.

- [x] **Step 6.3: Implement `damp`**

Add to `EstCoefConsensusHeuristic.kt`:

```kotlin
    internal fun damp(exerciseId: Long, emit: EmitProposal, currentCoef: Float): CoefficientResult? {
        if (currentCoef <= 0f) return null
        val raw = alpha * emit.confidence * ln((emit.proposal / currentCoef).toDouble()).toFloat()
        val step = raw.coerceIn(-maxLogStep, maxLogStep)
        val newCoef = currentCoef * kotlin.math.exp(step.toDouble()).toFloat()
        if (kotlin.math.abs(newCoef - currentCoef) < minChangeThreshold) return null
        return CoefficientResult(exerciseId, newCoef, emit.metadata)
    }
```

- [x] **Step 6.4: Run tests, verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"
```

Expected: all damping tests pass.

- [x] **Step 6.5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt
git commit -m "feat: EstCoefConsensusHeuristic log-space damping

Per spec Section 5: α × confidence × log(proposal/current), clamped to
±max_log_step (≈5%). Drops updates below min_change_threshold.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: Wire `compute()` end-to-end + integration test

**Why:** Compose Tasks 2–6 into the public `compute()` method. Bucket sets, build session signals, run H1, H2, damp. End with an integration test that exercises a realistic multi-exercise, multi-session input.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt`

- [x] **Step 7.1: Add failing integration test**

Append to `EstCoefConsensusHeuristicTest.kt`:

```kotlin
    @Test
    fun compute_singleExerciseConsistentRir2_4_nudgesCoefficientUp() {
        // One exercise (CHEST), four recent sessions all RIR_2_4 at 80kg×5 against baseline 80kg.
        // current_coef = 1.00, est_coef per session ≈ toOneRepMax(80,8) / 80 ≈ 1.30.
        val nowT = 100_000_000_000L
        val dayMs = 24L * 60 * 60 * 1000
        val sets = listOf(2L, 7L, 13L, 20L).flatMapIndexed { idx, dayOffset ->
            val sessionId = (idx + 1).toLong()
            listOf(
                WorkoutSet(
                    id = sessionId * 10,
                    sessionId = sessionId,
                    exerciseId = 1L,
                    setNumber = 1,
                    targetWeight = 80f,
                    targetReps = 5,
                    feedback = SetFeedback.RIR_2_4,
                )
            )
        }
        val input = CoefficientComputationInput(
            sets = sets,
            sessionTimes = mapOf(1L to nowT - 2 * dayMs, 2L to nowT - 7 * dayMs,
                                 3L to nowT - 13 * dayMs, 4L to nowT - 20 * dayMs),
            exerciseMuscle = mapOf(1L to io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST),
            baselines = mapOf(
                (1L to io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST) to 80f,
                (2L to io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST) to 80f,
                (3L to io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST) to 80f,
                (4L to io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST) to 80f,
            ),
            currentCoefficients = mapOf(1L to 1.00f),
        )
        val h = EstCoefConsensusHeuristic(now = { nowT })
        val results = h.compute(input)
        assertEquals(1, results.size)
        val res = results.single()
        assertEquals(1L, res.exerciseId)
        assertTrue("expected nudge up, got ${res.coefficient}", res.coefficient in 1.02f..1.06f)
    }

    @Test
    fun compute_skipsBodyweightExercisesWithZeroCoefficient() {
        val nowT = 100_000_000_000L
        val sets = listOf(WorkoutSet(
            sessionId = 1L, exerciseId = 99L, setNumber = 1,
            targetWeight = 0f, targetReps = 10, feedback = SetFeedback.RIR_2_4,
        ))
        val input = CoefficientComputationInput(
            sets = sets,
            sessionTimes = mapOf(1L to nowT),
            exerciseMuscle = mapOf(99L to io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST),
            baselines = mapOf((1L to io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST) to 80f),
            currentCoefficients = mapOf(99L to 0f),
        )
        val h = EstCoefConsensusHeuristic(now = { nowT })
        assertTrue(h.compute(input).isEmpty())
    }
```

- [x] **Step 7.2: Run, verify failure**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest" 2>&1 | tail -10
```

Expected: integration tests fail (compute returns empty by skeleton).

- [x] **Step 7.3: Implement `compute()`**

Replace the stub `compute(...)` in `EstCoefConsensusHeuristic.kt` with:

```kotlin
    override fun compute(input: CoefficientComputationInput): List<CoefficientResult> {
        val buckets = input.sets.groupBy { it.sessionId to it.exerciseId }
        val perExerciseSignals = mutableMapOf<Long, MutableList<SessionSignal>>()

        for ((key, bucketSets) in buckets) {
            val (sessionId, exerciseId) = key
            val current = input.currentCoefficients[exerciseId] ?: 0f
            if (current <= 0f) continue
            val muscle = input.exerciseMuscle[exerciseId] ?: continue
            val baseline = input.baselines[sessionId to muscle] ?: continue
            if (baseline <= 0f) continue
            val sessionTime = input.sessionTimes[sessionId] ?: continue
            val agg = aggregateSession(bucketSets) ?: continue
            perExerciseSignals.getOrPut(exerciseId) { mutableListOf() }
                .add(SessionSignal(
                    sessionId = sessionId,
                    sessionTime = sessionTime,
                    estCoef = agg.est1RM / baseline,
                    sessionConfidence = agg.sessionConfidence,
                    hasDefinite = agg.hasDefinite,
                ))
        }

        val h1Proposals = perExerciseSignals.mapNotNull { (id, signals) ->
            computeH1(signals)?.let { id to it }
        }.toMap()
        if (h1Proposals.isEmpty()) return emptyList()

        val survivors = applyH2(h1Proposals, input.currentCoefficients, input.exerciseMuscle)

        return survivors.mapNotNull { (id, emit) ->
            val cur = input.currentCoefficients[id] ?: return@mapNotNull null
            damp(id, emit, cur)
        }
    }
```

- [x] **Step 7.4: Run all heuristic tests**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"
```

Expected: all tests pass.

- [x] **Step 7.5: Run the full unit-test suite for regressions**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: green.

- [x] **Step 7.6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt
git commit -m "feat: EstCoefConsensusHeuristic compute() composition

Wires set-level signals → session aggregation → H1 → H2 → damping per
spec Section 7. Skips bodyweight exercises (current coef ≤ 0) and snapshots
with missing baseline/session-time/muscle data.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: Register heuristic in `StochasticStrengthApp`

**Why:** Until a heuristic is wired into the `WorkoutRepository` constructor, `recomputeCoefficients()` is a no-op. Make it live.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt`

- [x] **Step 8.1: Inspect current repository construction**

```bash
grep -n "WorkoutRepository(" /Users/mfk/dev/stochastic-strength/app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt
```

Expected: one line that constructs `WorkoutRepository(database)` or similar with default `heuristics = listOf()`.

If no construction exists in `StochasticStrengthApp.kt` itself, search wider:

```bash
grep -rn "WorkoutRepository(" /Users/mfk/dev/stochastic-strength/app/src/main/java --include="*.kt"
```

The call site to update is wherever the singleton/factory `WorkoutRepository` is built for production use. (Per existing project notes, `StochasticStrengthApp` owns `AppDatabase`; the repo construction likely lives in a ViewModel or factory keyed off `application as StochasticStrengthApp`.)

- [x] **Step 8.2: Update the construction site to pass the heuristic**

In whichever file actually constructs the production `WorkoutRepository`, change e.g.:

```kotlin
WorkoutRepository(database)
```

to:

```kotlin
WorkoutRepository(database, heuristics = listOf(EstCoefConsensusHeuristic()))
```

Add the import:

```kotlin
import io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristic
```

- [x] **Step 8.3: Build the app**

```bash
./gradlew :app:assembleDebug
```

Expected: success.

- [x] **Step 8.4: Run full unit + instrumented suites**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedAndroidTest
```

Expected: green. Heuristic registration shouldn't break any existing test — instrumented tests still construct `WorkoutRepository` directly with explicit heuristics (or no heuristics).

- [x] **Step 8.5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt
# include other modified files if step 8.2 found a different construction site
git commit -m "feat: register EstCoefConsensusHeuristic in production WorkoutRepository

The heuristic is inert until at least one is provided. Wiring it here
activates per-exercise coefficient updates after every session.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Notes

- **No DB changes.** All schema work (actualReps, baseline_change_log, coefficient_change_log) is already landed.
- **Bodyweight exercises.** Skipped entirely when `currentCoefficients[id] ≤ 0`. The seed `ExerciseCoefficients` returns null/0 for bodyweight; the new input builder maps null → 0f to keep that contract.
- **Migration risk.** Task 1's refactor changes the public shape of `CoefficientComputationInput`. The only external consumers are `WorkoutRepository.buildCoefficientInput`, the heuristic interface (no production implementations yet), and the existing test stubs in `WorkoutRepositoryTest.kt`. All are updated in Task 1.
- **Performance.** `db.workoutSetDao().getAll()` loads every set in history. Acceptable for the scale of a personal training app (low thousands of rows). If this becomes a bottleneck later, add a recency-aware query.
- **Why JVM tests.** Per the spec, the heuristic is a pure function over `CoefficientComputationInput`. No Android dependencies, so JVM tests at `src/test/` give faster iteration than instrumented tests.
