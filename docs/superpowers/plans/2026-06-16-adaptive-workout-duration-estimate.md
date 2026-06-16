# Adaptive Workout Duration Estimate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the constant-driven per-exercise time estimate in `WorkoutPlan` with a per-exercise mean of recent wall-clock observations, falling back to the existing formula when there's no usable history.

**Architecture:** A pure `ExerciseDurationEstimator` is built at plan-build time from the last 50 completed sessions. It measures wall-clock per appearance (from the previous-exercise's last completedAt — or session.startTime — to this exercise's last completedAt), skips appearances with HURT feedback / null timestamps / out-of-clamp values, and averages up to the 10 most recent qualifying appearances per exercise. `WorkoutPlanner.withWeight` stamps the learned value onto a new `PlannedExercise.estimatedSecondsOverride` field, and `WorkoutPlan.estimatedDurationSeconds` reads it (falling back to the existing formula).

**Tech Stack:** Kotlin, Room (Android), JUnit4.

**Spec:** `docs/superpowers/specs/2026-06-15-adaptive-workout-duration-estimate-design.md`

---

## File Map

- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimator.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimatorTest.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/PlannedExercise.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/PlannedExerciseTest.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlan.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlanTest.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSessionDao.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSetDao.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

---

## Task 1: `ExerciseDurationEstimator` — pure domain class

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimator.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimatorTest.kt`

- [ ] **Step 1.1: Write the failing test file**

```kotlin
// app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimatorTest.kt
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseDurationEstimatorTest {

    private fun session(id: Long, startTime: Long) =
        WorkoutSession(id = id, startTime = startTime, endTime = startTime + 60_000L)

    private fun set(
        sessionId: Long,
        exerciseId: Long,
        setNumber: Int,
        completedAt: Long?,
        feedback: SetFeedback? = SetFeedback.RIR_2_4,
    ) = WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = setNumber,
        targetWeight = 80f,
        targetReps = 8,
        feedback = feedback,
        completedAt = completedAt,
    )

    @Test
    fun `empty input yields no learned values`() {
        val estimator = ExerciseDurationEstimator.build(
            sessionsNewestFirst = emptyList(),
            setsBySessionId = emptyMap(),
        )
        assertNull(estimator.secondsFor(1L))
    }

    @Test
    fun `single appearance with predecessor returns wall-clock from predecessor end to last set`() {
        // Session at T=0; exercise 1 last set at T+60s; exercise 2 first set at T+120s, last at T+360s.
        // For exercise 2, predecessorEnd = 60_000, end = 360_000, duration = 300s.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L),
            set(10L, exerciseId = 2L, setNumber = 1, completedAt = 120_000L),
            set(10L, exerciseId = 2L, setNumber = 2, completedAt = 240_000L),
            set(10L, exerciseId = 2L, setNumber = 3, completedAt = 360_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertEquals(300, estimator.secondsFor(2L))
    }

    @Test
    fun `first exercise in session uses session startTime as predecessor`() {
        // session startTime = 1000; exercise 1's last set at 1000 + 240_000 ms → 240s.
        val sessions = listOf(session(id = 10L, startTime = 1_000L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 1_000L + 80_000L),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 1_000L + 160_000L),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 1_000L + 240_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertEquals(240, estimator.secondsFor(1L))
    }

    @Test
    fun `appearance with HURT feedback in any set is skipped`() {
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 120_000L, feedback = SetFeedback.RIR_2_4),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 180_000L, feedback = SetFeedback.HURT),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertNull(estimator.secondsFor(1L))
    }

    @Test
    fun `appearance with any null completedAt is skipped`() {
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 120_000L),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = null),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 360_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertNull(estimator.secondsFor(1L))
    }

    @Test
    fun `appearance shorter than MIN_SECONDS is skipped`() {
        // 40s wall-clock from session start, below the 60s floor.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 10_000L),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 25_000L),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 40_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertNull(estimator.secondsFor(1L))
    }

    @Test
    fun `appearance longer than MAX_SECONDS is skipped`() {
        // 1500s wall-clock from session start, above the 1200s ceiling.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 500_000L),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 1_000_000L),
            set(10L, exerciseId = 1L, setNumber = 3, completedAt = 1_500_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertNull(estimator.secondsFor(1L))
    }

    @Test
    fun `multiple appearances are averaged and rounded`() {
        // Two sessions, each with one appearance of exercise 1.
        // Session 20: 300s. Session 10: 360s. Mean = 330.
        val sessions = listOf(
            session(id = 20L, startTime = 1_000_000L),
            session(id = 10L, startTime = 0L),
        )
        val sets = mapOf(
            20L to listOf(
                set(20L, exerciseId = 1L, setNumber = 1, completedAt = 1_000_000L + 100_000L),
                set(20L, exerciseId = 1L, setNumber = 2, completedAt = 1_000_000L + 200_000L),
                set(20L, exerciseId = 1L, setNumber = 3, completedAt = 1_000_000L + 300_000L),
            ),
            10L to listOf(
                set(10L, exerciseId = 1L, setNumber = 1, completedAt = 120_000L),
                set(10L, exerciseId = 1L, setNumber = 2, completedAt = 240_000L),
                set(10L, exerciseId = 1L, setNumber = 3, completedAt = 360_000L),
            ),
        )

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertEquals(330, estimator.secondsFor(1L))
    }

    @Test
    fun `only the most recent MAX_APPEARANCES are kept per exercise`() {
        // 11 sessions, exercise 1 appears once in each. Session N has duration 100 + N seconds.
        // Newest-first: durations 111, 110, 109, ..., 102 → 10 newest = 111..102, mean = 106 (rounded from 106.5).
        // Note: kotlin's Double.toInt() truncates; spec says round(mean(samples)). 106.5 → 107 with half-up rounding.
        val sessions = (1..11).map { n ->
            session(id = n.toLong(), startTime = (n * 10_000_000L))
        }.sortedByDescending { it.startTime }

        val sets = (1..11).associate { n ->
            val start = n * 10_000_000L
            val durationMs = (100 + n) * 1000L
            n.toLong() to listOf(
                set(n.toLong(), exerciseId = 1L, setNumber = 1, completedAt = start + durationMs / 3),
                set(n.toLong(), exerciseId = 1L, setNumber = 2, completedAt = start + 2 * durationMs / 3),
                set(n.toLong(), exerciseId = 1L, setNumber = 3, completedAt = start + durationMs),
            )
        }

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        // 10 newest durations: 111..102 inclusive. Sum = (111+102)*10/2 = 1065. Mean = 106.5 → 107.
        assertEquals(107, estimator.secondsFor(1L))
    }

    @Test
    fun `predecessor uses max completedAt before this exercise's first set across all exercises`() {
        // Session startTime = 0. Exercise 1's sets at 60_000 and 120_000.
        // Exercise 2 starts at 130_000. Predecessor for exercise 2 = 120_000 (exercise 1's last).
        // Exercise 2's last set at 130_000 + 200_000 = 330_000. Duration = 210s.
        val sessions = listOf(session(id = 10L, startTime = 0L))
        val sets = mapOf(10L to listOf(
            set(10L, exerciseId = 1L, setNumber = 1, completedAt = 60_000L),
            set(10L, exerciseId = 1L, setNumber = 2, completedAt = 120_000L),
            set(10L, exerciseId = 2L, setNumber = 1, completedAt = 130_000L),
            set(10L, exerciseId = 2L, setNumber = 2, completedAt = 220_000L),
            set(10L, exerciseId = 2L, setNumber = 3, completedAt = 330_000L),
        ))

        val estimator = ExerciseDurationEstimator.build(sessions, sets)

        assertEquals(210, estimator.secondsFor(2L))
    }
}
```

- [ ] **Step 1.2: Run the test — verify compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ExerciseDurationEstimatorTest"`

Expected: Compile error — `ExerciseDurationEstimator` does not exist.

- [ ] **Step 1.3: Create the implementation**

```kotlin
// app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimator.kt
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.roundToInt

class ExerciseDurationEstimator(
    private val secondsByExerciseId: Map<Long, Int>,
) {
    fun secondsFor(exerciseId: Long): Int? = secondsByExerciseId[exerciseId]

    companion object {
        const val MAX_APPEARANCES = 10
        const val MIN_SECONDS = 60
        const val MAX_SECONDS = 1200

        val EMPTY = ExerciseDurationEstimator(emptyMap())

        fun build(
            sessionsNewestFirst: List<WorkoutSession>,
            setsBySessionId: Map<Long, List<WorkoutSet>>,
        ): ExerciseDurationEstimator {
            val samplesByExercise = mutableMapOf<Long, MutableList<Int>>()

            for (session in sessionsNewestFirst) {
                val sessionSets = setsBySessionId[session.id] ?: continue
                if (sessionSets.isEmpty()) continue

                val byExercise = sessionSets.groupBy { it.exerciseId }
                for ((exerciseId, exerciseSets) in byExercise) {
                    val existing = samplesByExercise[exerciseId]
                    if (existing != null && existing.size >= MAX_APPEARANCES) continue

                    val sample = measureAppearance(
                        exerciseSets = exerciseSets,
                        sessionSets = sessionSets,
                        sessionStartTime = session.startTime,
                    ) ?: continue

                    samplesByExercise.getOrPut(exerciseId) { mutableListOf() }.add(sample)
                }
            }

            val means = samplesByExercise.mapValues { (_, samples) ->
                samples.average().roundToInt()
            }
            return ExerciseDurationEstimator(means)
        }

        private fun measureAppearance(
            exerciseSets: List<WorkoutSet>,
            sessionSets: List<WorkoutSet>,
            sessionStartTime: Long,
        ): Int? {
            if (exerciseSets.any { it.feedback == SetFeedback.HURT }) return null
            if (exerciseSets.any { it.completedAt == null }) return null

            val completedTimes = exerciseSets.mapNotNull { it.completedAt }
            if (completedTimes.isEmpty()) return null

            val firstCompleted = completedTimes.min()
            val lastCompleted = completedTimes.max()

            val predecessorEnd = sessionSets
                .mapNotNull { it.completedAt }
                .filter { it < firstCompleted }
                .maxOrNull()
                ?: sessionStartTime

            val durationSec = ((lastCompleted - predecessorEnd) / 1000L).toInt()
            return if (durationSec in MIN_SECONDS..MAX_SECONDS) durationSec else null
        }
    }
}
```

- [ ] **Step 1.4: Run the test — verify all pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ExerciseDurationEstimatorTest"`

Expected: All 10 tests pass.

- [ ] **Step 1.5: Commit**

```bash
git add \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimator.kt \
  app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseDurationEstimatorTest.kt
git commit -m "feat(domain): add ExerciseDurationEstimator

Pure domain class that derives a per-exercise wall-clock mean from recent
session history. Skips HURT/null/out-of-clamp samples; caps at 10 most
recent appearances per exercise."
```

---

## Task 2: Add `estimatedSecondsOverride` to `PlannedExercise`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/PlannedExercise.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/PlannedExerciseTest.kt`

- [ ] **Step 2.1: Write the failing test**

```kotlin
// app/src/test/java/io/github/fowles/stochastic_strength/domain/model/PlannedExerciseTest.kt
package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannedExerciseTest {

    private fun exercise(
        isTimed: Boolean = false,
        isUnilateral: Boolean = false,
    ) = Exercise(
        id = 1L,
        name = "Ex",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = Equipment.BARBELL,
        isTimed = isTimed,
        isUnilateral = isUnilateral,
    )

    @Test
    fun `estimatedSeconds uses formula when override is null`() {
        val pe = PlannedExercise(exercise = exercise(), warmupSets = emptyList())
        // 3 sets × 135s + 0 warmups = 405
        assertEquals(405, pe.estimatedSeconds)
    }

    @Test
    fun `estimatedSeconds uses formula plus warmups when override is null`() {
        val pe = PlannedExercise(
            exercise = exercise(),
            warmupSets = listOf(WarmupSet(40f, 8), WarmupSet(60f, 5)),
        )
        // 3 × 135 + 2 × 60 = 525
        assertEquals(525, pe.estimatedSeconds)
    }

    @Test
    fun `estimatedSeconds returns override when present`() {
        val pe = PlannedExercise(
            exercise = exercise(),
            estimatedSecondsOverride = 612,
        )
        assertEquals(612, pe.estimatedSeconds)
    }

    @Test
    fun `estimatedSeconds override wins regardless of warmup count`() {
        val pe = PlannedExercise(
            exercise = exercise(),
            warmupSets = listOf(WarmupSet(40f, 8), WarmupSet(60f, 5)),
            estimatedSecondsOverride = 800,
        )
        assertEquals(800, pe.estimatedSeconds)
    }
}
```

- [ ] **Step 2.2: Run the test — verify compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.model.PlannedExerciseTest"`

Expected: Compile errors — `estimatedSecondsOverride` parameter and `estimatedSeconds` property do not exist.

- [ ] **Step 2.3: Update `PlannedExercise`**

Replace the entire body of `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/PlannedExercise.kt` with:

```kotlin
package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Exercise

data class PlannedExercise(
    val exercise: Exercise,
    val sessionWeight: Float = 0f,
    val originalSessionWeight: Float = sessionWeight,
    val sessionReps: Int = 10,
    val warmupSets: List<WarmupSet> = emptyList(),
    val estimatedSecondsOverride: Int? = null,
) {
    val secondsPerSet: Int = when {
        exercise.isTimed -> SECONDS_PER_TIMED_SET
        exercise.isUnilateral -> SECONDS_PER_UNILATERAL_SET
        else -> SECONDS_PER_SET
    }

    val estimatedSeconds: Int
        get() = estimatedSecondsOverride
            ?: (DEFAULT_SETS * secondsPerSet + warmupSets.size * SECONDS_PER_WARMUP_SET)

    companion object {
        const val DEFAULT_SETS = 3
        const val SECONDS_PER_SET = 135
        const val SECONDS_PER_UNILATERAL_SET = 180
        const val SECONDS_PER_WARMUP_SET = 60
        const val SECONDS_PER_TIMED_SET = 90
    }
}
```

- [ ] **Step 2.4: Run the test — verify all pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.model.PlannedExerciseTest"`

Expected: All 4 tests pass.

- [ ] **Step 2.5: Commit**

```bash
git add \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/model/PlannedExercise.kt \
  app/src/test/java/io/github/fowles/stochastic_strength/domain/model/PlannedExerciseTest.kt
git commit -m "feat(domain): add estimatedSecondsOverride to PlannedExercise

Adds an optional learned wall-clock value that, when present, replaces
the formula in estimatedSeconds. Default (null) preserves existing
behavior."
```

---

## Task 3: `WorkoutPlan.estimatedDurationSeconds` consumes the override

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlan.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlanTest.kt`

- [ ] **Step 3.1: Write the failing test**

```kotlin
// app/src/test/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlanTest.kt
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
    fun `estimatedDurationSeconds sums formula when no exercises have overrides`() {
        val plan = WorkoutPlan(
            exercises = listOf(
                PlannedExercise(exercise = ex(1L)),
                PlannedExercise(exercise = ex(2L)),
            ),
            locationId = null,
        )
        // each: 3 × 135 + 0 = 405. Total 810.
        assertEquals(810, plan.estimatedDurationSeconds)
    }

    @Test
    fun `estimatedDurationSeconds sums override and formula across exercises`() {
        val plan = WorkoutPlan(
            exercises = listOf(
                PlannedExercise(exercise = ex(1L), estimatedSecondsOverride = 500),
                PlannedExercise(exercise = ex(2L)),
            ),
            locationId = null,
        )
        // 500 + (3 × 135) = 905.
        assertEquals(905, plan.estimatedDurationSeconds)
    }

    @Test
    fun `estimatedDurationSeconds with all overrides simply sums them`() {
        val plan = WorkoutPlan(
            exercises = listOf(
                PlannedExercise(exercise = ex(1L), estimatedSecondsOverride = 500),
                PlannedExercise(exercise = ex(2L), estimatedSecondsOverride = 700),
            ),
            locationId = null,
        )
        assertEquals(1200, plan.estimatedDurationSeconds)
    }
}
```

- [ ] **Step 3.2: Run the test — verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.model.WorkoutPlanTest"`

Expected: Tests 2 and 3 fail — `estimatedDurationSeconds` currently uses `DEFAULT_SETS * secondsPerSet + warmups * SECONDS_PER_WARMUP_SET` directly and ignores the override. Test 1 may pass coincidentally.

- [ ] **Step 3.3: Update `WorkoutPlan`**

Replace the entire body of `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlan.kt` with:

```kotlin
package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.MuscleGroup

data class WorkoutPlan(
    val exercises: List<PlannedExercise>,
    val locationId: Long?,
    val sessionReps: Int = 10,
    val sessionRejectedIds: Set<Long> = emptySet(),
    val strengthOverrides: Map<MuscleGroup, Float> = emptyMap(),
) {
    val estimatedDurationSeconds: Int
        get() = exercises.sumOf { it.estimatedSeconds }
}
```

- [ ] **Step 3.4: Run the test — verify all pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.model.WorkoutPlanTest"`

Expected: All 3 tests pass.

- [ ] **Step 3.5: Commit**

```bash
git add \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlan.kt \
  app/src/test/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlanTest.kt
git commit -m "refactor(domain): WorkoutPlan.estimatedDurationSeconds reads per-exercise

Delegates to PlannedExercise.estimatedSeconds so any override set on a
planned exercise flows through to the plan-level total."
```

---

## Task 4: DAO queries for recent sessions and their sets

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSessionDao.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSetDao.kt`

No new unit test needed — Room generates the implementation; we'll verify the queries through Task 5's integration with the planner.

- [ ] **Step 4.1: Add query to `WorkoutSessionDao`**

Add this method inside the `WorkoutSessionDao` interface (between any two existing methods):

```kotlin
    @Query("""
        SELECT * FROM workout_sessions
        WHERE endTime IS NOT NULL
        ORDER BY startTime DESC
        LIMIT :limit
    """)
    suspend fun getRecentCompletedSessions(limit: Int): List<WorkoutSession>
```

- [ ] **Step 4.2: Add query to `WorkoutSetDao`**

Add this method inside the `WorkoutSetDao` interface:

```kotlin
    @Query("""
        SELECT * FROM workout_sets
        WHERE sessionId IN (:sessionIds)
          AND completedAt IS NOT NULL
    """)
    suspend fun getSetsForSessions(sessionIds: List<Long>): List<WorkoutSet>
```

- [ ] **Step 4.3: Verify Room compiles the project**

Run: `./gradlew :app:assembleDebug`

Expected: Build succeeds. (Room annotation processing validates the queries at compile time.)

- [ ] **Step 4.4: Commit**

```bash
git add \
  app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSessionDao.kt \
  app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSetDao.kt
git commit -m "feat(data): DAO queries for recent completed sessions and their sets

WorkoutSessionDao.getRecentCompletedSessions and
WorkoutSetDao.getSetsForSessions feed the duration estimator."
```

---

## Task 5: Wire the estimator through `WorkoutPlanner` and `WorkoutRepository`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [ ] **Step 5.1: Add the new constructor parameter to `WorkoutPlanner`**

In `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`, update the class constructor (currently lines 17–27):

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
    private val durationEstimator: ExerciseDurationEstimator = ExerciseDurationEstimator.EMPTY,
) {
```

- [ ] **Step 5.2: Use the estimator inside `withWeight`**

Replace the existing `withWeight` (currently lines 111–119) with:

```kotlin
    private fun withWeight(pe: PlannedExercise, sessionReps: Int): PlannedExercise {
        val learned = durationEstimator.secondsFor(pe.exercise.id)
        if (pe.exercise.isTimed) return pe.copy(
            sessionWeight = 0f,
            sessionReps = 60,
            warmupSets = emptyList(),
            estimatedSecondsOverride = learned,
        )
        val weight = weightForExercise(pe.exercise, sessionReps)
        return pe.copy(
            sessionWeight = weight,
            sessionReps = sessionReps,
            warmupSets = computeWarmupSets(weight),
            estimatedSecondsOverride = learned,
        )
    }
```

Note: the original `withWeight` early-returns on `isTimed` before warmup logic — we preserve that and stamp the override in both branches.

- [ ] **Step 5.3: Update the test helper in `WorkoutPlannerTest`**

In `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt`, update the `planner(...)` helper (currently lines 32–46) to take an optional estimator:

```kotlin
    private fun planner(
        exercises: List<Exercise> = emptyList(),
        strengths: Map<MuscleGroup, MuscleGroupStrength> = emptyMap(),
        random: Random = Random(0),
        recentHistory: Map<Long, List<WorkoutSet>> = emptyMap(),
        nowMs: Long = System.currentTimeMillis(),
        durationEstimator: ExerciseDurationEstimator = ExerciseDurationEstimator.EMPTY,
    ) = WorkoutPlanner(
        availableExercises = exercises,
        strengths = strengths,
        recentHistory = recentHistory,
        weightUnit = WeightUnit.KG,
        locationId = null,
        random = random,
        nowMs = nowMs,
        durationEstimator = durationEstimator,
    )
```

- [ ] **Step 5.4: Add a new test for the wiring**

Append this test to `WorkoutPlannerTest`. The single-exercise pool keeps generation deterministic — this matches the pattern of `generateWorkout_producesCorrectWeightForKnownCoefficient`.

```kotlin
    @Test
    fun `generated plan stamps duration override from estimator onto planned exercise`() {
        val ex = exercise(id = 1L, name = "Barbell Bench Press", muscle = MuscleGroup.CHEST)
        val estimator = ExerciseDurationEstimator(mapOf(1L to 612))
        val p = planner(
            exercises = listOf(ex),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f),
            durationEstimator = estimator,
        )

        val plan = p.generateWorkout(sessionReps = 8)

        assertEquals(612, plan.exercises.single().estimatedSecondsOverride)
    }

    @Test
    fun `generated plan leaves override null when estimator has no value for exercise`() {
        val ex = exercise(id = 1L, name = "Barbell Bench Press", muscle = MuscleGroup.CHEST)
        val p = planner(
            exercises = listOf(ex),
            strengths = strengthsFor(MuscleGroup.CHEST to 100f),
            // default durationEstimator is EMPTY
        )

        val plan = p.generateWorkout(sessionReps = 8)

        assertEquals(null, plan.exercises.single().estimatedSecondsOverride)
    }
```

Add the import at the top of the test file if not already present:

```kotlin
import io.github.fowles.stochastic_strength.domain.ExerciseDurationEstimator
```

- [ ] **Step 5.5: Run the planner tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`

Expected: All existing tests still pass + the two new tests pass.

- [ ] **Step 5.6: Wire it in `WorkoutRepository.buildPlanner`**

In `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`, update `buildPlanner` (currently lines 43–69) to:

```kotlin
    suspend fun buildPlanner(
        locationId: Long?,
        weightUnit: WeightUnit,
        strengthOverrides: Map<MuscleGroup, Float> = emptyMap(),
    ): WorkoutPlanner {
        val excluded = excludedExerciseIds(locationId)
        val available = db.exerciseDao().getActive().filter { it.id !in excluded }
        val dbStrengths = db.muscleGroupStrengthDao().getAll().associateBy { it.muscleGroup }
        val strengths = if (strengthOverrides.isEmpty()) dbStrengths else
            dbStrengths + strengthOverrides.mapValues { (muscle, baseline) ->
                MuscleGroupStrength(muscleGroup = muscle, baselineWeight = baseline)
            }
        val history = if (available.isNotEmpty())
            db.workoutSetDao().getRecentSetsForExercises(available.map { it.id }, limit = 200)
                .groupBy { it.exerciseId }
        else emptyMap()
        val recentSessions = db.workoutSessionDao().getRecentCompletedSessions(limit = 50)
        val recentSets = if (recentSessions.isNotEmpty())
            db.workoutSetDao().getSetsForSessions(recentSessions.map { it.id })
                .groupBy { it.sessionId }
        else emptyMap()
        val durationEstimator = ExerciseDurationEstimator.build(recentSessions, recentSets)
        val effectiveCoefficients = effectiveCoefficientSource()
        return WorkoutPlanner(
            availableExercises = available,
            strengths = strengths,
            recentHistory = history,
            weightUnit = weightUnit,
            locationId = locationId,
            coefficientSource = effectiveCoefficients,
            progressionEngine = progressionEngine,
            durationEstimator = durationEstimator,
        )
    }
```

- [ ] **Step 5.7: Run the full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: All tests pass (no regressions).

- [ ] **Step 5.8: Build the debug APK to make sure Room is happy**

Run: `./gradlew :app:assembleDebug`

Expected: Build succeeds.

- [ ] **Step 5.9: Commit**

```bash
git add \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt \
  app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt \
  app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt
git commit -m "feat(domain): wire ExerciseDurationEstimator through planner

WorkoutRepository.buildPlanner loads the last 50 completed sessions and
their sets, builds the estimator, and passes it to WorkoutPlanner.
withWeight() stamps each planned exercise's learned value onto
estimatedSecondsOverride, so plan-preview's total reflects history."
```

---

## Task 6: Instrumented test verification + manual smoke

**Files:** none modified.

- [ ] **Step 6.1: Run the instrumented test suite (if an emulator/device is connected)**

Run: `./gradlew :app:connectedAndroidTest`

Expected: All tests pass. (Per memory, an emulator is typically running — try it directly. Skip this step only if it fails to find a device.)

- [ ] **Step 6.2: Run lint**

Run: `./gradlew :app:lint`

Expected: No new warnings or errors introduced by these changes.

- [ ] **Step 6.3: Manual smoke (launch the app via the /run skill or by direct install)**

After installing the debug APK on a device with existing workout history:

1. Open the app and start a workout from home.
2. Observe the plan-preview header text: `"$durationMin min · …"`.
3. For an exercise you've done many times, the per-exercise contribution should reflect the learned wall-clock (not the constant). For a brand-new exercise (no history), it should still show the formula-based contribution.
4. Adjust exercise count with the slider — the total should change proportionally as exercises are added/removed.
5. Adjust an exercise's weight via the +/− controls — the total should change only by the warmup-set delta (since the override is preserved across `copy`), not by the full per-exercise contribution.

There's no UI label that breaks down per-exercise time, so verification of an individual exercise's learned value (if you want one) is via a unit test or temporary log statement — not part of this plan.

- [ ] **Step 6.4: No commit needed for verification.** If lint or the instrumented tests surfaced anything fixable, fix it inline and add a separate commit.

---

## Notes for the executor

- Each task is independently committable. If anything goes wrong mid-task, fix it before moving to the next.
- The estimator is built fresh per call to `buildPlanner`, so there's no caching to invalidate or persist.
- The `EMPTY` constant exists so callers (including the planner test helper) can construct an estimator that returns `null` for every exercise — that's the "no learned values yet" baseline.
- `PlannedExercise`'s data-class `copy` preserves `estimatedSecondsOverride`, so the existing per-set / weight-adjustment paths in `WorkoutSessionController` and `WorkoutPlanner.recomputeExercise` keep working without changes.
