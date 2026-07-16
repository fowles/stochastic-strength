# Phase 0: Backtest Harness + Main-Stack Baseline — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the forward-chaining held-out backtest harness over the real history and record the baseline score of main's current estimator — the single tuning authority for the estimator rebuild.

**Architecture:** Pure JVM test-tree code (`app/src/test/.../domain/backtest/`) plus one test-only Gradle dependency. The harness parses the local backup JSON, replays main's production stack (`SessionProgressionStepper` + `MuscleStrengthProjector`) session by session via a DB-free mirror of `ReplayEngine.run`, captures each session's predictions *before* folding it, and scores them against model-free ln(1RM) intervals implied by each set's feedback. A diagnostic counts demonstrated-capacity-cap violations. A runnable report test prints and records the baseline.

**Tech Stack:** Kotlin, JUnit4, org.json (test-only dep), existing domain classes on main (`BackupJsonParser`, `ReplaySnapshot`, `SessionProgressionStepper`, `MuscleStrengthProjector`, `ExerciseEstimate`, `DefaultProgressionEngine`, `ExerciseCoefficients`).

**Spec:** `docs/superpowers/specs/2026-07-14-estimator-rebuild-design.md`

## Global Constraints

- **Zero prod-source changes.** Everything lives under `app/src/test/`; the only build change is the test-only `org.json` dependency.
- **`history.json` is local-only** (`app/src/test/resources/backtest/history.json`, gitignored). Every test that reads it must skip cleanly (`org.junit.Assume`) when absent. Baseline numbers are recorded in this plan file, never asserted in CI.
- **The metric is model-free on the target side**: intervals come only from `DefaultProgressionEngine.rawToOneRepMax` + the feedback bucket. No fatigue correction, no estimator concepts.
- **Main's estimator is scored unmodified.** The replay mirror must reproduce `ReplayEngine.run` semantics exactly (initial overrides → per-session overrides → step; sessions sorted by `endTime` then `id`).
- **Version control is jj.** Commit each task checkpoint: `jj describe -m "<message>" && jj new`. Do not push.
- Run the most specific test target after each change: `./gradlew :app:testDebugUnitTest --tests "<class>"`. Full suite at the end.

---

### Task 1: org.json test dependency + BacktestData loader

**Files:**
- Modify: `gradle/libs.versions.toml` (add `json` library)
- Modify: `app/build.gradle.kts` (add `testImplementation(libs.json)`)
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestData.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestFixtures.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestDataTest.kt`

**Interfaces:**
- Consumes: `BackupJsonParser.parse(json: String): WorkoutBackup`, `BackupJsonBuilder.build(backup): String` (both `domain/backup/BackupJson.kt`), `ReplaySnapshot(exerciseMuscle, seedCoefficients)`, `ExerciseCoefficients.get(exercise): Float?`.
- Produces (later tasks rely on these exact signatures):
  - `class BacktestData(val backup: WorkoutBackup, val weightUnit: WeightUnit)` with `val sessions: List<WorkoutSession>` (endTime != null, sorted by endTime then id), `val setsBySession: Map<Long, List<WorkoutSet>>` (sorted by id), `val initialOverrides: List<ExerciseStrengthOverride>`, `val sessionOverrides: Map<Long, List<ExerciseStrengthOverride>>`, `fun newSnapshot(): ReplaySnapshot`
  - `BacktestData.loadOrNull(): BacktestData?`, `BacktestData.from(backup: WorkoutBackup): BacktestData`, `BacktestData.historyFile(): File`
  - `BacktestFixtures.backup(exercises, sessions, sets, strengthOverrides = emptyList()): WorkoutBackup`

- [x] **Step 1: Add the test-only org.json dependency**

In `gradle/libs.versions.toml`, under `[libraries]` (next to the `junit` line):

```toml
json = { group = "org.json", name = "json", version = "20240303" }
```

In `app/build.gradle.kts`, right after `testImplementation(libs.junit)`:

```kotlin
testImplementation(libs.json)
```

(Android ships `org.json` at runtime but stubs it for JVM unit tests; this dependency makes `BackupJsonParser` work under `testDebugUnitTest`.)

- [x] **Step 2: Write the failing test**

`app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestDataTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonBuilder
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktestDataTest {

    private val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
    private val goblet = Exercise(id = 2, name = "Goblet Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.DUMBBELL, isDisliked = true)

    @Test
    fun sessionsAreSortedAndUnfinishedDropped() {
        val backup = BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(
                WorkoutSession(id = 3, startTime = 0, endTime = 2000),
                WorkoutSession(id = 1, startTime = 0, endTime = 1000),
                WorkoutSession(id = 2, startTime = 0, endTime = null), // in-flight: dropped
            ),
            sets = listOf(
                WorkoutSet(id = 2, sessionId = 1, exerciseId = 1, setNumber = 2, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
                WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4),
            ),
        )
        val data = BacktestData.from(backup)
        assertEquals(listOf(1L, 3L), data.sessions.map { it.id })
        assertEquals(listOf(1L, 2L), data.setsBySession[1L]!!.map { it.id })
    }

    @Test
    fun overridesAreSplitByInitialVsSession() {
        val backup = BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = emptyList(),
            sets = emptyList(),
            strengthOverrides = listOf(
                ExerciseStrengthOverride(id = 1, sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0),
                ExerciseStrengthOverride(id = 2, sessionId = 7, exerciseId = 1, e1rm = 120f, asOf = 5),
            ),
        )
        val data = BacktestData.from(backup)
        assertEquals(1, data.initialOverrides.size)
        assertEquals(110f, data.initialOverrides[0].e1rm, 0f)
        assertEquals(120f, data.sessionOverrides[7L]!![0].e1rm, 0f)
    }

    @Test
    fun snapshotMirrorsProductionSeeding() {
        // Production seeds coefficients from active (non-disliked) exercises only; muscle map covers all.
        val data = BacktestData.from(BacktestFixtures.backup(listOf(squat, goblet), emptyList(), emptyList()))
        val snapshot = data.newSnapshot()
        assertEquals(1.00f, snapshot.seedCoefficients[1L]!!, 0f)
        assertNull(snapshot.seedCoefficients[2L]) // disliked: excluded like DAO getActive()
        assertEquals(MuscleGroup.QUADS, snapshot.exerciseMuscle[2L])
    }

    @Test
    fun jsonRoundTripSurvivesTheProdBuilder() {
        val backup = BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1000)),
            sets = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_0_1)),
        )
        val data = BacktestData.from(BackupJsonParser.parse(BackupJsonBuilder.build(backup)))
        assertEquals(1, data.sessions.size)
        assertTrue(data.setsBySession[1L]!!.single().feedback == SetFeedback.RIR_0_1)
    }
}
```

And `BacktestFixtures.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.backup.WorkoutBackup

/** Builders for synthetic histories. Exercise names must exist in ExerciseCoefficients.byName. */
object BacktestFixtures {
    const val DAY_MS = 24L * 60 * 60 * 1000

    fun backup(
        exercises: List<Exercise>,
        sessions: List<WorkoutSession>,
        sets: List<WorkoutSet>,
        strengthOverrides: List<ExerciseStrengthOverride> = emptyList(),
    ): WorkoutBackup = WorkoutBackup(
        formatVersion = WorkoutBackup.FORMAT_VERSION,
        dbVersion = WorkoutBackup.DB_VERSION,
        exportedAt = 0L,
        exercises = exercises,
        knownLocations = emptyList(),
        locationExcludedExercises = emptyList(),
        workoutSessions = sessions,
        workoutSets = sets,
        userProfile = emptyList(),
        baselineOverrides = emptyList(),
        exerciseHurtState = emptyList(),
        exerciseStrengthOverrides = strengthOverrides,
    )
}
```

- [x] **Step 3: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BacktestDataTest"`
Expected: FAIL — `BacktestData` unresolved.

- [x] **Step 4: Implement BacktestData**

`app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestData.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonParser
import io.github.fowles.stochastic_strength.domain.backup.WorkoutBackup
import java.io.File

/**
 * The parsed real (or synthetic) history for backtesting. history.json is a full app backup
 * (personal data, gitignored) exported via HistoryScreen; tests skip when it is absent.
 */
class BacktestData(val backup: WorkoutBackup, val weightUnit: WeightUnit) {

    val sessions = backup.workoutSessions
        .filter { it.endTime != null }
        .sortedWith(compareBy({ it.endTime!! }, { it.id }))

    val setsBySession = backup.workoutSets.groupBy { it.sessionId }
        .mapValues { (_, s) -> s.sortedBy { it.id } }

    val initialOverrides = backup.exerciseStrengthOverrides.filter { it.sessionId == null }

    val sessionOverrides = backup.exerciseStrengthOverrides.filter { it.sessionId != null }
        .groupBy { it.sessionId!! }

    /** Mirrors ReplaySnapshot.loadStaticFromDb: muscle map from all exercises; seed coefficients
     *  from active (non-disliked) exercises only, exactly like the DAO's getActive(). */
    fun newSnapshot(): ReplaySnapshot = ReplaySnapshot(
        exerciseMuscle = backup.exercises.associate { it.id to it.primaryMuscle },
        seedCoefficients = backup.exercises.filterNot { it.isDisliked }
            .associate { it.id to (ExerciseCoefficients.get(it) ?: 0f) },
    )

    companion object {
        private val dir = File("src/test/resources/backtest")
        fun historyFile(): File = File(dir, "history.json")
        fun baselineFile(): File = File(dir, "phase0_baseline.json")

        fun loadOrNull(): BacktestData? {
            val f = historyFile()
            if (!f.exists()) return null
            return from(BackupJsonParser.parse(f.readText()))
        }

        fun from(backup: WorkoutBackup): BacktestData =
            BacktestData(backup, backup.userProfile.firstOrNull()?.weightUnit ?: WeightUnit.KG)
    }
}
```

- [x] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BacktestDataTest"`
Expected: PASS (4 tests)

- [x] **Step 6: Commit**

```bash
jj describe -m "test(backtest): BacktestData loader + fixtures over the backup format" && jj new
```

---

### Task 2: Model-free set intervals (the metric's target side)

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/SetIntervals.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/SetIntervalsTest.kt`

**Interfaces:**
- Consumes: `DefaultProgressionEngine.rawToOneRepMax(weight: Float, reps: Float): Float` (internal; test source set is a friend of main, so it resolves).
- Produces:
  - `data class LnInterval(val lowerLn: Float?, val upperLn: Float?)` with `fun distanceTo(pointLn: Float): Float` (0 inside, else distance to the violated bound, in log units)
  - `SetIntervals.impliedLn1RmInterval(set: WorkoutSet): LnInterval?` (null = set carries no load observation)

Bounds table (from the spec — copy exactly):

| feedback | interval at weight w, target reps r |
|---|---|
| TOO_HARD, actualReps = a | [ln 1RM(w, a), ln 1RM(w, a+1)] |
| TOO_HARD, no rep count | (−∞, ln 1RM(w, r)] |
| RIR_0_1 | [ln 1RM(w, r), ln 1RM(w, r+2)] |
| RIR_2_4 | [ln 1RM(w, r+2), ln 1RM(w, r+5)] |
| RIR_5_PLUS | [ln 1RM(w, r+5), ∞) |
| HURT / null feedback / w ≤ 0 | null (not scored) |

- [x] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.ln

class SetIntervalsTest {

    private fun set(feedback: SetFeedback?, w: Float = 100f, r: Int = 5, a: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = w, targetReps = r, actualReps = a, feedback = feedback)

    private fun cap(w: Float, reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps))

    @Test
    fun rir01IsTwoSided() {
        val i = SetIntervals.impliedLn1RmInterval(set(SetFeedback.RIR_0_1))!!
        assertEquals(cap(100f, 5f), i.lowerLn!!, 1e-6f)
        assertEquals(cap(100f, 7f), i.upperLn!!, 1e-6f)
    }

    @Test
    fun rir24IsTwoSided() {
        val i = SetIntervals.impliedLn1RmInterval(set(SetFeedback.RIR_2_4))!!
        assertEquals(cap(100f, 7f), i.lowerLn!!, 1e-6f)
        assertEquals(cap(100f, 10f), i.upperLn!!, 1e-6f)
    }

    @Test
    fun rir5PlusIsLowerBoundOnly() {
        val i = SetIntervals.impliedLn1RmInterval(set(SetFeedback.RIR_5_PLUS))!!
        assertEquals(cap(100f, 10f), i.lowerLn!!, 1e-6f)
        assertNull(i.upperLn)
    }

    @Test
    fun countedFailureIsNarrowAroundDemonstratedReps() {
        val i = SetIntervals.impliedLn1RmInterval(set(SetFeedback.TOO_HARD, a = 3))!!
        assertEquals(cap(100f, 3f), i.lowerLn!!, 1e-6f)
        assertEquals(cap(100f, 4f), i.upperLn!!, 1e-6f)
    }

    @Test
    fun uncountedFailureIsUpperBoundOnly() {
        val i = SetIntervals.impliedLn1RmInterval(set(SetFeedback.TOO_HARD))!!
        assertNull(i.lowerLn)
        assertEquals(cap(100f, 5f), i.upperLn!!, 1e-6f)
    }

    @Test
    fun hurtNullFeedbackAndZeroWeightAreNotScored() {
        assertNull(SetIntervals.impliedLn1RmInterval(set(SetFeedback.HURT)))
        assertNull(SetIntervals.impliedLn1RmInterval(set(null)))
        assertNull(SetIntervals.impliedLn1RmInterval(set(SetFeedback.RIR_0_1, w = 0f)))
    }

    @Test
    fun distanceIsZeroInsideAndLinearOutside() {
        val i = LnInterval(lowerLn = 1f, upperLn = 2f)
        assertEquals(0f, i.distanceTo(1.5f), 0f)
        assertEquals(0f, i.distanceTo(1f), 0f)
        assertEquals(0.5f, i.distanceTo(0.5f), 1e-6f)
        assertEquals(1f, i.distanceTo(3f), 1e-6f)
        assertEquals(0f, LnInterval(null, 2f).distanceTo(-100f), 0f)
        assertEquals(0f, LnInterval(1f, null).distanceTo(100f), 0f)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.SetIntervalsTest"`
Expected: FAIL — `SetIntervals` unresolved.

- [x] **Step 3: Implement**

`SetIntervals.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import kotlin.math.ln

/** A model-free interval on ln(1RM). Null bound = unbounded on that side. */
data class LnInterval(val lowerLn: Float?, val upperLn: Float?) {
    /** 0 if [pointLn] is inside; otherwise the log-distance to the violated bound. */
    fun distanceTo(pointLn: Float): Float = when {
        lowerLn != null && pointLn < lowerLn -> lowerLn - pointLn
        upperLn != null && pointLn > upperLn -> pointLn - upperLn
        else -> 0f
    }
}

/**
 * The metric's target side (spec Phase 0): what a set says about ln(1RM) using ONLY the rep-max
 * formula and the feedback bucket. No fatigue correction, no estimator concepts — both stacks are
 * scored against the same intervals and neither can game it via its own modeling assumptions.
 */
object SetIntervals {
    fun impliedLn1RmInterval(set: WorkoutSet): LnInterval? {
        val feedback = set.feedback ?: return null
        if (feedback == SetFeedback.HURT) return null
        val w = set.targetWeight
        if (w <= 0f) return null
        val r = set.targetReps
        fun capLn(reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps))
        return when (feedback) {
            SetFeedback.TOO_HARD -> {
                val a = set.actualReps
                if (a != null) LnInterval(capLn(a.toFloat()), capLn(a + 1f))
                else LnInterval(null, capLn(r.toFloat()))
            }
            SetFeedback.RIR_0_1 -> LnInterval(capLn(r.toFloat()), capLn(r + 2f))
            SetFeedback.RIR_2_4 -> LnInterval(capLn(r + 2f), capLn(r + 5f))
            SetFeedback.RIR_5_PLUS -> LnInterval(capLn(r + 5f), null)
            SetFeedback.HURT -> null
        }
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.SetIntervalsTest"`
Expected: PASS (7 tests)

- [x] **Step 5: Commit**

```bash
jj describe -m "test(backtest): model-free ln(1RM) set intervals + distance metric" && jj new
```

---

### Task 3: DB-free replay of main's production stack with pre-fold predictions

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/MainStackReplay.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/MainStackReplayTest.kt`

**Interfaces:**
- Consumes: `BacktestData` (Task 1); `SessionProgressionStepper().step(sets, snapshot, asOf)`; `MuscleStrengthProjector().project(estimates, seedCoef, muscleExerciseIds, now): MuscleProjection` (`.effectiveE1rm: Map<Long, Float>`); `ExerciseEstimate.seed(e1rm, at)`; `ReplaySnapshot.currentEstimates`, `.muscleExerciseIds`.
- Produces:
  - `MainStackReplay.run(data: BacktestData, observer: SessionObserver)` where `fun interface SessionObserver { fun onSession(sessionId: Long, asOf: Long, sets: List<WorkoutSet>, predictions: Map<Long, Float>, snapshot: ReplaySnapshot) }`
  - `predictions` = every loaded exercise's projected effective 1RM (kg) computed **after applying that session's override rows but before folding its sets** — the held-out prediction for that session, at `now = session.endTime`.

- [x] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.backtest.BacktestFixtures.DAY_MS
import io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimate
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import io.github.fowles.stochastic_strength.domain.progression.SessionProgressionStepper
import org.junit.Assert.assertEquals
import org.junit.Test

class MainStackReplayTest {

    private val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
    private val s1 = WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)
    private val s2 = WorkoutSession(id = 2, startTime = 0, endTime = 3 * DAY_MS)
    private val sets1 = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_0_1))
    private val sets2 = listOf(WorkoutSet(id = 2, sessionId = 2, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4))

    @Test
    fun predictionsArePreFoldPostOverride() {
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(s1, s2),
            sets = sets1 + sets2,
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))

        val predictions = mutableListOf<Map<Long, Float>>()
        MainStackReplay.run(data) { _, _, _, preds, _ -> predictions += preds }
        assertEquals(2, predictions.size)

        // Hand-replay with the same prod components (mirrors ReplayEngine.run).
        val snapshot = data.newSnapshot()
        snapshot.currentEstimates[1L] = ExerciseEstimate.seed(110f, at = 0)
        val expected1 = MuscleStrengthProjector()
            .project(snapshot.currentEstimates, snapshot.seedCoefficients, listOf(1L), 1 * DAY_MS)
            .effectiveE1rm[1L]!!
        assertEquals(expected1, predictions[0][1L]!!, 1e-4f)

        SessionProgressionStepper().step(sets1, snapshot, 1 * DAY_MS)
        val expected2 = MuscleStrengthProjector()
            .project(snapshot.currentEstimates, snapshot.seedCoefficients, listOf(1L), 3 * DAY_MS)
            .effectiveE1rm[1L]!!
        assertEquals(expected2, predictions[1][1L]!!, 1e-4f)
    }

    @Test
    fun sessionOverrideIsAppliedBeforeThatSessionsPrediction() {
        // Same as prod ReplayEngine.run: override rows for session k land before session k's step —
        // and therefore before its prediction (user-entered corrections are known pre-workout).
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(s1, s2),
            sets = sets1 + sets2,
            strengthOverrides = listOf(
                ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0),
                ExerciseStrengthOverride(sessionId = 2, exerciseId = 1, e1rm = 200f, asOf = 2 * DAY_MS),
            ),
        ))
        var secondPrediction = 0f
        MainStackReplay.run(data) { sessionId, _, _, preds, _ ->
            if (sessionId == 2L) secondPrediction = preds[1L]!!
        }
        // Override confidence is 1.0 (prod value); the lone exercise dominates its muscle, so the
        // projected effective 1RM sits at the override.
        assertEquals(200f, secondPrediction, 1f)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.MainStackReplayTest"`
Expected: FAIL — `MainStackReplay` unresolved.

- [x] **Step 3: Implement**

`MainStackReplay.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimate
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import io.github.fowles.stochastic_strength.domain.progression.SessionProgressionStepper
import kotlin.math.ln

/**
 * DB-free mirror of the production replay (ReplayEngine.run) over parsed backup data, scoring
 * main's stack UNMODIFIED (spec Phase 0 constraint). KEEP IN SYNC with ReplayEngine.run:
 * initial overrides seed estimates; session-k override rows apply before session k; sessions
 * iterate sorted by (endTime, id); empty-set sessions are skipped.
 *
 * The one addition: before folding each session, every loaded exercise's projected effective 1RM
 * is captured at now = session.endTime — the forward-chained held-out prediction for that session.
 */
object MainStackReplay {

    fun interface SessionObserver {
        fun onSession(sessionId: Long, asOf: Long, sets: List<WorkoutSet>, predictions: Map<Long, Float>, snapshot: ReplaySnapshot)
    }

    fun run(
        data: BacktestData,
        stepper: SessionProgressionStepper = SessionProgressionStepper(),
        projector: MuscleStrengthProjector = MuscleStrengthProjector(),
        observer: SessionObserver,
    ) {
        val snapshot = data.newSnapshot()
        for (init in data.initialOverrides) {
            snapshot.currentEstimates[init.exerciseId] = ExerciseEstimate.seed(init.e1rm, at = init.asOf)
        }
        for (session in data.sessions) {
            data.sessionOverrides[session.id]?.forEach { o ->
                // Same shape as ReplayEngine.run's override row (confidence 1.0).
                snapshot.currentEstimates[o.exerciseId] = ExerciseEstimate(lnE = ln(o.e1rm), confidence = 1.0f, updatedAt = o.asOf)
            }
            val sets = data.setsBySession[session.id].orEmpty()
            if (sets.isEmpty()) continue
            val asOf = session.endTime!!
            val predictions = mutableMapOf<Long, Float>()
            for ((_, ids) in snapshot.muscleExerciseIds) {
                val proj = projector.project(snapshot.currentEstimates, snapshot.seedCoefficients, ids, asOf)
                predictions.putAll(proj.effectiveE1rm)
            }
            stepper.step(sets, snapshot, asOf)
            observer.onSession(session.id, asOf, sets, predictions, snapshot)
        }
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.MainStackReplayTest"`
Expected: PASS (2 tests)

- [x] **Step 5: Commit**

```bash
jj describe -m "test(backtest): DB-free replay of main's stack with pre-fold held-out predictions" && jj new
```

---

### Task 4: Forward-chaining held-out scorer

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/HeldOutScorer.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/HeldOutScorerTest.kt`

**Interfaces:**
- Consumes: `MainStackReplay.run` (Task 3), `SetIntervals.impliedLn1RmInterval` / `LnInterval.distanceTo` (Task 2).
- Produces:
  - `data class SessionScore(val sessionId: Long, val distance: Double, val scoredSets: Int)`
  - `data class ScoreReport(val totalDistance: Double, val scoredSets: Int, val skippedSets: Int, val perSession: List<SessionScore>)` — `skippedSets` counts sets with an interval but no prediction (cold exercise); interval-less sets (HURT/no feedback/bodyweight) are not counted at all.
  - `HeldOutScorer.score(data: BacktestData): ScoreReport`

- [x] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.backtest.BacktestFixtures.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class HeldOutScorerTest {

    private val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)

    @Test
    fun coldSingleExerciseScoreIsHandComputable() {
        // One exercise seeded at 110 kg with zero confidence: the projector returns the seed
        // itself (lone voter, level == its own seed-relative level), so the session-1 prediction
        // is exactly 110. One RIR_0_1 set at 100x5 -> interval [ln 1RM(100,5), ln 1RM(100,7)].
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)),
            sets = listOf(WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_0_1)),
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))
        val report = HeldOutScorer.score(data)
        val interval = LnInterval(
            lowerLn = ln(DefaultProgressionEngine.rawToOneRepMax(100f, 5f)),
            upperLn = ln(DefaultProgressionEngine.rawToOneRepMax(100f, 7f)),
        )
        assertEquals(interval.distanceTo(ln(110f)).toDouble(), report.totalDistance, 1e-6)
        assertEquals(1, report.scoredSets)
        assertEquals(0, report.skippedSets)
        assertEquals(1, report.perSession.size)
    }

    @Test
    fun hurtSetsAreNotScoredAndColdExercisesAreSkipped() {
        val lunge = Exercise(id = 2, name = "Dumbbell Lunge", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.DUMBBELL)
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat, lunge),
            sessions = listOf(WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS)),
            sets = listOf(
                WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.HURT),
                // Exercise 2 has no initial override -> no estimate -> no prediction -> skipped.
                WorkoutSet(id = 2, sessionId = 1, exerciseId = 2, setNumber = 1, targetWeight = 20f, targetReps = 10, feedback = SetFeedback.RIR_2_4),
            ),
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))
        val report = HeldOutScorer.score(data)
        assertEquals(0, report.scoredSets)   // HURT: no interval at all
        assertEquals(1, report.skippedSets)  // lunge: interval but no prediction
        assertEquals(0.0, report.totalDistance, 0.0)
    }

    @Test
    fun multiSessionTotalsAreSummed() {
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(
                WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS),
                WorkoutSession(id = 2, startTime = 0, endTime = 3 * DAY_MS),
            ),
            sets = listOf(
                WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_0_1),
                WorkoutSet(id = 2, sessionId = 2, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4),
            ),
            strengthOverrides = listOf(ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0)),
        ))
        val report = HeldOutScorer.score(data)
        assertEquals(2, report.scoredSets)
        assertEquals(report.perSession.sumOf { it.distance }, report.totalDistance, 1e-9)
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.HeldOutScorerTest"`
Expected: FAIL — `HeldOutScorer` unresolved.

- [x] **Step 3: Implement**

`HeldOutScorer.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import kotlin.math.ln

data class SessionScore(val sessionId: Long, val distance: Double, val scoredSets: Int)

data class ScoreReport(
    val totalDistance: Double,
    val scoredSets: Int,
    /** Sets that implied an interval but had no prediction (cold exercise, no estimate yet). */
    val skippedSets: Int,
    val perSession: List<SessionScore>,
)

/**
 * The single tuning authority (spec Phase 0): forward-chaining held-out score of a stack's
 * predictions against the model-free set intervals. Lower is better; 0 = every prediction landed
 * inside what the user demonstrated.
 */
object HeldOutScorer {
    fun score(data: BacktestData): ScoreReport {
        var total = 0.0
        var scored = 0
        var skipped = 0
        val perSession = mutableListOf<SessionScore>()
        MainStackReplay.run(data) { sessionId, _, sets, predictions, _ ->
            var d = 0.0
            var n = 0
            for (set in sets) {
                val interval = SetIntervals.impliedLn1RmInterval(set) ?: continue
                val pred = predictions[set.exerciseId]
                if (pred == null || pred <= 0f) { skipped++; continue }
                d += interval.distanceTo(ln(pred)).toDouble()
                n++
            }
            total += d
            scored += n
            perSession += SessionScore(sessionId, d, n)
        }
        return ScoreReport(total, scored, skipped, perSession)
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.HeldOutScorerTest"`
Expected: PASS (3 tests)

- [x] **Step 5: Commit**

```bash
jj describe -m "test(backtest): forward-chaining held-out scorer over set intervals" && jj new
```

---

### Task 5: Demonstrated-capacity-cap violation diagnostic

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/CapViolationDiagnostic.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/CapViolationDiagnosticTest.kt`

**Interfaces:**
- Consumes: `MainStackReplay.run` (Task 3), `SetIntervals.impliedLn1RmInterval` (Task 2).
- Produces:
  - `data class CapViolation(val sessionId: Long, val exerciseId: Long, val predictedE1rm: Float, val capE1rm: Float)`
  - `CapViolationDiagnostic.capLnFor(sets: List<WorkoutSet>): Float?` — the spec's Phase-1 cap rule applied to one session's sets for one exercise (null = uncapped)
  - `CapViolationDiagnostic.violations(data: BacktestData): List<CapViolation>`

Cap rule (from the spec — copy exactly): within a 28-day window, an exercise is capped by its **most recent session**: any TOO_HARD set → cap = min over failed sets' interval upper bounds; otherwise cap = max over the session's upper bounds, and if any loaded set is unbounded above (RIR_5_PLUS) there is no cap. This is a *diagnostic only* in Phase 0 (main has no policy layer); its violation count is baseline color and the seed of the Phase-1 invariant tests.

- [x] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.backtest.BacktestFixtures.DAY_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class CapViolationDiagnosticTest {

    private fun set(feedback: SetFeedback, w: Float, r: Int = 5, a: Int? = null, id: Long = 1) =
        WorkoutSet(id = id, sessionId = 1, exerciseId = 1, setNumber = id.toInt(), targetWeight = w, targetReps = r, actualReps = a, feedback = feedback)

    private fun cap(w: Float, reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps))

    @Test
    fun failureSessionCapIsMinOverFailedSets() {
        val ln = CapViolationDiagnostic.capLnFor(listOf(
            set(SetFeedback.RIR_2_4, 90f, id = 1),
            set(SetFeedback.TOO_HARD, 100f, a = 3, id = 2),  // upper = 1RM(100, 4)
            set(SetFeedback.TOO_HARD, 100f, id = 3),          // upper = 1RM(100, 5)
        ))!!
        assertEquals(cap(100f, 4f), ln, 1e-6f)
    }

    @Test
    fun cleanSessionCapIsBestDemonstratedUpperBound() {
        val ln = CapViolationDiagnostic.capLnFor(listOf(
            set(SetFeedback.RIR_0_1, 100f, id = 1),  // upper = 1RM(100, 7)
            set(SetFeedback.RIR_2_4, 95f, id = 2),   // upper = 1RM(95, 10)
        ))!!
        assertEquals(maxOf(cap(100f, 7f), cap(95f, 10f)), ln, 1e-6f)
    }

    @Test
    fun anyRir5PlusMeansNoCap() {
        assertNull(CapViolationDiagnostic.capLnFor(listOf(
            set(SetFeedback.RIR_2_4, 100f, id = 1),
            set(SetFeedback.RIR_5_PLUS, 100f, id = 2),
        )))
    }

    @Test
    fun hurtOnlySessionLeavesNoCap() {
        assertNull(CapViolationDiagnostic.capLnFor(listOf(set(SetFeedback.HURT, 100f))))
    }

    @Test
    fun overridePastAFailureCapIsFlagged() {
        val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(
                WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS),
                WorkoutSession(id = 2, startTime = 0, endTime = 3 * DAY_MS),
            ),
            sets = listOf(
                WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 2, feedback = SetFeedback.TOO_HARD),
                WorkoutSet(id = 2, sessionId = 2, exerciseId = 1, setNumber = 1, targetWeight = 80f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4),
            ),
            strengthOverrides = listOf(
                ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0),
                // User override right before session 2 jumps the estimate far above the failed cap.
                ExerciseStrengthOverride(sessionId = 2, exerciseId = 1, e1rm = 300f, asOf = 2 * DAY_MS),
            ),
        ))
        val violations = CapViolationDiagnostic.violations(data)
        assertEquals(1, violations.size)
        assertEquals(2L, violations[0].sessionId)
        assertTrue(violations[0].predictedE1rm > violations[0].capE1rm)
    }

    @Test
    fun capExpiresAfterTwentyEightDays() {
        val squat = Exercise(id = 1, name = "Barbell Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL)
        val data = BacktestData.from(BacktestFixtures.backup(
            exercises = listOf(squat),
            sessions = listOf(
                WorkoutSession(id = 1, startTime = 0, endTime = 1 * DAY_MS),
                WorkoutSession(id = 2, startTime = 0, endTime = 40 * DAY_MS),
            ),
            sets = listOf(
                WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, actualReps = 2, feedback = SetFeedback.TOO_HARD),
                WorkoutSet(id = 2, sessionId = 2, exerciseId = 1, setNumber = 1, targetWeight = 80f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4),
            ),
            strengthOverrides = listOf(
                ExerciseStrengthOverride(sessionId = null, exerciseId = 1, e1rm = 110f, asOf = 0),
                ExerciseStrengthOverride(sessionId = 2, exerciseId = 1, e1rm = 300f, asOf = 39 * DAY_MS),
            ),
        ))
        assertTrue(CapViolationDiagnostic.violations(data).isEmpty())
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.CapViolationDiagnosticTest"`
Expected: FAIL — `CapViolationDiagnostic` unresolved.

- [x] **Step 3: Implement**

`CapViolationDiagnostic.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.exp
import kotlin.math.ln

data class CapViolation(
    val sessionId: Long,
    val exerciseId: Long,
    val predictedE1rm: Float,
    val capE1rm: Float,
)

/**
 * Phase-0 diagnostic for the spec's demonstrated-capacity cap (the Phase-1 policy rule): counts
 * sessions where the stack's held-out prediction exceeded the upper bound demonstrated in that
 * exercise's most recent session (within a 28-day window). Main has no policy layer, so this is
 * baseline color — the "how often would the seatbelt have had to bind" number.
 */
object CapViolationDiagnostic {

    const val CAP_EXPIRY_MS = 28L * 24 * 60 * 60 * 1000

    /** The cap implied by one session's sets for one exercise, in ln(1RM). Null = uncapped. */
    fun capLnFor(sets: List<WorkoutSet>): Float? {
        val intervals = sets.mapNotNull { s ->
            SetIntervals.impliedLn1RmInterval(s)?.let { s to it }
        }
        if (intervals.isEmpty()) return null
        val failed = intervals.filter { (s, _) -> s.feedback == SetFeedback.TOO_HARD }
        if (failed.isNotEmpty()) return failed.mapNotNull { (_, i) -> i.upperLn }.min()
        // Clean session: best demonstrated upper bound; any unbounded set (RIR_5_PLUS) -> no cap.
        val uppers = intervals.map { (_, i) -> i.upperLn }
        if (uppers.any { it == null }) return null
        return uppers.filterNotNull().max()
    }

    fun violations(data: BacktestData): List<CapViolation> {
        data class Cap(val ln: Float, val at: Long)
        val lastCap = mutableMapOf<Long, Cap?>()
        val out = mutableListOf<CapViolation>()
        MainStackReplay.run(data) { sessionId, asOf, sets, predictions, _ ->
            for ((exerciseId, pred) in predictions) {
                val cap = lastCap[exerciseId] ?: continue
                if (asOf - cap.at > CAP_EXPIRY_MS) continue
                if (ln(pred) > cap.ln) out += CapViolation(sessionId, exerciseId, pred, exp(cap.ln))
            }
            sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
                if (exSets.any { it.feedback != null }) {
                    lastCap[id] = capLnFor(exSets)?.let { Cap(it, asOf) }
                }
            }
        }
        return out
    }
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.CapViolationDiagnosticTest"`
Expected: PASS (6 tests)

- [x] **Step 5: Commit**

```bash
jj describe -m "test(backtest): demonstrated-capacity-cap violation diagnostic" && jj new
```

---

### Task 6: Baseline report over the real history

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BaselineReportTest.kt`
- Modify: this plan file (record the numbers under "Baseline results" below)

**Interfaces:**
- Consumes: `BacktestData.loadOrNull()/baselineFile()` (Task 1), `HeldOutScorer.score` (Task 4), `CapViolationDiagnostic.violations` (Task 5), org.json.

- [x] **Step 1: Confirm the baseline file will stay untracked**

Run: `git check-ignore app/src/test/resources/backtest/phase0_baseline.json && echo IGNORED`
Expected: `IGNORED`. If not, add `app/src/test/resources/backtest/` to `.gitignore` first and include that change in this task's commit.

- [x] **Step 2: Write the report test**

This test is the deliverable (a runnable report), so there is no red-green cycle — it must skip cleanly without the local history file and pass with it.

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * Phase-0 exit artifact (spec): the held-out baseline of main's UNMODIFIED estimator over the real
 * history — the number the rebuilt stack must beat. Skips when the local history export is absent.
 * Writes phase0_baseline.json (gitignored) and prints the report; the headline numbers are recorded
 * in docs/superpowers/plans/2026-07-14-phase0-backtest-harness.md.
 */
class BaselineReportTest {

    @Test
    fun recordMainStackBaseline() {
        val data = BacktestData.loadOrNull()
        Assume.assumeTrue("backtest/history.json not present; skipping baseline report", data != null)
        data!!

        val report = HeldOutScorer.score(data)
        val violations = CapViolationDiagnostic.violations(data)

        assertTrue("baseline must score real sets", report.scoredSets > 0)

        val json = JSONObject()
            .put("generatedAt", System.currentTimeMillis())
            .put("stack", "main")
            .put("totalDistance", report.totalDistance)
            .put("scoredSets", report.scoredSets)
            .put("skippedSets", report.skippedSets)
            .put("meanDistancePerSet", report.totalDistance / report.scoredSets)
            .put("capViolations", violations.size)
            .put("perSession", JSONArray().apply {
                report.perSession.forEach {
                    put(JSONObject().put("s", it.sessionId).put("d", it.distance).put("n", it.scoredSets))
                }
            })
        BacktestData.baselineFile().writeText(json.toString(2))

        val sb = StringBuilder()
        sb.appendLine("=== Phase 0 baseline: main's estimator on real history ===")
        sb.appendLine("sessions scored : ${report.perSession.size}")
        sb.appendLine("sets scored     : ${report.scoredSets} (skipped: ${report.skippedSets})")
        sb.appendLine("total distance  : ${"%.4f".format(report.totalDistance)} ln-units")
        sb.appendLine("mean per set    : ${"%.5f".format(report.totalDistance / report.scoredSets)} ln-units")
        sb.appendLine("cap violations  : ${violations.size}")
        violations.forEach {
            sb.appendLine("  session ${it.sessionId} exercise ${it.exerciseId}: predicted %.1f kg > cap %.1f kg".format(it.predictedE1rm, it.capE1rm))
        }
        println(sb)
    }
}
```

- [x] **Step 3: Run the report against the real history**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BaselineReportTest" --info 2>&1 | grep -A 40 "Phase 0 baseline"`
Expected: PASS, with the printed report (24 sessions of data; scored sets should be in the low hundreds given 360 sets minus HURT/cold/bodyweight).

- [x] **Step 4: Record the numbers**

Paste the printed report into the "Baseline results" section at the bottom of this plan file. These numbers are the Phase-2 ship-gate reference (spec: "new stack ≥ main's baseline").

- [x] **Step 5: Run the full unit suite for regressions**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all green (new backtest tests skip the report if the local file is missing; everything pre-existing untouched).

- [x] **Step 6: Commit**

```bash
jj describe -m "test(backtest): phase-0 baseline report of main's estimator on real history" && jj new
```

---

## Baseline results (filled in by Task 6)

```
=== Phase 0 baseline: main's estimator on real history ===
sessions scored : 24
sets scored     : 213 (skipped: 9)
total distance  : 26.7593 ln-units
mean per set    : 0.12563 ln-units
cap violations  : 49
  session 18 exercise 21: predicted 33.3 kg > cap 31.6 kg
  session 18 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 19 exercise 21: predicted 33.3 kg > cap 31.6 kg
  session 19 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 21 exercise 21: predicted 33.4 kg > cap 31.6 kg
  session 21 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 22 exercise 21: predicted 34.1 kg > cap 31.6 kg
  session 22 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 23 exercise 21: predicted 34.1 kg > cap 31.6 kg
  session 23 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 24 exercise 21: predicted 34.2 kg > cap 31.6 kg
  session 24 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 25 exercise 21: predicted 34.2 kg > cap 31.6 kg
  session 25 exercise 26: predicted 22.7 kg > cap 21.2 kg
  session 25 exercise 34: predicted 24.1 kg > cap 24.1 kg
  session 25 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 26 exercise 21: predicted 34.3 kg > cap 31.6 kg
  session 26 exercise 26: predicted 22.7 kg > cap 21.2 kg
  session 26 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 27 exercise 21: predicted 34.4 kg > cap 31.6 kg
  session 27 exercise 26: predicted 22.7 kg > cap 21.2 kg
  session 27 exercise 33: predicted 35.7 kg > cap 31.6 kg
  session 27 exercise 34: predicted 25.4 kg > cap 24.1 kg
  session 27 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 28 exercise 21: predicted 34.4 kg > cap 31.6 kg
  session 28 exercise 26: predicted 22.7 kg > cap 21.2 kg
  session 28 exercise 33: predicted 35.7 kg > cap 31.6 kg
  session 28 exercise 34: predicted 25.4 kg > cap 24.1 kg
  session 28 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 29 exercise 21: predicted 34.5 kg > cap 31.6 kg
  session 29 exercise 23: predicted 44.2 kg > cap 38.4 kg
  session 29 exercise 26: predicted 22.0 kg > cap 21.2 kg
  session 29 exercise 33: predicted 35.7 kg > cap 31.6 kg
  session 29 exercise 34: predicted 25.5 kg > cap 24.1 kg
  session 29 exercise 46: predicted 14.7 kg > cap 14.6 kg
  session 29 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 30 exercise 21: predicted 34.5 kg > cap 31.6 kg
  session 30 exercise 23: predicted 44.2 kg > cap 38.4 kg
  session 30 exercise 26: predicted 22.0 kg > cap 21.2 kg
  session 30 exercise 33: predicted 32.0 kg > cap 27.6 kg
  session 30 exercise 34: predicted 25.1 kg > cap 24.1 kg
  session 30 exercise 46: predicted 14.7 kg > cap 14.6 kg
  session 30 exercise 77: predicted 15.8 kg > cap 13.6 kg
  session 31 exercise 21: predicted 34.6 kg > cap 31.6 kg
  session 31 exercise 23: predicted 44.2 kg > cap 38.4 kg
  session 31 exercise 26: predicted 21.9 kg > cap 21.2 kg
  session 31 exercise 33: predicted 32.0 kg > cap 27.6 kg
  session 31 exercise 46: predicted 14.7 kg > cap 14.6 kg
  session 31 exercise 77: predicted 15.8 kg > cap 13.6 kg
```

## Out of scope for this plan (later phases per the spec)

- Phase 1: policy layer (demonstrated-capacity cap as a *prescription clamp*, HURT backoff, cooldown) + invariant tests + BSS fixture conversion.
- Phase 2: belief core (boundary-pull folds, fatigue, aging), pooling, prescription; constants fit against this harness.
- Phase 3: prod swap, "why this weight" trace, deletions.
