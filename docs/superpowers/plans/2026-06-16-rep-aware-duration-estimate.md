# Rep-Aware Workout Duration Estimate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the constant-per-set duration heuristic with a rep-aware formula (work scales with `sessionReps`, plate-change overhead modeled explicitly) plus per-exercise learned pacing, so the plan-preview estimate tracks the new rep-range slider correctly.

**Architecture:** A pure `DurationCalculator` object computes per-exercise seconds from `(exercise, sessionReps, numSets, warmupSets, secondsPerRep?)`. A new `ExercisePacingEstimator` (replaces `ExerciseDurationEstimator`) learns per-exercise `secondsPerRep` from consecutive working-set time deltas. `WorkoutPlanner.withWeight`/`recomputeExercise` stamp `estimatedSeconds` directly onto each `PlannedExercise` (the `estimatedSecondsOverride` indirection is removed). `WorkoutRepository` builds the new estimator and hands it to the planner.

**Tech Stack:** Kotlin, JUnit 4, Room (read-only at planner build time; no schema change).

**Spec:** `docs/superpowers/specs/2026-06-16-rep-aware-duration-estimate-design.md`

---

## File Structure

**Created:**
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/DurationCalculator.kt` — pure formula object.
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/DurationCalculatorTest.kt` — JVM tests for the formula.
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExercisePacingEstimator.kt` — learns per-exercise `secondsPerRep` from history.
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/ExercisePacingEstimatorTest.kt` — JVM tests for the estimator.

**Modified:**
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/PlannedExercise.kt` — remove `estimatedSecondsOverride`, `secondsPerSet`, and `SECONDS_PER_*` constants; make `estimatedSeconds` a stored `Int = 0` field.
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt` — swap `durationEstimator` → `pacingEstimator`; `withWeight` and `recomputeExercise` call `DurationCalculator.estimate(...)`.
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` — build `ExercisePacingEstimator` instead of `ExerciseDurationEstimator`; pass `exercisesById`.
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/PlannedExerciseTest.kt` — rewrite for the new shape.
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlanTest.kt` — rewrite for the new shape.
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt` — replace the two `estimatedSecondsOverride` tests with `estimatedSeconds`/`pacingEstimator` equivalents.

**Deleted:**
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimator.kt`
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimatorTest.kt`

---

## Task 1: `DurationCalculator` — formula object (TDD)

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/DurationCalculator.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/DurationCalculatorTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/DurationCalculatorTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.model.WarmupSet
import org.junit.Assert.assertEquals
import org.junit.Test

class DurationCalculatorTest {

    private fun exercise(
        equipment: Equipment = Equipment.BARBELL,
        isTimed: Boolean = false,
        isUnilateral: Boolean = false,
    ) = Exercise(
        id = 1L,
        name = "Ex",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = equipment,
        isTimed = isTimed,
        isUnilateral = isUnilateral,
    )

    @Test
    fun bodyweight_threeSetsTenReps_noWarmups_usesDefaultPerRep() {
        // Bodyweight: plateChangeSec = 0. perRep = 3 (default).
        // working = 3 * (3*10 + 90) = 360. warmups/plate = 0. Total = 360.
        val seconds = DurationCalculator.estimate(
            exercise = exercise(equipment = Equipment.BODYWEIGHT),
            sessionReps = 10,
            numSets = 3,
            warmupSets = emptyList(),
            secondsPerRep = null,
        )
        assertEquals(360, seconds)
    }

    @Test
    fun barbell_threeSetsTenReps_twoWarmups_includesPlateChanges() {
        // perRep = 3. working = 3 * (30 + 90) = 360.
        // warmup work = (8 + 5) * 3 = 39. warmup rest = 2 * 30 = 60.
        // plate changes = 25 * (2 + 1) = 75. Total = 360 + 39 + 60 + 75 = 534.
        val seconds = DurationCalculator.estimate(
            exercise = exercise(equipment = Equipment.BARBELL),
            sessionReps = 10,
            numSets = 3,
            warmupSets = listOf(WarmupSet(40f, 8), WarmupSet(60f, 5)),
            secondsPerRep = null,
        )
        assertEquals(534, seconds)
    }

    @Test
    fun barbell_repsSweep_scalesWorkLinearly() {
        // numSets = 3, perRep = 3, no warmups, plate = 25 * (0+1) = 25.
        // estimate = 3 * (reps*3 + 90) + 25
        val expected = mapOf(
            1 to 304, 3 to 322, 5 to 340, 8 to 367, 10 to 385, 15 to 430, 20 to 475,
        )
        for ((reps, want) in expected) {
            val got = DurationCalculator.estimate(
                exercise = exercise(equipment = Equipment.BARBELL),
                sessionReps = reps,
                numSets = 3,
                warmupSets = emptyList(),
                secondsPerRep = null,
            )
            assertEquals("reps=$reps", want, got)
        }
    }

    @Test
    fun unilateral_doublesWorkTime() {
        // perRep = 3, sides = 2. workPerSet = 3*10*2 = 60.
        // working = 3 * (60 + 90) = 450. plate = 25. Total = 475.
        val seconds = DurationCalculator.estimate(
            exercise = exercise(equipment = Equipment.BARBELL, isUnilateral = true),
            sessionReps = 10,
            numSets = 3,
            warmupSets = emptyList(),
            secondsPerRep = null,
        )
        assertEquals(475, seconds)
    }

    @Test
    fun timed_usesSessionRepsAsDurationAndIgnoresLearning() {
        // sessionReps = 60 seconds. numSets = 3. Total = 3 * (60 + 90) = 450.
        // secondsPerRep override should be ignored for timed.
        val seconds = DurationCalculator.estimate(
            exercise = exercise(equipment = Equipment.BODYWEIGHT, isTimed = true),
            sessionReps = 60,
            numSets = 3,
            warmupSets = emptyList(),
            secondsPerRep = 5.0f,
        )
        assertEquals(450, seconds)
    }

    @Test
    fun learnedSecondsPerRep_overridesDefault() {
        // Same shape as bodyweight test but with perRep = 5 instead of default 3.
        // working = 3 * (5*10 + 90) = 420. Total = 420.
        val seconds = DurationCalculator.estimate(
            exercise = exercise(equipment = Equipment.BODYWEIGHT),
            sessionReps = 10,
            numSets = 3,
            warmupSets = emptyList(),
            secondsPerRep = 5.0f,
        )
        assertEquals(420, seconds)
    }

    @Test
    fun plateChangeSec_matchesTable() {
        assertEquals(25, DurationCalculator.plateChangeSec(Equipment.BARBELL))
        assertEquals(8, DurationCalculator.plateChangeSec(Equipment.DUMBBELL))
        assertEquals(5, DurationCalculator.plateChangeSec(Equipment.KETTLEBELL))
        assertEquals(5, DurationCalculator.plateChangeSec(Equipment.MACHINE))
        assertEquals(5, DurationCalculator.plateChangeSec(Equipment.CABLE_MACHINE))
        assertEquals(0, DurationCalculator.plateChangeSec(Equipment.BODYWEIGHT))
        assertEquals(0, DurationCalculator.plateChangeSec(Equipment.BAND))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.DurationCalculatorTest"`
Expected: FAIL with `Unresolved reference: DurationCalculator`.

- [ ] **Step 3: Implement `DurationCalculator`**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/DurationCalculator.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.domain.model.WarmupSet

object DurationCalculator {
    // Mirrors WorkoutSessionController.REST_SECONDS — kept here to avoid a domain → ui dep.
    const val REST_SECONDS = 90
    const val WARMUP_REST_SECONDS = 30
    const val DEFAULT_SECONDS_PER_REP = 3.0f

    fun estimate(
        exercise: Exercise,
        sessionReps: Int,
        numSets: Int,
        warmupSets: List<WarmupSet>,
        secondsPerRep: Float?,
    ): Int {
        if (exercise.isTimed) {
            return numSets * (sessionReps + REST_SECONDS)
        }
        val perRep = secondsPerRep ?: DEFAULT_SECONDS_PER_REP
        val sides = if (exercise.isUnilateral) 2 else 1
        val workPerSet = perRep * sessionReps * sides
        val workingTime = numSets * (workPerSet + REST_SECONDS)

        val warmupWork = warmupSets.sumOf { (it.reps * perRep * sides).toDouble() }.toFloat()
        val warmupRest = warmupSets.size * WARMUP_REST_SECONDS
        val plateChangeTime = plateChangeSec(exercise.equipment) * (warmupSets.size + 1)

        return (workingTime + warmupWork + warmupRest + plateChangeTime).toInt()
    }

    fun plateChangeSec(equipment: Equipment): Int = when (equipment) {
        Equipment.BARBELL -> 25
        Equipment.DUMBBELL -> 8
        Equipment.KETTLEBELL -> 5
        Equipment.MACHINE -> 5
        Equipment.CABLE_MACHINE -> 5
        Equipment.BODYWEIGHT -> 0
        Equipment.BAND -> 0
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.DurationCalculatorTest"`
Expected: PASS — 7 tests.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(domain): add DurationCalculator with rep-aware formula"
```

---

## Task 2: `ExercisePacingEstimator` — per-exercise `secondsPerRep` learning (TDD)

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExercisePacingEstimator.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ExercisePacingEstimatorTest.kt`

- [ ] **Step 1: Write the failing test file**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/ExercisePacingEstimatorTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExercisePacingEstimatorTest {

    private fun exercise(id: Long, isUnilateral: Boolean = false) = Exercise(
        id = id,
        name = "Ex$id",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = Equipment.BARBELL,
        isUnilateral = isUnilateral,
    )

    private fun session(id: Long, startTime: Long) =
        WorkoutSession(id = id, startTime = startTime, endTime = startTime + 60_000L)

    private fun set(
        sessionId: Long,
        exerciseId: Long,
        setNumber: Int,
        completedAt: Long?,
        targetReps: Int = 10,
        actualReps: Int? = null,
        feedback: SetFeedback? = SetFeedback.RIR_2_4,
    ) = WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = setNumber,
        targetWeight = 80f,
        targetReps = targetReps,
        actualReps = actualReps,
        feedback = feedback,
        completedAt = completedAt,
    )

    private fun assertNear(expected: Float, actual: Float?, tol: Float = 0.001f) {
        assertNotNull(actual)
        assertTrue("expected ~$expected, got $actual", abs(expected - actual!!) < tol)
    }

    @Test
    fun emptyInput_yieldsNoLearnedValues() {
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = emptyList(),
            setsBySessionId = emptyMap(),
            exercisesById = emptyMap(),
        )
        assertNull(estimator.secondsPerRep(1L))
    }

    @Test
    fun singlePair_returnsExpectedSecondsPerRep() {
        // Set 1 at t=60s, set 2 at t=240s. delta = 180s. workTime = 180 - 90 = 90s.
        // reps = 8 (targetReps). secondsPerRep = 90 / 8 = 11.25.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 8),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 240_000L, targetReps = 8),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNear(11.25f, estimator.secondsPerRep(1L))
    }

    @Test
    fun unilateralExercise_dividesPerRepBySides() {
        // Same numbers as above, but unilateral. workTime/reps/sides = 90 / (8*2) = 5.625.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 8),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 240_000L, targetReps = 8),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L, isUnilateral = true)),
        )
        assertNear(5.625f, estimator.secondsPerRep(1L))
    }

    @Test
    fun multiplePairsInAppearance_averagedWithinAppearance() {
        // Set 1 at 60s, set 2 at 240s (delta 180, workTime 90, perRep 90/10 = 9.0).
        // Set 2 at 240s, set 3 at 390s (delta 150, workTime 60, perRep 60/10 = 6.0).
        // Average = 7.5.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 240_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 390_000L, targetReps = 10),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNear(7.5f, estimator.secondsPerRep(1L))
    }

    @Test
    fun pairWithHurtFeedbackOnEitherSide_isSkipped() {
        // First pair has HURT on set 2 → skipped. Second pair valid → 6.0.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 240_000L, targetReps = 10, feedback = SetFeedback.HURT),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 390_000L, targetReps = 10),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        // Only the (2,3) pair survives but set 2 has HURT → that pair also skipped.
        // No valid pairs → null.
        assertNull(estimator.secondsPerRep(1L))
    }

    @Test
    fun pairWithNullCompletedAt_isSkipped() {
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = null, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 390_000L, targetReps = 10),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        // Both candidate pairs touch the null-completedAt set → no valid samples → null.
        assertNull(estimator.secondsPerRep(1L))
    }

    @Test
    fun sampleBelowMinSecondsPerRep_isSkipped() {
        // Set 1 at 60s, set 2 at 152s. delta = 92s. workTime = 2s. reps = 10 → 0.2 s/rep. Below 1.0 → skip.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 152_000L, targetReps = 10),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNull(estimator.secondsPerRep(1L))
    }

    @Test
    fun sampleAboveMaxSecondsPerRep_isSkipped() {
        // workTime = 600s, reps = 10 → 60 s/rep, above 30 → skip.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 750_000L, targetReps = 10),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNull(estimator.secondsPerRep(1L))
    }

    @Test
    fun actualReps_takesPrecedenceOverTargetReps() {
        // delta = 240s → workTime = 150s. reps = actualReps (5) → 30 s/rep — at the ceiling.
        // (At-or-below 30.0 passes by the !in 1f..30f bound, so this should be valid.)
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10, actualReps = 5),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 300_000L, targetReps = 10, actualReps = 5),
        ))
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNear(30.0f, estimator.secondsPerRep(1L))
    }

    @Test
    fun multipleSessions_averagedAcrossAppearances() {
        // Session 20: one pair → 9.0 s/rep. Session 10: one pair → 6.0 s/rep. Mean = 7.5.
        val sessions = listOf(
            session(id = 20L, startTime = 1_000_000L),
            session(id = 10L, startTime = 0L),
        )
        val sets = mapOf(
            20L to listOf(
                set(20L, exerciseId = 1L, setNumber = 1, completedAt = 1_000_000L + 60_000L, targetReps = 10),
                set(20L, exerciseId = 1L, setNumber = 2, completedAt = 1_000_000L + 240_000L, targetReps = 10),
            ),
            10L to listOf(
                set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L, targetReps = 10),
                set(10L, exerciseId = 1L, setNumber = 2, completedAt = 210_000L, targetReps = 10),
            ),
        )
        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        assertNear(7.5f, estimator.secondsPerRep(1L))
    }

    @Test
    fun maxAppearancesCap_keepsOnlyTheNewest() {
        // 11 sessions; session N produces a single pair with perRep = N seconds.
        // Newest-first session IDs are 11..1. Keeping the 10 newest yields N=2..11, mean = 6.5.
        val sessions = (1..11).map { n ->
            session(id = n.toLong(), startTime = (n * 10_000_000L))
        }.sortedByDescending { it.startTime }

        val sets = (1..11).associate { n ->
            val start = n * 10_000_000L
            val workTimeSec = n * 10L                // → perRep = n s (workTime / 10 reps)
            val deltaMs = (workTimeSec + 90L) * 1000L // delta = workTime + REST_SECONDS
            n.toLong() to listOf(
                set(n.toLong(), exerciseId = 1L, setNumber = 1, completedAt = start, targetReps = 10),
                set(n.toLong(), exerciseId = 1L, setNumber = 2, completedAt = start + deltaMs, targetReps = 10),
            )
        }

        val estimator = ExercisePacingEstimator.build(
            sessionsNewestFirst = sessions,
            setsBySessionId = sets,
            exercisesById = mapOf(1L to exercise(1L)),
        )
        // 10 newest: N = 11, 10, 9, 8, 7, 6, 5, 4, 3, 2. Mean = (2+11)*10/2/10 = 6.5.
        assertNear(6.5f, estimator.secondsPerRep(1L))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ExercisePacingEstimatorTest"`
Expected: FAIL with `Unresolved reference: ExercisePacingEstimator`.

- [ ] **Step 3: Implement `ExercisePacingEstimator`**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExercisePacingEstimator.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

class ExercisePacingEstimator(
    private val secondsPerRepByExerciseId: Map<Long, Float>,
) {
    fun secondsPerRep(exerciseId: Long): Float? = secondsPerRepByExerciseId[exerciseId]

    companion object {
        const val MAX_APPEARANCES = 10
        const val MIN_SECONDS_PER_REP = 1.0f
        const val MAX_SECONDS_PER_REP = 30.0f

        val EMPTY = ExercisePacingEstimator(emptyMap())

        fun build(
            sessionsNewestFirst: List<WorkoutSession>,
            setsBySessionId: Map<Long, List<WorkoutSet>>,
            exercisesById: Map<Long, Exercise>,
        ): ExercisePacingEstimator {
            val appearancesByExercise = mutableMapOf<Long, MutableList<Float>>()

            for (session in sessionsNewestFirst) {
                val sessionSets = setsBySessionId[session.id] ?: continue
                if (sessionSets.isEmpty()) continue

                val byExercise = sessionSets.groupBy { it.exerciseId }
                for ((exerciseId, exerciseSets) in byExercise) {
                    val existing = appearancesByExercise[exerciseId]
                    if (existing != null && existing.size >= MAX_APPEARANCES) continue

                    val exercise = exercisesById[exerciseId] ?: continue
                    val sides = if (exercise.isUnilateral) 2 else 1

                    val appearanceAvg = appearanceAverage(
                        exerciseSets = exerciseSets,
                        sides = sides,
                    ) ?: continue

                    appearancesByExercise.getOrPut(exerciseId) { mutableListOf() }.add(appearanceAvg)
                }
            }

            val perExercise = appearancesByExercise.mapValues { (_, samples) ->
                samples.average().toFloat()
            }
            return ExercisePacingEstimator(perExercise)
        }

        private fun appearanceAverage(exerciseSets: List<WorkoutSet>, sides: Int): Float? {
            val sorted = exerciseSets.sortedBy { it.setNumber }
            val samples = mutableListOf<Float>()
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val curr = sorted[i]
                if (prev.feedback == SetFeedback.HURT || curr.feedback == SetFeedback.HURT) continue
                val prevAt = prev.completedAt ?: continue
                val currAt = curr.completedAt ?: continue
                val deltaSec = ((currAt - prevAt) / 1000L).toInt()
                val workTimeSec = deltaSec - DurationCalculator.REST_SECONDS
                val reps = curr.actualReps ?: curr.targetReps
                if (reps <= 0) continue
                val perRep = workTimeSec.toFloat() / (reps * sides)
                if (perRep !in MIN_SECONDS_PER_REP..MAX_SECONDS_PER_REP) continue
                samples.add(perRep)
            }
            if (samples.isEmpty()) return null
            return samples.average().toFloat()
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ExercisePacingEstimatorTest"`
Expected: PASS — 11 tests.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(domain): add ExercisePacingEstimator (learns secondsPerRep per exercise)"
```

---

## Task 3: Switch model + planner to `DurationCalculator` + `ExercisePacingEstimator`

This task touches the model (`PlannedExercise`), the planner, and three test files **in a single commit** because the changes are mutually dependent — splitting them would leave the build broken between commits.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/PlannedExercise.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/PlannedExerciseTest.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlanTest.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt`

- [ ] **Step 1: Rewrite `PlannedExercise.kt`**

Replace the entire file `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/PlannedExercise.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Exercise

data class PlannedExercise(
    val exercise: Exercise,
    val sessionWeight: Float = 0f,
    val originalSessionWeight: Float = sessionWeight,
    val sessionReps: Int = 10,
    val warmupSets: List<WarmupSet> = emptyList(),
    val estimatedSeconds: Int = 0,
) {
    companion object {
        const val DEFAULT_SETS = 3
    }
}
```

- [ ] **Step 2: Rewrite `PlannedExerciseTest.kt`**

Replace the entire file `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/PlannedExerciseTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannedExerciseTest {

    private fun exercise() = Exercise(
        id = 1L,
        name = "Ex",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = Equipment.BARBELL,
    )

    @Test
    fun estimatedSeconds_defaultsToZero() {
        val pe = PlannedExercise(exercise = exercise())
        assertEquals(0, pe.estimatedSeconds)
    }

    @Test
    fun estimatedSeconds_isStoredFromConstructor() {
        val pe = PlannedExercise(exercise = exercise(), estimatedSeconds = 612)
        assertEquals(612, pe.estimatedSeconds)
    }
}
```

- [ ] **Step 3: Rewrite `WorkoutPlanTest.kt`**

Replace the entire file `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlanTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutPlanTest {

    private fun ex(id: Long) = Exercise(
        id = id,
        name = "Ex$id",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = Equipment.BARBELL,
    )

    @Test
    fun estimatedDurationSeconds_sumsEstimatedSecondsAcrossExercises() {
        val plan = WorkoutPlan(
            exercises = listOf(
                PlannedExercise(exercise = ex(1L), estimatedSeconds = 500),
                PlannedExercise(exercise = ex(2L), estimatedSeconds = 700),
            ),
            locationId = null,
        )
        assertEquals(1200, plan.estimatedDurationSeconds)
    }

    @Test
    fun estimatedDurationSeconds_isZeroWhenAllExercisesAreZero() {
        val plan = WorkoutPlan(
            exercises = listOf(
                PlannedExercise(exercise = ex(1L)),
                PlannedExercise(exercise = ex(2L)),
            ),
            locationId = null,
        )
        assertEquals(0, plan.estimatedDurationSeconds)
    }
}
```

- [ ] **Step 4: Replace `durationEstimator` field in the planner**

In `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`, change the constructor parameter:

```kotlin
private val durationEstimator: ExerciseDurationEstimator = ExerciseDurationEstimator.EMPTY,
```

to:

```kotlin
private val pacingEstimator: ExercisePacingEstimator = ExercisePacingEstimator.EMPTY,
```

- [ ] **Step 5: Rewrite `withWeight`**

Replace the `private fun withWeight(...)` function (currently at the bottom of `WorkoutPlanner.kt`, around lines 121-136) with:

```kotlin
    private fun withWeight(pe: PlannedExercise, sessionReps: Int): PlannedExercise {
        val perRep = pacingEstimator.secondsPerRep(pe.exercise.id)
        if (pe.exercise.isTimed) {
            val timedReps = 60
            return pe.copy(
                sessionWeight = 0f,
                sessionReps = timedReps,
                warmupSets = emptyList(),
                estimatedSeconds = DurationCalculator.estimate(
                    exercise = pe.exercise,
                    sessionReps = timedReps,
                    numSets = PlannedExercise.DEFAULT_SETS,
                    warmupSets = emptyList(),
                    secondsPerRep = perRep,
                ),
            )
        }
        val weight = weightForExercise(pe.exercise, sessionReps)
        val warmups = computeWarmupSets(weight)
        return pe.copy(
            sessionWeight = weight,
            sessionReps = sessionReps,
            warmupSets = warmups,
            estimatedSeconds = DurationCalculator.estimate(
                exercise = pe.exercise,
                sessionReps = sessionReps,
                numSets = PlannedExercise.DEFAULT_SETS,
                warmupSets = warmups,
                secondsPerRep = perRep,
            ),
        )
    }
```

- [ ] **Step 6: Rewrite `recomputeExercise`**

Replace the `fun recomputeExercise(...)` function (lines 89-100) with:

```kotlin
    fun recomputeExercise(pe: PlannedExercise, newBaselineKg: Float): PlannedExercise {
        val coeff = coefficientSource.get(pe.exercise) ?: return pe
        if (coeff <= 0f) return pe
        val newWeight = WeightFormatter.round(
            progressionEngine.fromOneRepMax(newBaselineKg * coeff, pe.sessionReps),
            weightUnit,
        )
        val warmups = if (pe.exercise.isTimed) emptyList() else computeWarmupSets(newWeight)
        val perRep = pacingEstimator.secondsPerRep(pe.exercise.id)
        return pe.copy(
            sessionWeight = newWeight,
            warmupSets = warmups,
            estimatedSeconds = DurationCalculator.estimate(
                exercise = pe.exercise,
                sessionReps = pe.sessionReps,
                numSets = PlannedExercise.DEFAULT_SETS,
                warmupSets = warmups,
                secondsPerRep = perRep,
            ),
        )
    }
```

- [ ] **Step 7: Update `WorkoutPlannerTest.kt`**

In `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt`:

(a) Replace the `planner(...)` helper's parameter `durationEstimator: ExerciseDurationEstimator = ExerciseDurationEstimator.EMPTY,` and its usage with the pacing estimator (lines 38, 47):

```kotlin
    private fun planner(
        exercises: List<Exercise> = emptyList(),
        strengths: Map<MuscleGroup, MuscleGroupStrength> = emptyMap(),
        random: Random = Random(0),
        recentHistory: Map<Long, List<WorkoutSet>> = emptyMap(),
        nowMs: Long = System.currentTimeMillis(),
        pacingEstimator: ExercisePacingEstimator = ExercisePacingEstimator.EMPTY,
    ) = WorkoutPlanner(
        availableExercises = exercises,
        strengths = strengths,
        recentHistory = recentHistory,
        weightUnit = WeightUnit.KG,
        locationId = null,
        random = random,
        nowMs = nowMs,
        pacingEstimator = pacingEstimator,
    )
```

(b) Replace the last two tests (currently `generated plan stamps duration override...` and `generated plan leaves override null...`, lines 496-523) with:

```kotlin
    @Test
    fun `generated plan stamps estimated seconds using learned secondsPerRep`() {
        val ex = exercise(id = 1L, name = "Barbell Bench Press", muscle = MuscleGroup.CHEST)
        val estimator = ExercisePacingEstimator(mapOf(1L to 5.0f))
        val p = planner(
            exercises = listOf(ex),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f),
            pacingEstimator = estimator,
        )

        val plan = p.generateWorkout(sessionReps = 8)
        val planned = plan.exercises.single()

        // Expected via DurationCalculator: barbell, 3 sets, 8 reps, perRep = 5.0, warmups
        // depend on weight from baseline 100kg + coeff 1.0 + reps 8.
        val expected = DurationCalculator.estimate(
            exercise = ex,
            sessionReps = 8,
            numSets = PlannedExercise.DEFAULT_SETS,
            warmupSets = planned.warmupSets,
            secondsPerRep = 5.0f,
        )
        assertEquals(expected, planned.estimatedSeconds)
    }

    @Test
    fun `generated plan uses default secondsPerRep when estimator has no value`() {
        val ex = exercise(id = 1L, name = "Barbell Bench Press", muscle = MuscleGroup.CHEST)
        val p = planner(
            exercises = listOf(ex),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f),
        )

        val plan = p.generateWorkout(sessionReps = 8)
        val planned = plan.exercises.single()

        val expected = DurationCalculator.estimate(
            exercise = ex,
            sessionReps = 8,
            numSets = PlannedExercise.DEFAULT_SETS,
            warmupSets = planned.warmupSets,
            secondsPerRep = null,
        )
        assertEquals(expected, planned.estimatedSeconds)
    }

    @Test
    fun `repriceForReps recomputes estimated seconds at the new rep target`() {
        val ex = exercise(id = 1L, name = "Barbell Bench Press", muscle = MuscleGroup.CHEST)
        val p = planner(
            exercises = listOf(ex),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f),
        )

        val planA = p.generateWorkout(sessionReps = 5)
        val planB = p.repriceForReps(planA, repMin = 15, repMax = 15)

        val a = planA.exercises.single()
        val b = planB.exercises.single()
        assertEquals(15, b.sessionReps)

        val expectedB = DurationCalculator.estimate(
            exercise = ex,
            sessionReps = 15,
            numSets = PlannedExercise.DEFAULT_SETS,
            warmupSets = b.warmupSets,
            secondsPerRep = null,
        )
        assertEquals(expectedB, b.estimatedSeconds)
        assertTrue(
            "estimates should differ across rep targets",
            a.estimatedSeconds != b.estimatedSeconds,
        )
    }
```

- [ ] **Step 8: Run the unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — all tests including the new ones from Tasks 1, 2, and the rewritten model + planner tests.

If the build fails because `ExerciseDurationEstimator` is still imported somewhere (it shouldn't be, but double-check), fix the import to `ExercisePacingEstimator` and rerun.

- [ ] **Step 9: Commit**

```bash
jj commit -m "feat(planner): rep-aware estimatedSeconds via DurationCalculator + ExercisePacingEstimator"
```

---

## Task 4: Switch `WorkoutRepository` to build `ExercisePacingEstimator`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [ ] **Step 1: Update `buildPlanner`**

In `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`, around line 67 (where `durationEstimator` is built), replace:

```kotlin
        val durationEstimator = ExerciseDurationEstimator.build(recentSessions, recentSets)
```

with:

```kotlin
        val exercisesById = available.associateBy { it.id }
        val pacingEstimator = ExercisePacingEstimator.build(recentSessions, recentSets, exercisesById)
```

And in the `WorkoutPlanner(...)` constructor invocation (around line 77), replace:

```kotlin
            durationEstimator = durationEstimator,
```

with:

```kotlin
            pacingEstimator = pacingEstimator,
```

- [ ] **Step 2: Build the project**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the unit test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
jj commit -m "feat(repo): wire ExercisePacingEstimator into WorkoutRepository"
```

---

## Task 5: Delete `ExerciseDurationEstimator` and its test

**Files:**
- Delete: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimator.kt`
- Delete: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimatorTest.kt`

- [ ] **Step 1: Verify no references remain**

Run: `grep -rn "ExerciseDurationEstimator" app/src --include="*.kt"`
Expected: no output (no remaining references after Tasks 3 and 4).

If output is non-empty, fix the reference before deleting the files.

- [ ] **Step 2: Delete the files**

```bash
rm app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimator.kt
rm app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimatorTest.kt
```

- [ ] **Step 3: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS — full suite, no compile errors.

- [ ] **Step 4: Run instrumented tests**

Run: `./gradlew :app:connectedAndroidTest`
Expected: PASS. (Memory notes the emulator is typically running. If it isn't, surface that and stop — don't try to launch one.)

- [ ] **Step 5: Commit**

```bash
jj commit -m "chore(domain): remove obsolete ExerciseDurationEstimator"
```

---

## Task 6: Final build sweep

- [ ] **Step 1: Assemble + lint**

Run: `./gradlew :app:assembleDebug :app:lint`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Confirm the rep-range slider drives the displayed duration**

Sanity-check the goal manually (no UI test required):

```bash
grep -n "estimatedDurationSeconds" app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/PlanPreviewContent.kt
```

Expected output line: `val durationMin = plan.estimatedDurationSeconds / 60`. Confirms the preview already reads through the model's `estimatedDurationSeconds`, which sums the new `estimatedSeconds` fields — no UI wiring change needed.

- [ ] **Step 3: Nothing to commit** unless `lint` introduced a baseline change. If it did:

```bash
jj commit -m "chore: lint baseline update"
```

Otherwise, the branch is ready for the user to review.
