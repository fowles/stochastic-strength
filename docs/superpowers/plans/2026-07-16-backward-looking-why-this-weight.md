# Backward-looking "Why this weight" Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the debug exercise-detail "Why this weight" trace, cross-tuning bars, and estimate lines all follow the selected chart point under a backward-looking ("decision entering the session") semantics, and add a synthetic "predicted today" point that is the default selection.

**Architecture:** Extend the single per-exercise replay in `ExerciseProgressionSeriesBuilder` to (a) sample every per-session frame from the *pre-fold* belief state (the state that produced that session's weights, captured via `ReplayEngine`'s `beforeSession` hook), (b) attach a per-session `PrescriptionTrace` built from a rolling `PolicyFacts` window of prior sets, and (c) append one synthetic "today" frame from the live post-final-fold state. The ViewModel keys frames+traces by epoch day; the screen's existing selection variable drives all three sections. `WorkoutRepository.getPrescriptionTrace` is removed (its single caller now reads the synthetic frame).

**Tech Stack:** Kotlin, Jetpack Compose, Vico charts, Room, JUnit4 (JVM unit tests).

## Global Constraints

- Display-only change. The `BeliefScoreTest` / `BeliefPolicyBacktestTest` backtest gates MUST stay green and unchanged — do not touch fold/pooling/policy math or `BeliefConfig`.
- No pipeline math may be re-implemented in display code: the trace is assembled ONLY by `PrescriptionTraceBuilder`, which reads `BeliefPooling` / `PrescriptionPolicy` outputs (per CLAUDE.md progression rules).
- No new persisted state / no Room schema bump; everything is recomputed on-demand in the existing replay.
- Follow the established `run` (DB adapter) / `runCore` (DB-free pure core) split when adding a testable core (mirrors `ReplayEngine`).
- Remove any imports/dependencies left unused by deletions (global CLAUDE.md).
- Run the most specific test target after each change; run the full JVM suite at the end. Commit at each task (jj; user owns reshape + push).

Build/test commands:
- Single JVM test class: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.<FQCN>"`
- Full JVM suite: `./gradlew :app:testDebugUnitTest`
- Instrumented (device attached): `./gradlew :app:connectedAndroidTest`

---

### Task 1: Add `trace` to `ProgressionFrame` and thread it through `buildFrame`

Carries a per-session `PrescriptionTrace` on the frame so the ViewModel can render it per selection.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilderTest.kt`

**Interfaces:**
- Produces: `ProgressionFrame(..., val trace: PrescriptionTrace? = null)`; `buildFrame(..., trace: PrescriptionTrace? = null)` now sets `frame.trace`.

- [ ] **Step 1: Update the existing frame test to assert the trace passes through**

In `ExerciseProgressionSeriesBuilderTest.kt`, add this test:

```kotlin
@Test
fun buildFrameCarriesThePassedTrace() {
    val snap = snapshot()
    val names = mapOf(1L to "Bench", 2L to "Incline")
    val sets = listOf(set(exerciseId = 1L, weight = 100f, reps = 5))
    val sample = sampleSession(1L, listOf(1L, 2L), snap, sets, 1_000L, config)
    val trace = io.github.fowles.stochastic_strength.domain.belief.PrescriptionTrace(
        lines = emptyList(), finalWeightKg = 42f,
    )
    val frame = buildFrame(
        targetId = 1L, muscleIds = listOf(1L, 2L), snapshot = snap,
        sets = sets, asOf = 1_000L, namesById = names, config = config, sample = sample,
        trace = trace,
    )
    assertEquals(42f, frame.trace!!.finalWeightKg, 0f)
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionSeriesBuilderTest"`
Expected: FAIL — `buildFrame` has no `trace` parameter / `ProgressionFrame` has no `trace`.

- [ ] **Step 3: Add the field and parameter**

In `ExerciseProgressionSeriesBuilder.kt`, add the import near the other `belief` imports:

```kotlin
import io.github.fowles.stochastic_strength.domain.belief.PrescriptionTrace
```

Add `trace` to the data class:

```kotlin
data class ProgressionFrame(
    val timestampMs: Long,
    val own: Float?,
    val siblings: Float?,
    val merged: Float?,
    val crossTuning: List<CrossTuningRow>,
    val observations: List<SessionExerciseObservation>,
    val trace: PrescriptionTrace? = null,
)
```

Add a `trace` parameter to `buildFrame` (signature and the returned frame):

```kotlin
internal fun buildFrame(
    targetId: Long,
    muscleIds: List<Long>,
    snapshot: ReplaySnapshot,
    sets: List<WorkoutSet>,
    asOf: Long,
    namesById: Map<Long, String>,
    config: BeliefConfig,
    sample: SessionSample,
    trace: PrescriptionTrace? = null,
): ProgressionFrame {
```

…and in its `return ProgressionFrame(...)` add `trace = trace,` as the final argument.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionSeriesBuilderTest"`
Expected: PASS (all tests in the class, including the pre-existing ones).

- [ ] **Step 5: Commit**

```bash
jj describe -m "feat(progression): ProgressionFrame carries an optional PrescriptionTrace

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
jj new
```

---

### Task 2: `buildSessionTrace` — per-decision trace from a rolling `PolicyFacts` window

Wraps `PrescriptionTraceBuilder.build` with the facts assembly (rolling window + cap session), given a belief snapshot and the sets known *before* the decision.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BuildSessionTraceTest.kt` (create)

**Interfaces:**
- Consumes: `PolicyFacts.build(sets, exerciseMuscle)`, `PrescriptionPolicy.FACTS_WINDOW_MS`, `PrescriptionTraceBuilder.build(...)`.
- Produces:
  ```kotlin
  internal fun buildSessionTrace(
      targetId: Long,
      muscle: MuscleGroup,
      beliefs: Map<Long, Belief>,
      seedCoef: Map<Long, Float>,
      muscleExerciseIds: List<Long>,
      exerciseMuscle: Map<Long, MuscleGroup>,
      priorSets: List<WorkoutSet>,
      sessionReps: Int,
      now: Long,
      weightUnit: WeightUnit,
      config: BeliefConfig,
      engine: ProgressionEngine = DefaultProgressionEngine,
  ): PrescriptionTrace?
  ```

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BuildSessionTraceTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class BuildSessionTraceTest {
    private val config = BeliefConfig()

    private fun completedSet(exerciseId: Long, weight: Float, reps: Int, actual: Int, fb: SetFeedback, at: Long) =
        WorkoutSet(
            sessionId = at, exerciseId = exerciseId, setNumber = 1,
            targetWeight = weight, targetReps = reps, actualReps = actual,
            feedback = fb, completedAt = at,
        )

    @Test
    fun capBindsWhenPriorSessionFailedTheWantedWeight() {
        // Belief wants a heavy weight; a recent failed (TOO_HARD) session at that weight must
        // surface a binding capacity cap in the trace.
        val beliefs = mapOf(1L to Belief(mu = ln(100f), sigma2 = 0.0001f, updatedAt = 0L))
        val now = 100_000_000L
        val priorSets = listOf(
            completedSet(1L, weight = 100f, reps = 5, actual = 2, fb = SetFeedback.TOO_HARD, at = now - 1_000L),
        )
        val trace = buildSessionTrace(
            targetId = 1L,
            muscle = MuscleGroup.CHEST,
            beliefs = beliefs,
            seedCoef = mapOf(1L to 1.0f),
            muscleExerciseIds = listOf(1L),
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST),
            priorSets = priorSets,
            sessionReps = 5,
            now = now,
            weightUnit = WeightUnit.KG,
            config = config,
        )
        assertNotNull(trace)
        // The capacity-cap line reports a binding cap (mentions "capped").
        assertTrue(trace!!.lines.any { it.label == "Capacity cap" && it.detail.contains("capped") })
    }

    @Test
    fun setsOutsideTheFactsWindowDoNotFormFacts() {
        val beliefs = mapOf(1L to Belief(mu = ln(100f), sigma2 = 0.0001f, updatedAt = 0L))
        val now = 100_000_000L
        // A failed session OLDER than the facts window must be ignored (no binding cap).
        val stale = now - PrescriptionPolicy.FACTS_WINDOW_MS - 1_000L
        val priorSets = listOf(
            completedSet(1L, weight = 100f, reps = 5, actual = 2, fb = SetFeedback.TOO_HARD, at = stale),
        )
        val trace = buildSessionTrace(
            targetId = 1L, muscle = MuscleGroup.CHEST, beliefs = beliefs,
            seedCoef = mapOf(1L to 1.0f), muscleExerciseIds = listOf(1L),
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST), priorSets = priorSets,
            sessionReps = 5, now = now, weightUnit = WeightUnit.KG, config = config,
        )
        assertNotNull(trace)
        assertTrue(trace!!.lines.any { it.label == "Capacity cap" && it.detail == "no cap" })
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BuildSessionTraceTest"`
Expected: FAIL — `buildSessionTrace` unresolved.

- [ ] **Step 3: Implement `buildSessionTrace`**

In `ExerciseProgressionSeriesBuilder.kt`, add imports:

```kotlin
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ProgressionEngine
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.PrescriptionTraceBuilder
import io.github.fowles.stochastic_strength.domain.policy.PolicyFacts
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
```

Add the function (top-level in the file, near `buildFrame`):

```kotlin
/**
 * The "why this weight" trace for one exercise's decision made at [now], given the belief snapshot
 * that produced it ([beliefs]) and the completed sets known at that moment ([priorSets] — the sets
 * from sessions BEFORE this decision). Facts are rebuilt over [PrescriptionPolicy.FACTS_WINDOW_MS]
 * exactly like the live planner's context, so the trace matches the production pipeline. Pure; no DB.
 */
internal fun buildSessionTrace(
    targetId: Long,
    muscle: MuscleGroup,
    beliefs: Map<Long, Belief>,
    seedCoef: Map<Long, Float>,
    muscleExerciseIds: List<Long>,
    exerciseMuscle: Map<Long, MuscleGroup>,
    priorSets: List<WorkoutSet>,
    sessionReps: Int,
    now: Long,
    weightUnit: WeightUnit,
    config: BeliefConfig,
    engine: ProgressionEngine = DefaultProgressionEngine,
): PrescriptionTrace? {
    val windowStart = now - PrescriptionPolicy.FACTS_WINDOW_MS
    val factsSets = priorSets.filter { it.completedAt != null && it.completedAt!! >= windowStart }
    val facts = PolicyFacts.build(sets = factsSets, exerciseMuscle = exerciseMuscle)
    val capFact = facts.capByExercise[targetId]
    val capSessionSets = capFact?.let { f ->
        factsSets.groupBy { it.sessionId }
            .values
            .firstOrNull { s -> s.maxOf { it.completedAt!! } == f.demonstratedAt }
    }.orEmpty()
    return PrescriptionTraceBuilder.build(
        exerciseId = targetId,
        muscle = muscle,
        beliefs = beliefs,
        seedCoef = seedCoef,
        muscleExerciseIds = muscleExerciseIds,
        facts = facts,
        capSessionSets = capSessionSets,
        sessionReps = sessionReps,
        now = now,
        weightUnit = weightUnit,
        config = config,
        engine = engine,
    )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BuildSessionTraceTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj describe -m "feat(progression): buildSessionTrace assembles a per-decision PrescriptionTrace

Rolling PolicyFacts window over prior sets, reusing PrescriptionTraceBuilder.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
jj new
```

---

### Task 3: Expose the pre-fold `beforeSession` hook on `ReplayEngine.run`

`runCore` already takes `beforeSession`; `run` (the DB adapter) does not forward it. Add the pass-through so the series builder can capture pre-fold beliefs.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ReplayEngine.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ReplayEngineTest.kt`

**Interfaces:**
- Produces: `suspend fun run(db, snapshot, beforeSession: ((Map<Long, Belief>, Long) -> Unit)? = null, observer: SessionObserver)`.

Note: `beforeSession` is placed BEFORE `observer` so existing call sites using a trailing-lambda `observer` keep compiling unchanged (the trailing lambda still binds to `observer`).

- [ ] **Step 1: Write the failing test**

Add to `ReplayEngineTest.kt`:

```kotlin
@Test
fun runCoreInvokesBeforeSessionPreFold() {
    // beforeSession must fire once per session, BEFORE the fold mutates beliefs.
    val snap = ReplaySnapshot(
        exerciseMuscle = mapOf(1L to io.github.fowles.stochastic_strength.data.model.MuscleGroup.CHEST),
        seedCoefficients = mapOf(1L to 1.0f),
    )
    val session = io.github.fowles.stochastic_strength.data.model.WorkoutSession(
        id = 1L, startTime = 0L, endTime = 1_000L,
    )
    val sets = listOf(
        io.github.fowles.stochastic_strength.data.model.WorkoutSet(
            sessionId = 1L, exerciseId = 1L, setNumber = 1,
            targetWeight = 100f, targetReps = 5, actualReps = 5,
            feedback = io.github.fowles.stochastic_strength.data.model.SetFeedback.RIR_2_4,
            completedAt = 1_000L,
        ),
    )
    var beforeCount = 0
    var beliefsPresentAtBefore = true
    kotlinx.coroutines.runBlocking {
        ReplayEngine().runCore(
            snapshot = snap,
            initialOverrides = emptyList(),
            sessionOverrides = emptyMap(),
            sessions = listOf(session),
            setsForSession = { sets },
            observer = { _, _, _, _, _ -> },
            beforeSession = { beliefs, _ ->
                beforeCount++
                // No initial override, so pre-fold this exercise has no belief yet.
                beliefsPresentAtBefore = beliefs.containsKey(1L)
            },
        )
    }
    assertEquals(1, beforeCount)
    assertEquals(false, beliefsPresentAtBefore)
}
```

- [ ] **Step 2: Run it to verify it fails or passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ReplayEngineTest"`
Expected: This test actually PASSES already (it exercises the existing `runCore` `beforeSession`). It is a regression guard establishing the pre-fold contract the builder relies on. If it fails to compile, fix imports. Proceed to add the `run` pass-through next.

- [ ] **Step 3: Add the `beforeSession` pass-through to `run`**

In `ReplayEngine.kt`, change `run` to:

```kotlin
suspend fun run(
    db: AppDatabase,
    snapshot: ReplaySnapshot,
    beforeSession: ((beliefs: Map<Long, Belief>, asOf: Long) -> Unit)? = null,
    observer: SessionObserver,
) {
    runCore(
        snapshot = snapshot,
        initialOverrides = db.exerciseStrengthOverrideDao().getInitials(),
        sessionOverrides = db.exerciseStrengthOverrideDao().getNonInitials()
            .groupBy { it.sessionId!! },
        sessions = db.workoutSessionDao().getAll(),
        setsForSession = { db.workoutSetDao().getSetsForSession(it) },
        observer = observer,
        beforeSession = beforeSession,
    )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ReplayEngineTest"`
Expected: PASS. Also confirm the existing `WorkoutRepository.replayDerivedState` call site (`replayEngine.run(db, snapshot) { ... }`) still compiles — its trailing-lambda binds to `observer` because `beforeSession` has a default.

- [ ] **Step 5: Commit**

```bash
jj describe -m "feat(replay): ReplayEngine.run forwards the pre-fold beforeSession hook

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
jj new
```

---

### Task 4: Builder emits pre-fold frames + per-session traces + a synthetic "today" frame

Split `build` into a DB adapter and a DB-free `buildCore`; `buildCore` samples each frame from the pre-fold state, attaches a trace, and appends the synthetic today frame from the live post-final-fold state.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilderCoreTest.kt` (create)

**Interfaces:**
- Consumes: `buildSessionTrace(...)` (Task 2), `sampleSession(...)`, `buildFrame(..., trace)` (Task 1), `ReplayEngine.runCore(..., beforeSession)`.
- Produces:
  - `data class ExerciseProgressionData(val series: ExerciseProgressionSeries, val frames: List<ProgressionFrame>, val predictedFrame: ProgressionFrame? = null)`
  - `internal suspend fun ExerciseProgressionSeriesBuilder.buildCore(exerciseId, snapshot, muscle, muscleIds, namesById, weightUnit, initialOverrides, sessionOverrides, sessions, setsForSession, now): ExerciseProgressionData`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilderCoreTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.belief.Belief
import io.github.fowles.stochastic_strength.domain.belief.BeliefConfig
import io.github.fowles.stochastic_strength.domain.belief.BeliefFold
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class ExerciseProgressionSeriesBuilderCoreTest {
    private val config = BeliefConfig()
    private val builder = ExerciseProgressionSeriesBuilder(config)

    private fun setAt(exerciseId: Long, sessionId: Long, weight: Float, reps: Int, at: Long) = WorkoutSet(
        sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
        targetWeight = weight, targetReps = reps, actualReps = reps,
        feedback = SetFeedback.RIR_2_4, completedAt = at,
    )

    private fun snapshotSeeded(): ReplaySnapshot {
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f),
        )
        // Initial override seeds a belief at 100 kg so the very first pre-fold decision has a belief.
        return snap
    }

    @Test
    fun framesArePreFoldAndTrailingPredictedFrameIsLive() = runBlocking {
        val snap = snapshotSeeded()
        val initial = io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride(
            exerciseId = 1L, e1rm = 100f, asOf = 0L, sessionId = null,
        )
        val s1 = WorkoutSession(id = 1L, startTime = 0L, endTime = 1_000L)
        val s2 = WorkoutSession(id = 2L, startTime = 2_000L, endTime = 3_000L)
        val setsBySession = mapOf(
            1L to listOf(setAt(1L, 1L, weight = 100f, reps = 5, at = 1_000L)),
            2L to listOf(setAt(1L, 2L, weight = 105f, reps = 5, at = 3_000L)),
        )
        val now = 9_999_999L

        val data = builder.buildCore(
            exerciseId = 1L,
            snapshot = snap,
            muscle = MuscleGroup.CHEST,
            muscleIds = listOf(1L),
            namesById = mapOf(1L to "Bench"),
            weightUnit = WeightUnit.KG,
            initialOverrides = listOf(initial),
            sessionOverrides = emptyMap(),
            sessions = listOf(s1, s2),
            setsForSession = { setsBySession.getValue(it) },
            now = now,
        )

        // Two historical session frames + one synthetic predicted frame.
        assertEquals(2, data.frames.size)
        assertNotNull(data.predictedFrame)

        // Frame entering S1 = pre-fold = the seeded 100 kg belief.
        assertEquals(100f, data.frames[0].own!!, 0.5f)

        // Frame entering S2 = pre-fold = the belief AFTER folding S1. Compute it directly.
        val afterS1 = BeliefFold(config).foldSession(
            Belief(mu = ln(100f), sigma2 = config.sigmaSeed * config.sigmaSeed, updatedAt = 0L),
            setsBySession.getValue(1L),
            1_000L,
        )
        assertEquals(exp(afterS1.mu), data.frames[1].own!!, 0.5f)

        // The predicted frame is stamped at `now` and reflects the state AFTER S2 (differs from S2's frame).
        assertEquals(now, data.predictedFrame!!.timestampMs)
        assertTrue(data.predictedFrame!!.own!! != data.frames[1].own!!)

        // Every emitted frame carries a trace.
        assertTrue(data.frames.all { it.trace != null })
        assertNotNull(data.predictedFrame!!.trace)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionSeriesBuilderCoreTest"`
Expected: FAIL — `buildCore` unresolved / `predictedFrame` missing.

- [ ] **Step 3: Add `predictedFrame` and implement `buildCore`; rewrite `build` as the DB adapter**

In `ExerciseProgressionSeriesBuilder.kt`:

Add imports:

```kotlin
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
```

Extend `ExerciseProgressionData`:

```kotlin
data class ExerciseProgressionData(
    val series: ExerciseProgressionSeries,
    val frames: List<ProgressionFrame>,
    val predictedFrame: ProgressionFrame? = null,
)
```

Replace the `class ExerciseProgressionSeriesBuilder` body (the constructor gains a `progressionEngine`, `build` becomes a thin DB adapter, and the pure `buildCore` holds the logic). The complete new class:

```kotlin
/**
 * Recomputes the exercise progression series for one exercise by replaying its muscle through the
 * same engine the production replay uses. On-demand; touches no durable derived state.
 */
class ExerciseProgressionSeriesBuilder(
    private val config: BeliefConfig = BeliefConfig(),
    // Built from the SAME config: the engine's folds and this builder's dots/lines must never
    // read different constants (the passed-config-ignored seam bit phase 4 once already).
    private val engine: ReplayEngine = ReplayEngine(config),
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
) {
    /** DB adapter: loads the static inputs, then delegates to the DB-free [buildCore]. */
    suspend fun build(db: AppDatabase, exerciseId: Long): ExerciseProgressionData {
        val snapshot = ReplaySnapshot.loadStaticFromDb(db)
        val muscle = snapshot.exerciseMuscle[exerciseId]
            ?: return ExerciseProgressionData(ExerciseProgressionSeries.empty(), emptyList())
        val muscleIds = snapshot.muscleExerciseIds[muscle]
            ?: return ExerciseProgressionData(ExerciseProgressionSeries.empty(), emptyList())
        if (exerciseId !in muscleIds) {
            return ExerciseProgressionData(ExerciseProgressionSeries.empty(), emptyList())
        }
        val namesById = db.exerciseDao().getAll().associate { it.id to it.name }
        val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
        return buildCore(
            exerciseId = exerciseId,
            snapshot = snapshot,
            muscle = muscle,
            muscleIds = muscleIds,
            namesById = namesById,
            weightUnit = weightUnit,
            initialOverrides = db.exerciseStrengthOverrideDao().getInitials(),
            sessionOverrides = db.exerciseStrengthOverrideDao().getNonInitials()
                .groupBy { it.sessionId!! },
            sessions = db.workoutSessionDao().getAll(),
            setsForSession = { db.workoutSetDao().getSetsForSession(it) },
            now = System.currentTimeMillis(),
        )
    }

    /**
     * DB-free core: replays the muscle, sampling each frame from the PRE-FOLD state (the decision
     * entering that session) with its per-decision trace, then appends one synthetic PREDICTED frame
     * at [now] from the live post-final-fold state. Mirrors the run/runCore split in [ReplayEngine].
     */
    internal suspend fun buildCore(
        exerciseId: Long,
        snapshot: ReplaySnapshot,
        muscle: MuscleGroup,
        muscleIds: List<Long>,
        namesById: Map<Long, String>,
        weightUnit: WeightUnit,
        initialOverrides: List<ExerciseStrengthOverride>,
        sessionOverrides: Map<Long, List<ExerciseStrengthOverride>>,
        sessions: List<WorkoutSession>,
        setsForSession: suspend (Long) -> List<WorkoutSet>,
        now: Long,
    ): ExerciseProgressionData {
        val ownEstimate = mutableListOf<ProgressionPoint>()
        val siblingsEstimate = mutableListOf<ProgressionPoint>()
        val merged = mutableListOf<ProgressionPoint>()
        val bandUpper = mutableListOf<ProgressionPoint>()
        val bandLower = mutableListOf<ProgressionPoint>()
        val ownObservations = mutableListOf<ProgressionPoint>()
        val siblingObservations = mutableListOf<ProgressionPoint>()
        val frames = mutableListOf<ProgressionFrame>()

        // Pre-fold beliefs for the CURRENT session, captured by the beforeSession hook right before
        // the fold mutates them. Copied because the fold mutates snapshot.currentBeliefs in place.
        var preFold: Map<Long, Belief> = emptyMap()
        // Completed sets from sessions strictly before the current one (the facts a decision saw).
        val priorSets = mutableListOf<WorkoutSet>()

        fun preFoldSnapshot(beliefs: Map<Long, Belief>): ReplaySnapshot =
            ReplaySnapshot(snapshot.exerciseMuscle, snapshot.seedCoefficients)
                .also { it.currentBeliefs.putAll(beliefs) }

        fun targetReps(sets: List<WorkoutSet>): Int =
            sets.filter { it.exerciseId == exerciseId }.minByOrNull { it.setNumber }?.targetReps ?: 10

        engine.runCore(
            snapshot = snapshot,
            initialOverrides = initialOverrides,
            sessionOverrides = sessionOverrides,
            sessions = sessions,
            setsForSession = setsForSession,
            beforeSession = { beliefs, _ -> preFold = HashMap(beliefs) },
            observer = { _, asOf, sets, snap, beliefResult ->
                if (beliefResult.steps.any { it.muscle == muscle }) {
                    val preSnap = preFoldSnapshot(preFold)
                    val sample = sampleSession(exerciseId, muscleIds, preSnap, sets, asOf, config)
                    ownEstimate += sample.ownEstimate
                    siblingsEstimate += sample.siblingsEstimate
                    merged += sample.merged
                    bandUpper += sample.bandUpper
                    bandLower += sample.bandLower
                    ownObservations += sample.ownObservations
                    siblingObservations += sample.siblingObservations
                    val trace = buildSessionTrace(
                        targetId = exerciseId, muscle = muscle, beliefs = preFold,
                        seedCoef = snap.seedCoefficients, muscleExerciseIds = muscleIds,
                        exerciseMuscle = snap.exerciseMuscle, priorSets = priorSets,
                        sessionReps = targetReps(sets), now = asOf, weightUnit = weightUnit,
                        config = config, engine = progressionEngine,
                    )
                    frames += buildFrame(exerciseId, muscleIds, preSnap, sets, asOf, namesById, config, sample, trace)
                }
                priorSets += sets.filter { it.completedAt != null }
            },
        )

        // Synthetic PREDICTED frame: the live forward-looking decision at `now`, from the
        // post-final-fold state (snapshot.currentBeliefs after the last fold).
        val predictedFrame: ProgressionFrame? = if (frames.isNotEmpty()) {
            val liveSample = sampleSession(exerciseId, muscleIds, snapshot, emptyList(), now, config)
            ownEstimate += liveSample.ownEstimate
            siblingsEstimate += liveSample.siblingsEstimate
            merged += liveSample.merged
            bandUpper += liveSample.bandUpper
            bandLower += liveSample.bandLower
            val liveReps = priorSets.filter { it.exerciseId == exerciseId }
                .maxByOrNull { it.completedAt ?: 0L }?.targetReps ?: 10
            val liveTrace = buildSessionTrace(
                targetId = exerciseId, muscle = muscle, beliefs = snapshot.currentBeliefs,
                seedCoef = snapshot.seedCoefficients, muscleExerciseIds = muscleIds,
                exerciseMuscle = snapshot.exerciseMuscle, priorSets = priorSets,
                sessionReps = liveReps, now = now, weightUnit = weightUnit,
                config = config, engine = progressionEngine,
            )
            buildFrame(exerciseId, muscleIds, snapshot, emptyList(), now, namesById, config, liveSample, liveTrace)
        } else null

        return ExerciseProgressionData(
            series = ExerciseProgressionSeries(
                ownEstimate = ownEstimate,
                siblingsEstimate = siblingsEstimate,
                merged = merged,
                bandUpper = bandUpper,
                bandLower = bandLower,
                ownObservations = ownObservations,
                siblingObservations = siblingObservations,
            ),
            frames = frames,
            predictedFrame = predictedFrame,
        )
    }
}
```

Notes:
- `buildCore` drives `engine.runCore` directly (DB-free) — it never calls `engine.run` (which needs a DB). This is why Task 3 only needed the `run` pass-through for other callers, not here.
- The synthetic frame samples lines from `snapshot.currentBeliefs`, which after `runCore` completes holds the live post-final-fold beliefs.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionSeriesBuilderCoreTest"`
Expected: PASS.

- [ ] **Step 5: Run the whole progression test package to confirm no regressions**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.*"`
Expected: PASS (existing `sampleSession`/`buildFrame` tests are state-agnostic and unaffected).

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat(progression): pre-fold frames + per-session traces + synthetic predicted frame

buildCore replays the muscle sampling each frame from the pre-fold state (the
decision entering that session) with its trace, then appends a synthetic
predicted frame at now from the live state. build() is the DB adapter.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
jj new
```

---

### Task 5: ViewModel — carry the trace on `FrameView`, default to the predicted point, drop the standalone trace fetch

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModelTest.kt`

**Interfaces:**
- Produces: `FrameView(..., val trace: PrescriptionTrace?)`; `buildFrameViews(frames, predictedFrame, unit, zone): Pair<Map<Long, FrameView>, Long?>` where the default epoch day is the predicted frame's day; `ExerciseCoefficientDetailState` loses `trace`, gains a `predictedSeries`-ready value via `state.predictedPoint` (below).
- Consumes: `ExerciseProgressionData.predictedFrame` (Task 4).

- [ ] **Step 1: Update the ViewModel test**

Replace the two existing tests' expectations and add trace coverage. New `ExerciseCoefficientDetailViewModelTest.kt` body for the frame test:

```kotlin
@Test fun buildFrameViewsKeysByEpochDayAndDefaultsToPredicted() {
    val zone = ZoneId.of("UTC")
    val dayMs = 86_400_000L
    val trace10 = io.github.fowles.stochastic_strength.domain.belief.PrescriptionTrace(emptyList(), 10f)
    val trace20 = io.github.fowles.stochastic_strength.domain.belief.PrescriptionTrace(emptyList(), 20f)
    val tracePredicted = io.github.fowles.stochastic_strength.domain.belief.PrescriptionTrace(emptyList(), 30f)
    val frames = listOf(
        ProgressionFrame(timestampMs = dayMs * 10, own = 100f, siblings = 90f, merged = 95f, crossTuning = emptyList(), observations = emptyList(), trace = trace10),
        ProgressionFrame(timestampMs = dayMs * 20, own = 110f, siblings = 92f, merged = 99f, crossTuning = emptyList(), observations = emptyList(), trace = trace20),
    )
    val predicted = ProgressionFrame(timestampMs = dayMs * 30, own = 120f, siblings = 95f, merged = 105f, crossTuning = emptyList(), observations = emptyList(), trace = tracePredicted)
    val (map, default) = buildFrameViews(frames, predicted, WeightUnit.KG, zone)
    assertEquals(3, map.size)
    assertEquals(30L, default)            // predicted frame's epoch-day is the default
    assertEquals("110.0 kg", map.getValue(20L).headerOwn)
    assertEquals(30f, map.getValue(30L).trace!!.finalWeightKg, 0f)
    assertEquals(10f, map.getValue(10L).trace!!.finalWeightKg, 0f)
}
```

(Keep `tooltipStacksNameThenSetsTargetFirst` unchanged.)

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.debug.ExerciseCoefficientDetailViewModelTest"`
Expected: FAIL — `FrameView` has no `trace`; `buildFrameViews` arity wrong.

- [ ] **Step 3: Update the ViewModel**

In `ExerciseCoefficientDetailViewModel.kt`:

Add `trace` to `FrameView`:

```kotlin
data class FrameView(
    val timestampMs: Long,
    val headerOwn: String,
    val headerSiblings: String,
    val headerMerged: String,
    val crossTuning: List<CrossTuningRow>,
    val tooltip: CharSequence,
    val trace: PrescriptionTrace?,
)
```

Change `buildFrameViews` to accept the predicted frame and fold it into the map, defaulting to it:

```kotlin
internal fun buildFrameViews(
    frames: List<ProgressionFrame>,
    predictedFrame: ProgressionFrame?,
    unit: WeightUnit,
    zone: ZoneId,
): Pair<Map<Long, FrameView>, Long?> {
    val all = frames + listOfNotNull(predictedFrame)
    if (all.isEmpty()) return emptyMap<Long, FrameView>() to null
    val byEpochDay = LinkedHashMap<Long, FrameView>()
    for (f in all) {
        val epochDay = timestampToLocalEpochDay(f.timestampMs, zone)
        byEpochDay[epochDay] = FrameView(   // later same-day frame overwrites; predicted (last) wins its day
            timestampMs = f.timestampMs,
            headerOwn = headerValue(f.own, unit),
            headerSiblings = headerValue(f.siblings, unit),
            headerMerged = headerValue(f.merged, unit),
            crossTuning = f.crossTuning,
            tooltip = formatTooltip(f.observations, unit),
            trace = f.trace,
        )
    }
    val defaultFrame = predictedFrame ?: frames.maxBy { it.timestampMs }
    val defaultEpochDay = timestampToLocalEpochDay(defaultFrame.timestampMs, zone)
    return byEpochDay to defaultEpochDay
}
```

Remove `trace` from `ExerciseCoefficientDetailState` and add the predicted point coordinates for the chart marker:

```kotlin
data class ExerciseCoefficientDetailState(
    val loading: Boolean = true,
    val exercise: Exercise? = null,
    val progressionSeries: List<ProgressionChartSeries> = emptyList(),
    val framesByEpochDay: Map<Long, FrameView> = emptyMap(),
    val defaultEpochDay: Long? = null,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val chartYRange: ClosedFloatingPointRange<Double>? = null,
)
```

In `init`, remove the standalone trace fetch and thread the predicted frame. Replace the concurrent-load block:

```kotlin
            val data = repository.getExerciseProgressionData(exerciseId)
            val series = data.series
            val (framesByEpochDay, defaultEpochDay) =
                buildFrameViews(data.frames, data.predictedFrame, weightUnit, ZoneId.systemDefault())
            fun pts(list: List<io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint>) =
                list.map { DebugChartPoint(it.timestampMs, it.value) }
            val predictedPoint = data.predictedFrame?.let { pf ->
                (pf.merged ?: pf.own)?.let { listOf(DebugChartPoint(pf.timestampMs, it)) }
            }.orEmpty()
            val progressionSeries = listOf(
                ProgressionChartSeries("Own estimate", pts(series.ownEstimate), ProgressionSeriesStyle.LINE, ProgressionColorRole.OWN),
                ProgressionChartSeries("Siblings", pts(series.siblingsEstimate), ProgressionSeriesStyle.LINE, ProgressionColorRole.SIBLINGS),
                ProgressionChartSeries("Merged", pts(series.merged), ProgressionSeriesStyle.LINE, ProgressionColorRole.MERGED),
                ProgressionChartSeries("+σ", pts(series.bandUpper), ProgressionSeriesStyle.LINE, ProgressionColorRole.BAND),
                ProgressionChartSeries("−σ", pts(series.bandLower), ProgressionSeriesStyle.LINE, ProgressionColorRole.BAND),
                ProgressionChartSeries("Sessions", pts(series.ownObservations), ProgressionSeriesStyle.FILLED_DOTS, ProgressionColorRole.OWN_OBS),
                ProgressionChartSeries("Siblings (scaled)", pts(series.siblingObservations), ProgressionSeriesStyle.HOLLOW_DOTS, ProgressionColorRole.SIBLING_OBS),
                ProgressionChartSeries("Predicted today", predictedPoint, ProgressionSeriesStyle.PREDICTED_DOT, ProgressionColorRole.PREDICTED),
            )

            _state.value = ExerciseCoefficientDetailState(
                loading = false,
                exercise = exercise,
                progressionSeries = progressionSeries,
                framesByEpochDay = framesByEpochDay,
                defaultEpochDay = defaultEpochDay,
                weightUnit = weightUnit,
                chartYRange = sharedProgressionYRange(data),
            )
```

Remove the now-unused imports: `kotlinx.coroutines.async` and the `PrescriptionTrace` import stays (used by `FrameView`). Remove `SessionExerciseObservation`/`ObservedSet` only if they become unused (they are used by `formatTooltip`/`formatObservedSet` — keep). `ProgressionSeriesStyle.PREDICTED_DOT` and `ProgressionColorRole.PREDICTED` are added in Task 6; this file references them, so Task 6 must land with (or before) compiling this. Build order: implement Task 6's enum additions first if compiling in isolation — see Task 6 note.

- [ ] **Step 4: Run the test to verify it passes (after Task 6 enum additions exist)**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.debug.ExerciseCoefficientDetailViewModelTest"`
Expected: PASS. (If the enum members are not yet present, this task will not compile; land Task 6's enum additions first — they are trivial and dependency-free.)

- [ ] **Step 5: Commit**

```bash
jj describe -m "feat(debug): FrameView carries its trace; default selection is the predicted point

Drops the standalone getPrescriptionTrace fetch; buildFrameViews folds in the
synthetic predicted frame and defaults to it.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
jj new
```

---

### Task 6: Screen — render the selected trace; add the distinct "Predicted today" marker style

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailScreen.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/ExerciseProgressionChart.kt`

**Interfaces:**
- Produces: `ProgressionSeriesStyle.PREDICTED_DOT`; `ProgressionColorRole.PREDICTED`. The "Why this weight" section renders `crossTuningFrame.trace`.

Build-order note: this task's enum additions (Step 1) are dependency-free — implement them first so Task 5 compiles. The rest of this task (screen wiring) can follow.

- [ ] **Step 1: Add the enum members and their rendering/color**

In `ExerciseProgressionChart.kt`:

```kotlin
enum class ProgressionSeriesStyle { LINE, FILLED_DOTS, HOLLOW_DOTS, PREDICTED_DOT }
enum class ProgressionColorRole { OWN, SIBLINGS, MERGED, OWN_OBS, SIBLING_OBS, BAND, PREDICTED }
```

In `progressionColors()` add the mapping (tertiary reads distinctly on most dynamic-color schemes; verify on-device per Step 5):

```kotlin
ProgressionColorRole.PREDICTED to MaterialTheme.colorScheme.tertiary,
```

In the `when (s.style)` block that builds `lines`, add a branch. `PREDICTED_DOT` is a larger filled diamond-ish pill so the single "today" point stands out from the session dots:

```kotlin
ProgressionSeriesStyle.PREDICTED_DOT -> LineCartesianLayer.rememberLine(
    fill = transparent,
    pointProvider = LineCartesianLayer.PointProvider.single(
        LineCartesianLayer.point(
            rememberShapeComponent(
                fill = fill(Color.Transparent),
                shape = CorneredShape.Pill,
                strokeFill = fill(color),
                strokeThickness = 2.5.dp,
            ),
            size = 12.dp,
        ),
    ),
)
```

- [ ] **Step 2: Render the selected trace in the screen**

In `ExerciseCoefficientDetailScreen.kt`, update the comment + the "Why this weight" item to read the frame's trace instead of `state.trace`. Replace the block:

```kotlin
            item { SectionHeader("Why this weight", verticalPadding = 4.dp) }

            item {
                val trace = crossTuningFrame?.trace
                if (trace == null) {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        Text("No effective belief yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    PrescriptionTraceSection(trace, state.weightUnit)
                }
            }
```

Update the selection comment above `selectedEpochDay` to reflect that all three sections now follow the selection and default to the synthetic predicted point:

```kotlin
        // One selection drives all three sections. Until the user taps, selectedEpochDay is null and
        // everything shows the synthetic "predicted today" point (state.defaultEpochDay); tapping a
        // session dot time-travels the trace, cross-tuning, and headers to that session's PRE-FOLD
        // decision state. Selection persists across recomposition.
        var selectedEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
        val crossTuningFrame = (selectedEpochDay ?: state.defaultEpochDay)
            ?.let { state.framesByEpochDay[it] }
```

The `PrescriptionTrace` import already exists in the screen. Remove it only if it becomes unused — it is still referenced by `PrescriptionTraceSection`'s parameter, so keep it.

- [ ] **Step 3: Build the app to confirm everything compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the debug UI + progression unit tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.debug.*" --tests "io.github.fowles.stochastic_strength.domain.progression.*"`
Expected: PASS.

- [ ] **Step 5: On-device visual verification (deferred if no device)**

Install and open a seeded exercise's debug detail (Debug → exercise → coefficient detail). Confirm:
- The "Predicted today" marker is visibly distinct from session/sibling dots and sits at today's x on the merged line.
- On open, "Why this weight", Cross-tuning header, and bars all describe the predicted-today point.
- Tapping a historical session dot moves all three sections together to that session; the trace's numbers change and read as the weight that produced that session.
Note in the commit if visual verification was deferred (no device).

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat(debug): why-this-weight follows selection; distinct predicted-today marker

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
jj new
```

---

### Task 7: Remove the now-dead `WorkoutRepository.getPrescriptionTrace`

Its only caller (the ViewModel) no longer uses it.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

**Interfaces:**
- Removes: `WorkoutRepository.getPrescriptionTrace`.

- [ ] **Step 1: Confirm there are no remaining callers**

Run: `grep -rn "getPrescriptionTrace" app/src`
Expected: no matches (Task 5 removed the ViewModel call).

- [ ] **Step 2: Delete the method and clean imports**

In `WorkoutRepository.kt`, delete the entire `getPrescriptionTrace(...)` function (the doc comment through its closing brace, currently around lines 418–457). Then remove imports left unused by the deletion — check and remove any of these that no longer appear elsewhere in the file:

```kotlin
import io.github.fowles.stochastic_strength.domain.belief.PrescriptionTraceBuilder
```

(Verify with `grep -n "PrescriptionTraceBuilder\|PrescriptionTrace\b" app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` before removing — `PolicyFacts`, `WorkoutSet`, `WeightUnit` etc. are used by `prescriptionContext`/other methods and MUST stay.)

- [ ] **Step 3: Build to confirm compilation**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
jj describe -m "refactor(repo): remove dead getPrescriptionTrace (live trace is the predicted frame)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
jj new
```

---

### Task 8: Full regression pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full JVM unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, including the backtest gates `BeliefScoreTest` and `BeliefPolicyBacktestTest` (they must be byte-for-byte unchanged — this is a display-only change).

- [ ] **Step 2: Run the instrumented suite (device attached)**

Run: `./gradlew :app:connectedAndroidTest`
Expected: PASS. (If no device/emulator is available, note it as deferred.)

- [ ] **Step 3: Final commit / describe if any residue**

If the working copy has uncommitted changes:

```bash
jj describe -m "test: full regression pass for backward-looking why-this-weight

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

Otherwise nothing to do — leave the branch for the user to reshape + push.

---

## Self-Review Notes (author)

- **Spec coverage:** pre-fold reframe (Task 4 `buildCore` sampling from `preFold`), trace-follows-selection (Tasks 1/5/6), synthetic today point + default (Tasks 4/5/6), one selection variable (Task 6), `getPrescriptionTrace` removed (Task 7), re-baseline display tests (Tasks 1/5 updated in place; the pure `sampleSession`/`buildFrame` tests are state-agnostic and need no re-baseline — the spec's "re-baseline" is satisfied by updating the frame/ViewModel constructor tests), backtest gate untouched (Task 8 asserts). Y-range coupling to the user-facing chart: `sharedProgressionYRange` reads `data.series`, which now includes the synthetic point — inclusive and safe.
- **Type consistency:** `ProgressionFrame.trace`, `buildFrame(..., trace)`, `buildSessionTrace(...)`, `ExerciseProgressionData.predictedFrame`, `buildCore(...)`, `FrameView.trace`, `buildFrameViews(frames, predictedFrame, unit, zone)`, `ProgressionSeriesStyle.PREDICTED_DOT`, `ProgressionColorRole.PREDICTED` are used consistently across tasks.
- **Build-order dependency:** Task 5 references the Task 6 enum members; Task 6 Step 1 (enum additions) is called out as dependency-free and to be landed first when compiling in isolation. Under subagent-driven execution, run Task 6 Step 1 together with Task 5, or execute Task 6 before Task 5's compile step.
