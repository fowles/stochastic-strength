# Per-exercise Progression Chart + Cross-tuning Bars Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the per-exercise coefficient chart with an estimated-1RM chart (per-session dots + own-estimate / siblings / merged lines) and replace the "Coefficient vs seed" lists with muscle-wide cross-tuning bars (agreement vs consensus + contribution).

**Architecture:** Extract the inline per-session fold and the full replay loop out of `WorkoutRepository` into reusable `SessionProgressionStepper` + `ReplayEngine` (with a per-session observer hook). A new pure `ExerciseProgressionSeriesBuilder` drives the same `ReplayEngine` over one muscle to recompute the chart series on demand; a `computeCrossTuning` helper derives the bars from the live estimate snapshot. The repo exposes two read methods as the ViewModel seam. No schema/DB changes; no progression-behavior changes.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Vico charts, Room, JUnit + `org.junit.Assert`.

## Global Constraints

- Package root: `io.github.fowles.stochastic_strength`.
- No DB/schema changes; no new durable state (no Room entities, no migration).
- No change to prescription/progression numerical behavior — the engine extraction must keep `ExerciseEstimatorSimulationTest` and `ReplayDerivedStateTest` green.
- Unit tests run on JVM: `./gradlew :app:testDebugUnitTest`.
- All estimate/observation values are in estimated-1RM **kilograms**; screens format to the user's unit via `WeightFormatter`.
- Use `org.junit.Test` + `org.junit.Assert.assertEquals/assertTrue` (match existing `MuscleStrengthProjectorTest` style).

---

### Task 1: Extract `SessionProgressionStepper`

Pull the pure per-session core (HURT → per-exercise fold → per-affected-muscle projection) out of `WorkoutRepository.applySessionProgression` into a reusable class that mutates `snapshot.currentEstimates` in place and returns the affected muscles' projections.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SessionProgressionStepper.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (`applySessionProgression`, lines 91-144; add a stepper field near line 38)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/SessionProgressionStepperTest.kt`

**Interfaces:**
- Consumes: `ReplaySnapshot` (`currentEstimates`, `seedCoefficients`, `exerciseMuscle`, `muscleExerciseIds`), `ExerciseEstimateUpdater`, `MuscleStrengthProjector`, `MuscleProjection`, `SessionSignalExtractor.aggregateSession`, `WorkoutSet`, `MuscleGroup`, `SetFeedback`.
- Produces:
  - `class SessionProgressionStepper(updater: ExerciseEstimateUpdater = ExerciseEstimateUpdater(), projector: MuscleStrengthProjector = MuscleStrengthProjector())`
  - `data class SessionProgressionStepper.MuscleStep(val muscle: MuscleGroup, val projection: MuscleProjection)`
  - `data class SessionProgressionStepper.StepResult(val steps: List<MuscleStep>)`
  - `fun step(sets: List<WorkoutSet>, snapshot: ReplaySnapshot, asOf: Long): StepResult`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class SessionProgressionStepperTest {

    private val stepper = SessionProgressionStepper()

    private fun snapshot(): ReplaySnapshot {
        // Two exercises in CHEST, both loaded (seed > 0).
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f, 2L to 0.6f),
        )
        snap.currentEstimates[1L] = ExerciseEstimate(lnE = ln(100f), confidence = 6f, updatedAt = 0L)
        snap.currentEstimates[2L] = ExerciseEstimate(lnE = ln(60f), confidence = 6f, updatedAt = 0L)
        return snap
    }

    private fun set(exerciseId: Long, weight: Float, reps: Int, feedback: SetFeedback) = WorkoutSet(
        sessionId = 10L,
        exerciseId = exerciseId,
        setNumber = 1,
        targetWeight = weight,
        targetReps = reps,
        actualReps = reps,
        feedback = feedback,
    )

    @Test
    fun foldMovesOnlyTheWorkedExerciseAndReturnsItsMuscle() {
        val snap = snapshot()
        val before2 = snap.currentEstimates.getValue(2L).lnE
        val result = stepper.step(
            sets = listOf(set(1L, weight = 105f, reps = 5, feedback = SetFeedback.RIR_2_4)),
            snapshot = snap,
            asOf = 1_000L,
        )
        // Exercise 1 moved; exercise 2 untouched (local fold).
        assertTrue(snap.currentEstimates.getValue(1L).updatedAt == 1_000L)
        assertEquals(before2, snap.currentEstimates.getValue(2L).lnE, 1e-6f)
        // The worked exercise's muscle is reported with a projection.
        assertEquals(1, result.steps.size)
        assertEquals(MuscleGroup.CHEST, result.steps.first().muscle)
        assertTrue(result.steps.first().projection.level > 0f)
    }

    @Test
    fun hurtBacksOffEveryLoadedExerciseInTheMuscle() {
        val snap = snapshot()
        val before1 = snap.currentEstimates.getValue(1L).lnE
        stepper.step(
            sets = listOf(set(2L, weight = 60f, reps = 5, feedback = SetFeedback.HURT)),
            snapshot = snap,
            asOf = 2_000L,
        )
        // HURT is muscle-level: exercise 1 is backed off even though only 2 was performed.
        assertEquals(before1 + ln(0.85f), snap.currentEstimates.getValue(1L).lnE, 1e-4f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SessionProgressionStepperTest"`
Expected: FAIL — `SessionProgressionStepper` is unresolved.

- [ ] **Step 3: Create the stepper**

Create `SessionProgressionStepper.kt` by lifting the body of `applySessionProgression` (minus the DB `getSetsForSession` call and the `scratch` writes):

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.SessionSignalExtractor

/**
 * Pure per-session core of progression: HURT (muscle-level) → per-exercise fold → projection of
 * each affected muscle. Mutates [ReplaySnapshot.currentEstimates] in place and returns the affected
 * muscles' projections. Persistence of the projections is the caller's concern.
 */
class SessionProgressionStepper(
    private val updater: ExerciseEstimateUpdater = ExerciseEstimateUpdater(),
    private val projector: MuscleStrengthProjector = MuscleStrengthProjector(),
) {
    data class MuscleStep(val muscle: MuscleGroup, val projection: MuscleProjection)
    data class StepResult(val steps: List<MuscleStep>)

    fun step(sets: List<WorkoutSet>, snapshot: ReplaySnapshot, asOf: Long): StepResult {
        if (sets.isEmpty()) return StepResult(emptyList())

        // HURT first (muscle-level): for any hurt muscle, hurt every loaded exercise estimate in it.
        val hurtMuscles = sets.filter { it.feedback == SetFeedback.HURT }
            .mapNotNull { snapshot.exerciseMuscle[it.exerciseId] }.toSet()
        for (m in hurtMuscles) {
            for (id in snapshot.muscleExerciseIds[m].orEmpty()) {
                snapshot.currentEstimates[id]?.let {
                    snapshot.currentEstimates[id] = updater.hurt(it, asOf)
                }
            }
        }

        // Per-exercise fold from the session aggregate.
        val affectedMuscles = mutableSetOf<MuscleGroup>()
        sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
            if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
            val agg = SessionSignalExtractor.aggregateSession(exSets) ?: return@forEach
            val prior = snapshot.currentEstimates[id] ?: return@forEach
            snapshot.currentEstimates[id] = updater.fold(prior, agg.est1RM, agg.bracketConfidence, asOf)
            snapshot.exerciseMuscle[id]?.let { affectedMuscles.add(it) }
        }
        affectedMuscles.addAll(hurtMuscles)

        val steps = affectedMuscles.mapNotNull { m ->
            val exerciseIds = snapshot.muscleExerciseIds[m] ?: return@mapNotNull null
            val projection = projector.project(
                estimates = snapshot.currentEstimates,
                seedCoef = snapshot.seedCoefficients,
                muscleExerciseIds = exerciseIds,
                now = asOf,
            )
            MuscleStep(muscle = m, projection = projection)
        }
        return StepResult(steps)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SessionProgressionStepperTest"`
Expected: PASS.

- [ ] **Step 5: Refactor `WorkoutRepository.applySessionProgression` to use the stepper**

Add a field near line 38 (next to `replayMutex`):

```kotlin
    private val stepper = SessionProgressionStepper()
```

Replace the whole body of `applySessionProgression` (lines 91-144) with:

```kotlin
    private suspend fun applySessionProgression(
        sessionId: Long,
        snapshot: ReplaySnapshot,
        asOf: Long,
        scratch: MutableDerivedState,
    ) {
        val sets = db.workoutSetDao().getSetsForSession(sessionId)
        if (sets.isEmpty()) return

        val result = stepper.step(sets, snapshot, asOf)
        for (stepResult in result.steps) {
            writeLevelUpdate(stepResult.muscle, stepResult.projection.level, sessionId, asOf, scratch)
            val exerciseIds = snapshot.muscleExerciseIds[stepResult.muscle] ?: continue
            writeDerivedCoefficients(
                muscleExerciseIds = exerciseIds,
                derivedCoef = stepResult.projection.derivedCoef,
                snapshot = snapshot,
                asOf = asOf,
                scratch = scratch,
            )
        }
    }
```

Remove the now-unused imports in `WorkoutRepository.kt` if `ExerciseEstimateUpdater` / `MuscleStrengthProjector` / `SessionSignalExtractor` / `SetFeedback` are no longer referenced elsewhere in the file (check with the build in the next step; `MuscleStrengthProjector` is still used by the cold-start fill near line 295, so keep that import).

- [ ] **Step 6: Verify no behavior change**

Run: `./gradlew :app:testDebugUnitTest --tests "*ExerciseEstimatorSimulationTest" --tests "*SessionProgressionStepperTest"`
Expected: PASS (simulation test proves the extraction is behavior-preserving).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SessionProgressionStepper.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/SessionProgressionStepperTest.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt
git commit -m "refactor: extract SessionProgressionStepper from applySessionProgression

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Extract `ReplayEngine` with a per-session observer

Pull the replay loop (initial-estimate seeding, per-session override rows, per-session step) out of `WorkoutRepository.replayDerivedState` into a reusable engine that calls an observer after each session. `replayDerivedState` becomes a thin caller whose observer writes the derived rows; the series builder (Task 3) reuses the same engine with a recording observer.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ReplayEngine.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (`replayDerivedState`, lines 247-284 — the seeding + session loop only; keep the cold-start fill and `putExerciseEstimates` afterward)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ReplayEngineTest.kt`

**Interfaces:**
- Consumes: `AppDatabase`, `ReplaySnapshot`, `SessionProgressionStepper` (+ its `StepResult`), `ExerciseEstimate`, `ExerciseStrengthOverride` DAO (`getInitials`, `getNonInitials`), `WorkoutSessionDao.getAll`, `WorkoutSetDao.getSetsForSession`, `WorkoutSet`.
- Produces:
  - `class ReplayEngine(stepper: SessionProgressionStepper = SessionProgressionStepper())`
  - `fun interface ReplayEngine.SessionObserver { fun onSession(sessionId: Long, asOf: Long, sets: List<WorkoutSet>, snapshot: ReplaySnapshot, result: SessionProgressionStepper.StepResult) }`
  - `suspend fun run(db: AppDatabase, snapshot: ReplaySnapshot, observer: SessionObserver)`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayEngineTest {

    @Test
    fun observerSurfaceHasTheAgreedShape() {
        // Compile-only guard that the observer surface exists with the agreed five-arg shape.
        // (Full DB-backed replay parity is covered by the instrumented ReplayDerivedStateTest.)
        var captured = -1
        val observer = ReplayEngine.SessionObserver { _, _, sets, _, result ->
            captured = sets.size + result.steps.size
        }
        observer.onSession(
            sessionId = 1L,
            asOf = 0L,
            sets = emptyList(),
            snapshot = ReplaySnapshot(emptyMap(), emptyMap()),
            result = SessionProgressionStepper.StepResult(emptyList()),
        )
        assertEquals(0, captured)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ReplayEngineTest"`
Expected: FAIL — `ReplayEngine` is unresolved.

- [ ] **Step 3: Create the engine**

Create `ReplayEngine.kt`, lifting the seeding + loop from `replayDerivedState` (lines 252-284):

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import kotlin.math.ln

/**
 * Replays every completed session in order through [SessionProgressionStepper], seeding initial
 * estimates and applying per-session strength-override rows exactly as the production replay does.
 * After each session it invokes [SessionObserver]; the caller decides what to do with the result
 * (write derived rows, or record chart samples). The replay is muscle-agnostic; consumers filter.
 */
class ReplayEngine(
    private val stepper: SessionProgressionStepper = SessionProgressionStepper(),
) {
    fun interface SessionObserver {
        fun onSession(
            sessionId: Long,
            asOf: Long,
            sets: List<WorkoutSet>,
            snapshot: ReplaySnapshot,
            result: SessionProgressionStepper.StepResult,
        )
    }

    suspend fun run(db: AppDatabase, snapshot: ReplaySnapshot, observer: SessionObserver) {
        // Init from per-exercise strength overrides (sessionId = null rows).
        val initials = db.exerciseStrengthOverrideDao().getInitials()
        for (init in initials) {
            snapshot.currentEstimates[init.exerciseId] = ExerciseEstimate.seed(init.e1rm, at = init.asOf)
        }

        val exerciseOverridesBySession = db.exerciseStrengthOverrideDao().getNonInitials()
            .groupBy { it.sessionId!! }

        val sessions = db.workoutSessionDao().getAll()
            .filter { it.endTime != null }
            .sortedWith(compareBy({ it.endTime!! }, { it.id }))

        for (session in sessions) {
            exerciseOverridesBySession[session.id]?.forEach { o ->
                snapshot.currentEstimates[o.exerciseId] = ExerciseEstimate(
                    lnE = ln(o.e1rm),
                    confidence = 1.0f,
                    updatedAt = o.asOf,
                )
            }

            val sets = db.workoutSetDao().getSetsForSession(session.id)
            if (sets.isEmpty()) continue
            val result = stepper.step(sets, snapshot, session.endTime!!)
            observer.onSession(session.id, session.endTime!!, sets, snapshot, result)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*ReplayEngineTest"`
Expected: PASS.

- [ ] **Step 5: Refactor `replayDerivedState` to use the engine**

In `WorkoutRepository.kt`, add a field near the `stepper` field from Task 1:

```kotlin
    private val replayEngine = ReplayEngine(stepper)
```

Replace lines 252-284 (the `// Init from per-exercise...` block through the end of the `for (session in sessions)` loop) with:

```kotlin
            replayEngine.run(db, snapshot) { sessionId, asOf, _, _, result ->
                for (stepResult in result.steps) {
                    writeLevelUpdate(stepResult.muscle, stepResult.projection.level, sessionId, asOf, scratch)
                    val exerciseIds = snapshot.muscleExerciseIds[stepResult.muscle] ?: continue
                    writeDerivedCoefficients(
                        muscleExerciseIds = exerciseIds,
                        derivedCoef = stepResult.projection.derivedCoef,
                        snapshot = snapshot,
                        asOf = asOf,
                        scratch = scratch,
                    )
                }
            }
```

The lines after the loop (`scratch.putExerciseEstimates(...)`, the cold-start display fill, `config`/`displayProjector`) stay unchanged. `applySessionProgression` (Task 1) is now only used by... nothing else — delete the private `applySessionProgression` method (its logic now lives in the observer + the stepper). Verify with the build that no other caller references it (`replayDerivedState` was its only caller).

> Note: `writeLevelUpdate`/`writeDerivedCoefficients` use `continue`, which is only valid inside a loop. Inside the lambda, change `?: continue` to `?: return@run` is **not** correct (it's inside `for (stepResult ...)`). The `for` loop makes `continue` valid — keep `continue` as written above.

- [ ] **Step 6: Verify no behavior change (instrumented + JVM)**

Run: `./gradlew :app:testDebugUnitTest --tests "*ExerciseEstimatorSimulationTest"`
Expected: PASS.

Run (requires a connected device/emulator — the replay parity test is instrumented):
`./gradlew :app:connectedAndroidTest --tests "*ReplayDerivedStateTest"`
Expected: PASS. If no device is available, note it and rely on the simulation test; flag for the reviewer to run on-device.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ReplayEngine.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ReplayEngineTest.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt
git commit -m "refactor: extract ReplayEngine with per-session observer

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `ExerciseProgressionSeriesBuilder` + repo accessor

Recompute the five chart series for one exercise by driving `ReplayEngine` over its muscle with a recording observer.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (add `getExerciseProgressionSeries` near the other read accessors, ~line 360)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilderTest.kt`

**Interfaces:**
- Consumes: `ReplayEngine`, `ReplaySnapshot`, `MuscleStrengthProjector`, `SessionSignalExtractor.aggregateSession`, `ExerciseEstimate`, `WorkoutSet`, `AppDatabase`, `DebugChartPoint` (from `ui.debug.components`).
- Produces:
  - `data class ProgressionPoint(val timestampMs: Long, val value: Float)` — local plain point (kept independent of the UI `DebugChartPoint` so the domain layer has no UI dependency).
  - `data class ExerciseProgressionSeries(val ownEstimate: List<ProgressionPoint>, val siblingsEstimate: List<ProgressionPoint>, val merged: List<ProgressionPoint>, val ownObservations: List<ProgressionPoint>, val siblingObservations: List<ProgressionPoint>)` with `companion object { fun empty(): ExerciseProgressionSeries }`.
  - `class ExerciseProgressionSeriesBuilder(engine: ReplayEngine = ReplayEngine(), projector: MuscleStrengthProjector = MuscleStrengthProjector())`
  - `suspend fun build(db: AppDatabase, exerciseId: Long): ExerciseProgressionSeries`
  - On `WorkoutRepository`: `suspend fun getExerciseProgressionSeries(exerciseId: Long): ExerciseProgressionSeries`

- [ ] **Step 1: Write the failing test**

The builder's per-session math is testable by exercising the observer logic directly via a small pure helper. Extract the per-session sampling into an internal pure function so it can be tested without a DB:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class ExerciseProgressionSeriesBuilderTest {

    private fun snapshot(): ReplaySnapshot {
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f, 2L to 0.6f),
        )
        snap.currentEstimates[1L] = ExerciseEstimate(lnE = ln(100f), confidence = 6f, updatedAt = 0L)
        snap.currentEstimates[2L] = ExerciseEstimate(lnE = ln(60f), confidence = 6f, updatedAt = 0L)
        return snap
    }

    private fun set(exerciseId: Long, weight: Float, reps: Int) = WorkoutSet(
        sessionId = 10L,
        exerciseId = exerciseId,
        setNumber = 1,
        targetWeight = weight,
        targetReps = reps,
        actualReps = reps,
        feedback = SetFeedback.RIR_2_4,
    )

    @Test
    fun siblingObservationsAreRescaledIntoTargetSpace() {
        val snap = snapshot()
        // Sibling 2 performed at an observed 1RM; target is 1. Rescale factor = seed[1]/seed[2].
        val siblingSets = listOf(set(exerciseId = 2L, weight = 60f, reps = 5))
        val agg = io.github.fowles.stochastic_strength.domain.SessionSignalExtractor.aggregateSession(siblingSets)!!
        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snap,
            sets = siblingSets,
            asOf = 1_000L,
            projector = MuscleStrengthProjector(),
        )
        assertEquals(1, sample.siblingObservations.size)
        val expected = agg.est1RM * (1.0f / 0.6f)
        assertEquals(expected, sample.siblingObservations.first().value, 1e-2f)
        // No own observation this session (target had no sets).
        assertEquals(0, sample.ownObservations.size)
    }

    @Test
    fun leaveOneOutLineExcludesTargetVote() {
        val snap = snapshot()
        // Make target (1) artificially huge; leave-one-out must ignore it and reflect sibling 2.
        snap.currentEstimates[1L] = ExerciseEstimate(lnE = ln(1000f), confidence = 6f, updatedAt = 0L)
        val sample = sampleSession(
            targetId = 1L,
            muscleIds = listOf(1L, 2L),
            snapshot = snap,
            sets = listOf(set(exerciseId = 1L, weight = 100f, reps = 5)),
            asOf = 1_000L,
            projector = MuscleStrengthProjector(),
        )
        // Sibling 2 at 60 with seed 0.6 implies level ~100, so target prediction ~100*1.0 = 100,
        // NOT ~1000. Far below the inflated own estimate.
        assertEquals(1, sample.siblingsEstimate.size)
        org.junit.Assert.assertTrue(sample.siblingsEstimate.first().value < 200f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ExerciseProgressionSeriesBuilderTest"`
Expected: FAIL — `sampleSession` / `MuscleStrengthProjector` symbols unresolved in this file.

- [ ] **Step 3: Create the builder + the pure `sampleSession` helper**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.SessionSignalExtractor
import kotlin.math.exp
import kotlin.math.ln

data class ProgressionPoint(val timestampMs: Long, val value: Float)

data class ExerciseProgressionSeries(
    val ownEstimate: List<ProgressionPoint>,
    val siblingsEstimate: List<ProgressionPoint>,
    val merged: List<ProgressionPoint>,
    val ownObservations: List<ProgressionPoint>,
    val siblingObservations: List<ProgressionPoint>,
) {
    companion object {
        fun empty() = ExerciseProgressionSeries(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** One session's contribution to the five series. Pure; no DB. */
internal data class SessionSample(
    val ownEstimate: List<ProgressionPoint>,
    val siblingsEstimate: List<ProgressionPoint>,
    val merged: List<ProgressionPoint>,
    val ownObservations: List<ProgressionPoint>,
    val siblingObservations: List<ProgressionPoint>,
)

/**
 * Computes one session's samples for [targetId], given the post-step [snapshot] (estimates already
 * folded for [asOf]) and the session's [sets]. Lines are sampled only when the target's muscle was
 * touched; dots come straight from the session's observed aggregates.
 */
internal fun sampleSession(
    targetId: Long,
    muscleIds: List<Long>,
    snapshot: ReplaySnapshot,
    sets: List<io.github.fowles.stochastic_strength.data.model.WorkoutSet>,
    asOf: Long,
    projector: MuscleStrengthProjector,
): SessionSample {
    val targetSeed = snapshot.seedCoefficients[targetId] ?: 0f

    // Lines: own estimate, leave-one-out siblings prediction, engine merged effectiveE1rm.
    val ownEstimate = snapshot.currentEstimates[targetId]?.let {
        listOf(ProgressionPoint(asOf, exp(it.lnE)))
    } ?: emptyList()

    val fullProjection = projector.project(snapshot.currentEstimates, snapshot.seedCoefficients, muscleIds, asOf)
    val merged = fullProjection.effectiveE1rm[targetId]?.let { listOf(ProgressionPoint(asOf, it)) } ?: emptyList()

    val leaveOneOut = projector.project(
        snapshot.currentEstimates, snapshot.seedCoefficients, muscleIds.filter { it != targetId }, asOf,
    )
    val siblingsEstimate = if (targetSeed > 0f && leaveOneOut.level > 0f) {
        listOf(ProgressionPoint(asOf, leaveOneOut.level * targetSeed))
    } else {
        emptyList()
    }

    // Dots: own + sibling observed aggregates, siblings rescaled into target space.
    val byExercise = sets.groupBy { it.exerciseId }
    val ownObservations = byExercise[targetId]?.let { exSets ->
        SessionSignalExtractor.aggregateSession(exSets)?.let { listOf(ProgressionPoint(asOf, it.est1RM)) }
    }.orEmpty()

    val siblingObservations = byExercise.entries.mapNotNull { (id, exSets) ->
        if (id == targetId) return@mapNotNull null
        val sibSeed = snapshot.seedCoefficients[id] ?: return@mapNotNull null
        if (sibSeed <= 0f || targetSeed <= 0f) return@mapNotNull null
        val agg = SessionSignalExtractor.aggregateSession(exSets) ?: return@mapNotNull null
        ProgressionPoint(asOf, agg.est1RM * (targetSeed / sibSeed))
    }

    return SessionSample(ownEstimate, siblingsEstimate, merged, ownObservations, siblingObservations)
}

/**
 * Recomputes the five progression series for one exercise by replaying its muscle through the same
 * engine the production replay uses. On-demand; touches no durable derived state.
 */
class ExerciseProgressionSeriesBuilder(
    private val engine: ReplayEngine = ReplayEngine(),
    private val projector: MuscleStrengthProjector = MuscleStrengthProjector(),
) {
    suspend fun build(db: AppDatabase, exerciseId: Long): ExerciseProgressionSeries {
        val snapshot = ReplaySnapshot.loadStaticFromDb(db)
        val muscle = snapshot.exerciseMuscle[exerciseId] ?: return ExerciseProgressionSeries.empty()
        val muscleIds = snapshot.muscleExerciseIds[muscle] ?: return ExerciseProgressionSeries.empty()
        if (exerciseId !in muscleIds) return ExerciseProgressionSeries.empty() // unloadable / bodyweight target

        val ownEstimate = mutableListOf<ProgressionPoint>()
        val siblingsEstimate = mutableListOf<ProgressionPoint>()
        val merged = mutableListOf<ProgressionPoint>()
        val ownObservations = mutableListOf<ProgressionPoint>()
        val siblingObservations = mutableListOf<ProgressionPoint>()

        engine.run(db, snapshot) { _, asOf, sets, snap, result ->
            // Only sample sessions that touched this muscle.
            if (result.steps.any { it.muscle == muscle }) {
                val sample = sampleSession(exerciseId, muscleIds, snap, sets, asOf, projector)
                ownEstimate += sample.ownEstimate
                siblingsEstimate += sample.siblingsEstimate
                merged += sample.merged
                ownObservations += sample.ownObservations
                siblingObservations += sample.siblingObservations
            }
        }

        return ExerciseProgressionSeries(
            ownEstimate = ownEstimate,
            siblingsEstimate = siblingsEstimate,
            merged = merged,
            ownObservations = ownObservations,
            siblingObservations = siblingObservations,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*ExerciseProgressionSeriesBuilderTest"`
Expected: PASS.

- [ ] **Step 5: Add the repo accessor**

In `WorkoutRepository.kt`, near the other read accessors (~line 360), add:

```kotlin
    private val progressionSeriesBuilder = ExerciseProgressionSeriesBuilder()

    suspend fun getExerciseProgressionSeries(exerciseId: Long): ExerciseProgressionSeries =
        progressionSeriesBuilder.build(db, exerciseId)
```

Add the import for `ExerciseProgressionSeriesBuilder` / `ExerciseProgressionSeries`.

- [ ] **Step 6: Build**

Run: `./gradlew :app:testDebugUnitTest --tests "*ExerciseProgressionSeriesBuilderTest" --tests "*SessionProgressionStepperTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilderTest.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt
git commit -m "feat: ExerciseProgressionSeriesBuilder + repo accessor

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Cross-tuning computation + repo accessor

Compute, per muscle, each exercise's agreement-vs-consensus and contribution share from the live estimate snapshot.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/CrossTuning.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (add `getCrossTuning` near the other accessors)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/CrossTuningTest.kt`

**Interfaces:**
- Consumes: `ExerciseEstimate`, `MuscleStrengthProjector`, `ExerciseEstimateUpdater.decayedConfidence`, `AppDatabase`, `ReplaySnapshot`, `DerivedStateStore` snapshot (`exerciseEstimates()`).
- Produces:
  - `data class CrossTuningRow(val exerciseId: Long, val name: String, val agreement: Float, val contribution: Float)`
  - `fun computeCrossTuning(estimates: Map<Long, ExerciseEstimate>, seedCoef: Map<Long, Float>, namesById: Map<Long, String>, muscleExerciseIds: List<Long>, now: Long, projector: MuscleStrengthProjector = MuscleStrengthProjector(), updater: ExerciseEstimateUpdater = ExerciseEstimateUpdater()): List<CrossTuningRow>`
  - On `WorkoutRepository`: `suspend fun getCrossTuning(muscle: MuscleGroup, now: Long = System.currentTimeMillis()): List<CrossTuningRow>`

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class CrossTuningTest {

    private fun est(e1rm: Float, conf: Float) = ExerciseEstimate(lnE = ln(e1rm), confidence = conf, updatedAt = 0L)

    @Test
    fun agreementIsPositiveWhenExerciseExceedsConsensus() {
        // Exercise 1 is stronger than its seed ratio vs sibling 2 implies → positive agreement.
        val estimates = mapOf(1L to est(120f, conf = 6f), 2L to est(60f, conf = 6f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val rows = computeCrossTuning(
            estimates = estimates,
            seedCoef = seed,
            namesById = mapOf(1L to "A", 2L to "B"),
            muscleExerciseIds = listOf(1L, 2L),
            now = 0L,
        )
        val a = rows.first { it.exerciseId == 1L }
        // Sibling 2 (60 at seed 0.6) implies level ~100 → prediction for 1 ~100; own is 120 → +~0.2.
        assertTrue("agreement positive when above consensus", a.agreement > 0.1f)
    }

    @Test
    fun contributionsSumToOneAndColdExerciseIsNearZero() {
        val estimates = mapOf(1L to est(100f, conf = 6f), 2L to est(60f, conf = 0f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val rows = computeCrossTuning(
            estimates = estimates,
            seedCoef = seed,
            namesById = mapOf(1L to "A", 2L to "B"),
            muscleExerciseIds = listOf(1L, 2L),
            now = 0L,
        )
        val sum = rows.sumOf { it.contribution.toDouble() }.toFloat()
        assertEquals(1f, sum, 1e-3f)
        assertTrue(rows.first { it.exerciseId == 2L }.contribution < 0.05f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*CrossTuningTest"`
Expected: FAIL — `computeCrossTuning` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.exp

data class CrossTuningRow(
    val exerciseId: Long,
    val name: String,
    /** (ownE1rm - leaveOneOutPrediction) / leaveOneOutPrediction; signed. 0 when no consensus exists. */
    val agreement: Float,
    /** This exercise's decayed confidence as a share of the muscle's total (0..1). */
    val contribution: Float,
)

/**
 * Per-muscle cross-tuning at [now]: how far each exercise's own estimate sits from what its siblings
 * predict (agreement), and how much of the muscle's total decayed confidence it carries (contribution).
 * Sorted by agreement descending. Pure.
 */
fun computeCrossTuning(
    estimates: Map<Long, ExerciseEstimate>,
    seedCoef: Map<Long, Float>,
    namesById: Map<Long, String>,
    muscleExerciseIds: List<Long>,
    now: Long,
    projector: MuscleStrengthProjector = MuscleStrengthProjector(),
    updater: ExerciseEstimateUpdater = ExerciseEstimateUpdater(),
): List<CrossTuningRow> {
    val confById = muscleExerciseIds.associateWith { id ->
        estimates[id]?.let { updater.decayedConfidence(it, now) } ?: 0f
    }
    val totalConf = confById.values.sum()

    val rows = muscleExerciseIds.mapNotNull { id ->
        val estimate = estimates[id] ?: return@mapNotNull null
        val seed = seedCoef[id] ?: return@mapNotNull null
        if (seed <= 0f) return@mapNotNull null
        val name = namesById[id] ?: return@mapNotNull null

        val leaveOneOut = projector.project(estimates, seedCoef, muscleExerciseIds.filter { it != id }, now)
        val prediction = leaveOneOut.level * seed
        val ownE1rm = exp(estimate.lnE)
        val agreement = if (prediction > 0f) ownE1rm / prediction - 1f else 0f
        val contribution = if (totalConf > 0f) (confById[id] ?: 0f) / totalConf else 0f

        CrossTuningRow(exerciseId = id, name = name, agreement = agreement, contribution = contribution)
    }
    return rows.sortedByDescending { it.agreement }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*CrossTuningTest"`
Expected: PASS.

- [ ] **Step 5: Add the repo accessor**

In `WorkoutRepository.kt`, add near the other accessors:

```kotlin
    suspend fun getCrossTuning(
        muscle: MuscleGroup,
        now: Long = System.currentTimeMillis(),
    ): List<CrossTuningRow> {
        val snapshot = ReplaySnapshot.loadStaticFromDb(db)
        val muscleIds = snapshot.muscleExerciseIds[muscle] ?: return emptyList()
        val estimates = derivedState.snapshot().exerciseEstimates()
        val namesById = db.exerciseDao().getAll().associate { it.id to it.name }
        return computeCrossTuning(
            estimates = estimates,
            seedCoef = snapshot.seedCoefficients,
            namesById = namesById,
            muscleExerciseIds = muscleIds,
            now = now,
        )
    }
```

Add imports for `CrossTuningRow` / `computeCrossTuning`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/CrossTuning.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/CrossTuningTest.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt
git commit -m "feat: cross-tuning computation (agreement + contribution) + repo accessor

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: `ExerciseProgressionChart` composable

A multi-series Vico chart: three lines + filled dots + hollow dots, with a legend.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/ExerciseProgressionChart.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/components/ExerciseProgressionChartTest.kt`

**Interfaces:**
- Consumes: `DebugChartPoint`, `timestampToLocalEpochDay`, `epochDayLabel`, `paddedChartRangeProvider`, Vico APIs (mirror `DebugLineChart.kt`).
- Produces:
  - `enum class ProgressionSeriesStyle { LINE, FILLED_DOTS, HOLLOW_DOTS }`
  - `data class ProgressionChartSeries(val label: String, val points: List<DebugChartPoint>, val style: ProgressionSeriesStyle, val colorRole: ProgressionColorRole)`
  - `enum class ProgressionColorRole { OWN, SIBLINGS, MERGED, OWN_OBS, SIBLING_OBS }`
  - `@Composable fun ExerciseProgressionChart(series: List<ProgressionChartSeries>, yFormatter: (Float) -> String, modifier: Modifier = Modifier)`
  - `internal fun seriesPlotOrder(series: List<ProgressionChartSeries>): List<ProgressionChartSeries>` — deterministic z-order: lines first, dots on top.

- [ ] **Step 1: Write the failing test (pure ordering helper)**

```kotlin
package io.github.fowles.stochastic_strength.ui.debug.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseProgressionChartTest {

    private fun s(label: String, style: ProgressionSeriesStyle, role: ProgressionColorRole) =
        ProgressionChartSeries(label, emptyList(), style, role)

    @Test
    fun dotsArePlottedAfterLinesSoTheyRenderOnTop() {
        val input = listOf(
            s("own dots", ProgressionSeriesStyle.FILLED_DOTS, ProgressionColorRole.OWN_OBS),
            s("own line", ProgressionSeriesStyle.LINE, ProgressionColorRole.OWN),
            s("sib dots", ProgressionSeriesStyle.HOLLOW_DOTS, ProgressionColorRole.SIBLING_OBS),
            s("merged line", ProgressionSeriesStyle.LINE, ProgressionColorRole.MERGED),
        )
        val ordered = seriesPlotOrder(input)
        assertEquals(
            listOf(ProgressionSeriesStyle.LINE, ProgressionSeriesStyle.LINE, ProgressionSeriesStyle.FILLED_DOTS, ProgressionSeriesStyle.HOLLOW_DOTS),
            ordered.map { it.style },
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*ExerciseProgressionChartTest"`
Expected: FAIL — symbols unresolved.

- [ ] **Step 3: Implement the chart**

Model the Vico wiring on `DebugLineChart.kt`. Build one `lineSeries { }` transaction with one `series(...)` per `ProgressionChartSeries` (in `seriesPlotOrder`), then a `LineProvider.series(listOf(...))` whose i-th `LineCartesianLayer.rememberLine` matches the i-th series' style/color. For `LINE`: solid fill, no `pointProvider`. For `FILLED_DOTS`/`HOLLOW_DOTS`: transparent line fill + a point. Hollow = `rememberShapeComponent` with `Fill.Transparent` + `strokeFill`/`strokeThickness`; filled = `fill(color)`.

```kotlin
package io.github.fowles.stochastic_strength.ui.debug.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

enum class ProgressionSeriesStyle { LINE, FILLED_DOTS, HOLLOW_DOTS }
enum class ProgressionColorRole { OWN, SIBLINGS, MERGED, OWN_OBS, SIBLING_OBS }

data class ProgressionChartSeries(
    val label: String,
    val points: List<DebugChartPoint>,
    val style: ProgressionSeriesStyle,
    val colorRole: ProgressionColorRole,
)

/** Lines first, dots last, so dots render on top of the lines. Stable within each group. */
internal fun seriesPlotOrder(series: List<ProgressionChartSeries>): List<ProgressionChartSeries> =
    series.sortedBy { if (it.style == ProgressionSeriesStyle.LINE) 0 else 1 }

@Composable
internal fun ExerciseProgressionChart(
    series: List<ProgressionChartSeries>,
    yFormatter: (Float) -> String,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val ordered = remember(series) { seriesPlotOrder(series) }
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(ordered, zone) {
        modelProducer.runTransaction {
            lineSeries {
                ordered.forEach { s ->
                    // A single-point or empty series still needs a slot to keep line<->style indices aligned.
                    series(
                        x = s.points.map { timestampToLocalEpochDay(it.timestampMs, zone) }.ifEmpty { listOf(0L) },
                        y = s.points.map { it.value }.ifEmpty { listOf(0f) },
                    )
                }
            }
        }
    }

    val colors = progressionColors()
    val transparent = remember { LineCartesianLayer.LineFill.single(Fill.Transparent) }
    val lines = ordered.map { s ->
        val color = colors.getValue(s.colorRole)
        when (s.style) {
            ProgressionSeriesStyle.LINE -> LineCartesianLayer.rememberLine(
                fill = LineCartesianLayer.LineFill.single(fill(color)),
            )
            ProgressionSeriesStyle.FILLED_DOTS -> LineCartesianLayer.rememberLine(
                fill = transparent,
                pointProvider = LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.point(rememberShapeComponent(fill(color), CorneredShape.Pill), size = 8.dp),
                ),
            )
            ProgressionSeriesStyle.HOLLOW_DOTS -> LineCartesianLayer.rememberLine(
                fill = transparent,
                pointProvider = LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.point(
                        rememberShapeComponent(
                            fill = fill(Color.Transparent),
                            shape = CorneredShape.Pill,
                            strokeFill = fill(color),
                            strokeThickness = 1.5.dp,
                        ),
                        size = 8.dp,
                    ),
                ),
            )
        }
    }
    val lineProvider = remember(lines) { LineCartesianLayer.LineProvider.series(lines) }
    val rangeProvider = remember { paddedChartRangeProvider() }

    val yValueFormatter = remember(yFormatter) {
        CartesianValueFormatter { _, value, _ -> yFormatter(value.toFloat()) }
    }
    val dateFormatter = remember(zone) {
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone(zone) }
        CartesianValueFormatter { _, value, _ -> epochDayLabel(value.toLong(), sdf) }
    }
    val scrollState = rememberVicoScrollState(initialScroll = Scroll.Absolute.End)

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(lineProvider = lineProvider, pointSpacing = 0.dp, rangeProvider = rangeProvider),
            startAxis = VerticalAxis.rememberStart(valueFormatter = yValueFormatter),
            bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = dateFormatter, labelRotationDegrees = 45f),
        ),
        modelProducer = modelProducer,
        scrollState = scrollState,
        modifier = modifier,
    )
}

@Composable
private fun progressionColors(): Map<ProgressionColorRole, Color> = mapOf(
    ProgressionColorRole.OWN to MaterialTheme.colorScheme.primary,
    ProgressionColorRole.SIBLINGS to MaterialTheme.colorScheme.secondary,
    ProgressionColorRole.MERGED to MaterialTheme.colorScheme.tertiary,
    ProgressionColorRole.OWN_OBS to MaterialTheme.colorScheme.primary,
    ProgressionColorRole.SIBLING_OBS to MaterialTheme.colorScheme.onSurfaceVariant,
)
```

> Per memory `reference_dynamic_color_charts`: dynamic-color `tertiary` can render as near-invisible grey on some devices — the MERGED line color must be visually verified on-device in Task 8 and swapped to an explicit accent if it disappears.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*ExerciseProgressionChartTest"`
Expected: PASS.

- [ ] **Step 5: Build the debug variant to catch Vico API misuse**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/ExerciseProgressionChart.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/components/ExerciseProgressionChartTest.kt
git commit -m "feat: ExerciseProgressionChart multi-series component

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Cross-tuning bar components

A reusable diverging bar (agreement) and a proportion bar (contribution), with a section composable.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/CrossTuningBars.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/CoefficientDeviationBar.kt` (extract the diverging-bar primitive for reuse)

**Interfaces:**
- Consumes: `CrossTuningRow` (`domain.progression`), `MAX_DEVIATION`.
- Produces:
  - `@Composable internal fun DivergingBar(value: Float, maxMagnitude: Float, modifier: Modifier)` (extracted from `DeviationRow`'s bar body).
  - `@Composable internal fun CrossTuningSection(rows: List<CrossTuningRow>, highlightedName: String? = null)` — renders, for each row, name + agreement diverging bar + agreement % label, then a thin contribution proportion bar beneath; highlighted name in bold.

- [ ] **Step 1: Extract `DivergingBar` from `CoefficientDeviationBar.kt`**

In `CoefficientDeviationBar.kt`, factor the `BoxWithConstraints` bar body of `DeviationRow` (lines 68-133) into:

```kotlin
@Composable
internal fun DivergingBar(value: Float, maxMagnitude: Float, modifier: Modifier = Modifier) {
    val positiveColor = MaterialTheme.colorScheme.primary
    val negativeColor = MaterialTheme.colorScheme.error
    val guidelineColor = MaterialTheme.colorScheme.outlineVariant
    val tickColor = guidelineColor.copy(alpha = 0.5f)
    BoxWithConstraints(modifier = modifier) {
        val halfWidth = maxWidth / 2
        for (i in 1..5) {
            val offsetDp = halfWidth * (i / 5f)
            Box(Modifier.align(Alignment.Center).offset(x = offsetDp).width(1.dp).fillMaxHeight().background(tickColor))
            Box(Modifier.align(Alignment.Center).offset(x = -offsetDp).width(1.dp).fillMaxHeight().background(tickColor))
        }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (value < 0f) {
                    val fraction = ((-value) / maxMagnitude).coerceAtMost(1f)
                    Box(Modifier.align(Alignment.CenterEnd).fillMaxWidth(fraction).height(10.dp).clip(RoundedCornerShape(2.dp)).background(negativeColor))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (value > 0f) {
                    val fraction = (value / maxMagnitude).coerceAtMost(1f)
                    Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(fraction).height(10.dp).clip(RoundedCornerShape(2.dp)).background(positiveColor))
                }
            }
        }
        Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(guidelineColor))
    }
}
```

Leave the rest of `CoefficientDeviationBar.kt` (`CoefficientDeviationList`, `DeviationRow`, `formatDeviation`, `MAX_DEVIATION`) in place for now — Task 8 deletes the parts that go unused once both screens migrate. `DeviationRow` may call `DivergingBar` to avoid duplication, but that is optional.

- [ ] **Step 2: Create `CrossTuningBars.kt`**

```kotlin
package io.github.fowles.stochastic_strength.ui.debug.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.domain.progression.CrossTuningRow

@Composable
internal fun CrossTuningSection(rows: List<CrossTuningRow>, highlightedName: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        rows.forEach { row ->
            CrossTuningItem(row, highlighted = row.name == highlightedName)
        }
    }
}

@Composable
private fun CrossTuningItem(row: CrossTuningRow, highlighted: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlighted) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(140.dp),
            )
            DivergingBar(
                value = row.agreement,
                maxMagnitude = MAX_DEVIATION,
                modifier = Modifier.weight(1f).height(16.dp).padding(horizontal = 4.dp),
            )
            Text(
                text = formatSignedPercent(row.agreement),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp),
            )
        }
        ContributionBar(row.contribution)
    }
}

@Composable
private fun ContributionBar(contribution: Float) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(140.dp)) {
            Text(
                text = "contribution",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier.weight(1f).height(6.dp).padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                Modifier.fillMaxWidth(contribution.coerceIn(0f, 1f)).height(6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.secondary),
            )
        }
        Text(
            text = "%.0f%%".format(contribution * 100f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

private fun formatSignedPercent(value: Float): String {
    val pct = (value * 100f).toInt()
    return if (pct >= 0) "+$pct%" else "$pct%"
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/CrossTuningBars.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/CoefficientDeviationBar.kt
git commit -m "feat: cross-tuning bar components (agreement + contribution)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Wire the exercise-detail screen

Replace the coefficient chart with `ExerciseProgressionChart`; replace the deviation list with `CrossTuningSection` (self highlighted).

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailScreen.kt`

**Interfaces:**
- Consumes: `WorkoutRepository.getExerciseProgressionSeries`, `WorkoutRepository.getCrossTuning`, `ExerciseProgressionSeries`, `CrossTuningRow`, `ExerciseProgressionChart` + its series/style/role types, `CrossTuningSection`, `DebugChartPoint`.
- Produces (state changes): `ExerciseCoefficientDetailState` gains `val progressionSeries: List<ProgressionChartSeries>` and `val crossTuning: List<CrossTuningRow>`; drops `chartPoints` and `coefficientDeviations`.

- [ ] **Step 1: Update the ViewModel**

In `ExerciseCoefficientDetailViewModel.kt`:
- Change `ExerciseCoefficientDetailState` to drop `chartPoints` / `coefficientDeviations` and add `progressionSeries: List<ProgressionChartSeries> = emptyList()` and `crossTuning: List<CrossTuningRow> = emptyList()`.
- In `init`, replace the `chartPoints` and `deviations`/`coefficientDeviations` construction with:

```kotlin
            val series = repository.getExerciseProgressionSeries(exerciseId)
            fun pts(list: List<io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint>) =
                list.map { DebugChartPoint(it.timestampMs, it.value) }
            val progressionSeries = listOf(
                ProgressionChartSeries("Own estimate", pts(series.ownEstimate), ProgressionSeriesStyle.LINE, ProgressionColorRole.OWN),
                ProgressionChartSeries("Siblings", pts(series.siblingsEstimate), ProgressionSeriesStyle.LINE, ProgressionColorRole.SIBLINGS),
                ProgressionChartSeries("Merged", pts(series.merged), ProgressionSeriesStyle.LINE, ProgressionColorRole.MERGED),
                ProgressionChartSeries("Sessions", pts(series.ownObservations), ProgressionSeriesStyle.FILLED_DOTS, ProgressionColorRole.OWN_OBS),
                ProgressionChartSeries("Siblings (scaled)", pts(series.siblingObservations), ProgressionSeriesStyle.HOLLOW_DOTS, ProgressionColorRole.SIBLING_OBS),
            )
            val crossTuning = repository.getCrossTuning(exercise.primaryMuscle)
```

- Pass `progressionSeries = progressionSeries, crossTuning = crossTuning` into the `_state.value = ExerciseCoefficientDetailState(...)`.
- Remove the now-unused `computeCoefficientDeviations`/`getLatestCoefficientPerExercise`/`ExerciseCoefficients` usage and imports.
- Add imports for `DebugChartPoint`, `ProgressionChartSeries`, `ProgressionSeriesStyle`, `ProgressionColorRole`, `CrossTuningRow`.

- [ ] **Step 2: Update the screen**

In `ExerciseCoefficientDetailScreen.kt`:
- Replace the first `item { SectionHeader("Coefficient over time" ...) }` + its `DebugLineChart`/empty block with:

```kotlin
            item { SectionHeader("Estimated 1RM over time", verticalPadding = 4.dp) }
            item {
                val hasData = state.progressionSeries.any { it.points.isNotEmpty() }
                if (!hasData) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("No sessions yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    ExerciseProgressionChart(
                        series = state.progressionSeries,
                        yFormatter = { value -> WeightFormatter.format(value, state.weightUnit) },
                        modifier = Modifier.fillMaxWidth().height(220.dp).padding(horizontal = 16.dp),
                    )
                    ProgressionLegend(state.progressionSeries)
                }
            }
```

- Replace the "Coefficient vs seed" section + `CoefficientDeviationList` with:

```kotlin
            item { SectionHeader("Cross-tuning", verticalPadding = 4.dp) }
            item {
                if (state.crossTuning.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("No weighted exercises", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    CrossTuningSection(rows = state.crossTuning, highlightedName = state.exercise?.name)
                }
            }
```

- Add a small `ProgressionLegend(series)` composable in this file (a `FlowRow`/`Row` of colored swatch + label per series; reuse `progressionColors` mapping by label). Minimal version:

```kotlin
@Composable
private fun ProgressionLegend(series: List<ProgressionChartSeries>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        series.filter { it.points.isNotEmpty() }.forEach { s ->
            Text(s.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
```

- `ExerciseCoefficientDetailState` has no `weightUnit` today — add `val weightUnit: WeightUnit = WeightUnit.KG` to the state and load it in the ViewModel `init` (`app.database.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG`), mirroring `MuscleBaselineDetailViewModel`. Add the `WeightUnit` / `WeightFormatter` imports to both files. Remove the now-unused `DebugLineChart` import.

- [ ] **Step 3: Build + existing screen tests**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailScreen.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt
git commit -m "feat: per-exercise 1RM progression chart + cross-tuning on exercise detail

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: Wire the muscle-detail screen + retire the deviation list

Replace "Coefficient vs seed" on the muscle screen with `CrossTuningSection`; delete the now-dead `computeCoefficientDeviations` / `CoefficientDeviationRow` / `CoefficientDeviationList`; verify on-device.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailScreen.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/CoefficientDeviationBar.kt` (delete `CoefficientDeviationList` + `DeviationRow` + `formatDeviation`; keep `DivergingBar` + `MAX_DEVIATION`)
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModelTest.kt` (drop tests of `computeCoefficientDeviations` / `CoefficientDeviationRow`)

**Interfaces:**
- Consumes: `WorkoutRepository.getCrossTuning`, `CrossTuningRow`, `CrossTuningSection`.
- Produces (state changes): `MuscleBaselineDetailState` swaps `coefficientDeviations: List<CoefficientDeviationRow>` for `crossTuning: List<CrossTuningRow>`.

- [ ] **Step 1: Update the muscle ViewModel**

In `MuscleBaselineDetailViewModel.kt`:
- Delete `computeCoefficientDeviations`, the `CoefficientDeviationRow` data class, and the `coefficientDeviations` construction in `init` (lines ~167-172).
- Add `val crossTuning: List<CrossTuningRow> = emptyList()` to `MuscleBaselineDetailState`, removing `coefficientDeviations`.
- In `init`, replace the deviation construction with `val crossTuning = repository.getCrossTuning(muscleGroup)` and pass it into the state.
- Keep `buildBaselineChartPoints`, `buildExerciseBlocks`, `formatBaselineSetLine` (still used). Remove `ExerciseCoefficients` import if now unused.

- [ ] **Step 2: Update the muscle screen**

In `MuscleBaselineDetailScreen.kt`, replace the "Coefficient vs seed" section + `CoefficientDeviationList(state.coefficientDeviations)` with:

```kotlin
            item { SectionHeader("Cross-tuning", verticalPadding = 4.dp) }
            item {
                if (state.crossTuning.isEmpty()) {
                    EmptyDeviationsPlaceholder()
                } else {
                    CrossTuningSection(state.crossTuning)
                }
            }
```

Update the import from `CoefficientDeviationList` to `CrossTuningSection`.

- [ ] **Step 3: Delete the dead deviation widgets**

In `CoefficientDeviationBar.kt`, delete `CoefficientDeviationList`, `DeviationRow`, and `formatDeviation`; keep `DivergingBar` and `const val MAX_DEVIATION`. Remove the now-unused `CoefficientDeviationRow` import.

Search for any remaining references and confirm none survive:

Run: `rg -n "CoefficientDeviationList|CoefficientDeviationRow|computeCoefficientDeviations" app/src`
Expected: no matches (or only the deleted-file diff).

- [ ] **Step 4: Fix the muscle ViewModel test**

In `MuscleBaselineDetailViewModelTest.kt`, delete any test exercising `computeCoefficientDeviations` / `CoefficientDeviationRow`. Keep tests for `buildBaselineChartPoints` / `buildExerciseBlocks` / `formatBaselineSetLine`.

- [ ] **Step 5: Full unit-test suite + build**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all green, no references to deleted symbols).

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: On-device visual verification**

Build/install and open Debug & Advanced Stats → tap an exercise with history, then tap a muscle. Confirm:
- The 1RM chart shows three distinct lines + filled/hollow dots; the **MERGED (tertiary) line is actually visible** (per `reference_dynamic_color_charts` — if it renders as invisible grey, change `ProgressionColorRole.MERGED` to an explicit accent color in `ExerciseProgressionChart.progressionColors`).
- Cross-tuning bars render on both screens; the current exercise is bolded on the exercise screen.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailScreen.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/CoefficientDeviationBar.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModelTest.kt
git commit -m "feat: cross-tuning on muscle detail; retire coefficient-deviation list

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Notes for the implementer

- The seed coefficient for an exercise comes from `ExerciseCoefficients.get(exercise)`; `ReplaySnapshot.seedCoefficients` already holds it per id (0f for unloadable). Never recompute it elsewhere.
- The `ReplaySnapshot` for the series builder / cross-tuning is loaded fresh and is independent of the live `DerivedStateStore`, so these reads need no `replayMutex` and never mutate durable state.
- All series values are in 1RM kg; only the screen converts to display units.
- If `./gradlew :app:connectedAndroidTest` has no device, say so explicitly and flag the `ReplayDerivedStateTest` parity check for manual on-device confirmation — do not claim it passed.
