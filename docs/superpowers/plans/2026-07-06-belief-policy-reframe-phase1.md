# Belief+Policy Reframe — Phase 1: Prescription Policy Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a pure `PrescriptionPolicy` layer (failure ceiling, HURT-as-decaying-multiplier, sore-muscle cooldown, neutral z/δ knobs) between the projector and the planner, move HURT out of the estimator, and build the real-history backtest harness with a frozen pre-change baseline.

**Architecture:** Phase 1 of the 4-phase reframe in `docs/superpowers/specs/2026-07-06-belief-policy-reframe-design.md`. The estimator itself stays untouched except for deleting the HURT fold. All new policy state (ceilings, hurt events, muscle stress) is derived during replay into the in-memory `DerivedStateStore` — zero Room migrations. A DB-free replay core is extracted first so both the backtest harness (now) and the phase-4 fitter (later) can replay preloaded history.

**Tech Stack:** Kotlin, Android single-module app (`app/`), Room (untouched), JUnit4 JVM unit tests, jj for version control.

## Global Constraints

- Domain code stays pure JVM Kotlin (no Android imports in `domain/` beyond existing `data.model` entities).
- Zero Room migrations: schema stays v17; no new persisted tables or columns.
- Version control is jj. Commit at every task checkpoint. Every commit message ends with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Test commands: single class `./gradlew :app:testDebugUnitTest --tests "<fqcn>"`; full suite `./gradlew :app:testDebugUnitTest`; lint `./gradlew :app:lint`.
- Intended phase-1 behavior deltas are ONLY: (a) HURT heals over time instead of permanently denting estimates, (b) failure ceilings cap prescriptions, (c) the sore-muscle rule reads replay-derived state instead of a live query. Everything else must be behavior-identical.
- `org.json:json` may be added ONLY as `testImplementation` (production code uses Android's built-in org.json).
- NEVER commit files under `app/src/test/resources/backtest/` (personal training data; the directory is gitignored in Task 2).
- Replay stays deterministic and idempotent: policy state must be a pure function of the replayed history.
- `EstimatorConfig` (in `domain/progression/ExerciseEstimate.kt`) is the sole tuning surface; all new constants go there.
- `WorkoutSet`, `WorkoutSession`, `ExerciseStrengthOverride` entity constructors: always pass named args; nullable fields (`endTime`, `actualReps`, `feedback`, `completedAt`, `stravaActivityId`) exist on all of them.

---

### Task 1: DB-free replay core (`ReplayHistory`)

Pure refactor, no behavior change. Extracts data loading from `ReplayEngine.run(db, ...)` so history can be replayed from preloaded lists (backtest now, fitter in phase 4).

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ReplayHistory.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ReplayEngine.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSetDao.kt` (add unfiltered bulk query — the existing `getSetsForSessions` filters `completedAt IS NOT NULL`, which replay must not)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ReplayHistoryTest.kt`

**Interfaces:**
- Consumes: existing `ReplaySnapshot`, `SessionProgressionStepper`, `ExerciseEstimate`.
- Produces: `ReplayHistory(sessions: List<WorkoutSession>, setsBySession: Map<Long, List<WorkoutSet>>, initialOverrides: List<ExerciseStrengthOverride>, sessionOverrides: Map<Long, List<ExerciseStrengthOverride>>)` with `companion object { suspend fun loadFromDb(db: AppDatabase): ReplayHistory }`; `ReplayEngine.run(history: ReplayHistory, snapshot: ReplaySnapshot, observer: SessionObserver)` (non-suspend) plus the existing `suspend fun run(db, snapshot, observer)` kept as a thin wrapper.

- [ ] **Step 1: Write the failing test**

Create `ReplayHistoryTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class ReplayHistoryTest {

    private fun set(sessionId: Long, exerciseId: Long) = WorkoutSet(
        sessionId = sessionId, exerciseId = exerciseId, setNumber = 1,
        targetWeight = 100f, targetReps = 5, actualReps = 5, feedback = SetFeedback.RIR_2_4,
    )

    @Test
    fun replaysInEndTimeOrderAppliesOverridesAndSkipsEmptySessions() {
        val snapshot = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(1L to 1.0f),
        )
        // Sessions deliberately out of order; session 3 has an override but no sets.
        val history = ReplayHistory(
            sessions = listOf(
                WorkoutSession(id = 2, locationId = null, startTime = 0L, endTime = 2_000L, stravaActivityId = null),
                WorkoutSession(id = 1, locationId = null, startTime = 0L, endTime = 1_000L, stravaActivityId = null),
                WorkoutSession(id = 3, locationId = null, startTime = 0L, endTime = 3_000L, stravaActivityId = null),
            ),
            setsBySession = mapOf(1L to listOf(set(1, 1)), 2L to listOf(set(2, 1))),
            initialOverrides = listOf(
                ExerciseStrengthOverride(sessionId = null, exerciseId = 1L, e1rm = 100f, asOf = 0L),
            ),
            sessionOverrides = mapOf(
                3L to listOf(ExerciseStrengthOverride(sessionId = 3L, exerciseId = 1L, e1rm = 75f, asOf = 2_500L)),
            ),
        )

        val observed = mutableListOf<Long>()
        ReplayEngine().run(history, snapshot) { sessionId, _, _, _, _ -> observed.add(sessionId) }

        // Sessions with sets ran in endTime order; the empty session 3 was skipped by the observer.
        assertEquals(listOf(1L, 2L), observed)
        // But session 3's override was still applied to the estimate map.
        assertEquals(ln(75f), snapshot.currentEstimates.getValue(1L).lnE, 1e-4f)
        assertEquals(1.0f, snapshot.currentEstimates.getValue(1L).confidence, 1e-4f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ReplayHistoryTest"`
Expected: compilation FAILURE — `ReplayHistory` unresolved.

- [ ] **Step 3: Create `ReplayHistory.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/**
 * Preloaded replay inputs: completed sessions plus their sets and strength-override rows.
 * Lets [ReplayEngine] run without a database (backtests, and the phase-4 fitter's inner loop).
 * [sessions] may be unsorted and may contain incomplete sessions; the engine filters and sorts.
 */
data class ReplayHistory(
    val sessions: List<WorkoutSession>,
    val setsBySession: Map<Long, List<WorkoutSet>>,
    val initialOverrides: List<ExerciseStrengthOverride>,
    val sessionOverrides: Map<Long, List<ExerciseStrengthOverride>>,
) {
    companion object {
        suspend fun loadFromDb(db: AppDatabase): ReplayHistory {
            val sessions = db.workoutSessionDao().getAll().filter { it.endTime != null }
            // getAllSetsForSessions, NOT getSetsForSessions: the latter filters completedAt IS NOT NULL,
            // which would silently drop timestamp-less sets from replay (behavior change).
            val sets = if (sessions.isEmpty()) emptyMap()
            else db.workoutSetDao().getAllSetsForSessions(sessions.map { it.id }).groupBy { it.sessionId }
            return ReplayHistory(
                sessions = sessions,
                setsBySession = sets,
                initialOverrides = db.exerciseStrengthOverrideDao().getInitials(),
                sessionOverrides = db.exerciseStrengthOverrideDao().getNonInitials().groupBy { it.sessionId!! },
            )
        }
    }
}
```

- [ ] **Step 4: Reshape `ReplayEngine.kt`**

Replace the body of the class (keep the file header comment and `SessionObserver` as they are):

```kotlin
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

    suspend fun run(db: AppDatabase, snapshot: ReplaySnapshot, observer: SessionObserver) =
        run(ReplayHistory.loadFromDb(db), snapshot, observer)

    fun run(history: ReplayHistory, snapshot: ReplaySnapshot, observer: SessionObserver) {
        for (init in history.initialOverrides) {
            snapshot.currentEstimates[init.exerciseId] = ExerciseEstimate.seed(init.e1rm, at = init.asOf)
        }
        val ordered = history.sessions.filter { it.endTime != null }
            .sortedWith(compareBy({ it.endTime!! }, { it.id }))
        for (session in ordered) {
            history.sessionOverrides[session.id]?.forEach { o ->
                snapshot.currentEstimates[o.exerciseId] = ExerciseEstimate(
                    lnE = ln(o.e1rm),
                    confidence = 1.0f,
                    updatedAt = o.asOf,
                )
            }
            val sets = history.setsBySession[session.id].orEmpty()
            if (sets.isEmpty()) continue
            val result = stepper.step(sets, snapshot, session.endTime!!)
            observer.onSession(session.id, session.endTime!!, sets, snapshot, result)
        }
    }
}
```

Keep the existing imports plus `ReplayHistory` is same-package; `kotlin.math.ln` import already present.

- [ ] **Step 5: Run the new test and the neighbors that exercise replay**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ReplayHistoryTest" --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionSeriesBuilderTest"`
Expected: PASS (series builder uses the `run(db, ...)` wrapper unchanged).

- [ ] **Step 6: Commit**

```bash
jj commit -m "refactor: extract DB-free ReplayHistory core from ReplayEngine

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Backup-JSON backtest harness + frozen baseline generator

Additive only — no production behavior change. After this task lands there is a **USER ACTION**: export the real history from the phone (History → ⋮ → Export) into `app/src/test/resources/backtest/history.json`, then run the generator once to freeze the baseline. Tasks 3–6 do not depend on this, but Task 7 does.

**Files:**
- Modify: `gradle/libs.versions.toml` (add org.json library)
- Modify: `app/build.gradle.kts` (add `testImplementation(libs.json)`)
- Modify: `.gitignore` (repo root — add `app/src/test/resources/backtest/`)
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestHarness.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestBaselineGeneratorTest.kt`

**Interfaces:**
- Consumes: `BackupJsonParser.parse(json: String): WorkoutBackup` (domain/backup), `ReplayHistory`, `ReplayEngine.run(history, snapshot, observer)` from Task 1, `MuscleStrengthProjector.project`, `DefaultProgressionEngine.fromOneRepMax`, `WeightFormatter.round`.
- Produces (test-sources only): `BacktestHarness.load(): BacktestData?`, `BacktestHarness.replayProjectorPrescriptions(data): List<Row>`, `BacktestHarness.writeBaseline(rows)`, `BacktestHarness.readBaseline(): List<Row>?`, `data class Row(sessionId: Long, exerciseId: Long, weightKg: Float)`, `BacktestData.newSnapshot(): ReplaySnapshot`, `const REFERENCE_REPS = 10`. Task 7 adds `replayPolicyPrescriptions` beside these.

- [ ] **Step 1: Add the JVM org.json dependency**

In `gradle/libs.versions.toml` under `[libraries]` (after the `junit` line):

```toml
json = { group = "org.json", name = "json", version = "20240303" }
```

In `app/build.gradle.kts`, next to `testImplementation(libs.junit)`:

```kotlin
testImplementation(libs.json)
```

In the repo-root `.gitignore`, append:

```
# Personal training data used by the local backtest — never commit.
app/src/test/resources/backtest/
```

- [ ] **Step 2: Write the harness**

Create `BacktestHarness.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.backup.BackupJsonParser
import io.github.fowles.stochastic_strength.domain.backup.WorkoutBackup
import io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjector
import io.github.fowles.stochastic_strength.domain.progression.ReplayEngine
import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Replays a real exported backup (app/src/test/resources/backtest/history.json — gitignored,
 * personal data) and computes per-session prescriptions at a fixed reference rep count.
 * baseline_prescriptions.json freezes the pre-reframe prescriptions; later phases compare
 * against it. Both files live only on the developer machine.
 */
object BacktestHarness {
    const val REFERENCE_REPS = 10

    private val dir = File("src/test/resources/backtest")
    fun historyFile(): File = File(dir, "history.json")
    fun baselineFile(): File = File(dir, "baseline_prescriptions.json")

    data class Row(val sessionId: Long, val exerciseId: Long, val weightKg: Float)

    class BacktestData(val backup: WorkoutBackup, val weightUnit: WeightUnit, val history: ReplayHistory) {
        fun newSnapshot(): ReplaySnapshot = ReplaySnapshot(
            exerciseMuscle = backup.exercises.associate { it.id to it.primaryMuscle },
            seedCoefficients = backup.exercises.associate { it.id to (ExerciseCoefficients.get(it) ?: 0f) },
        )
    }

    fun load(): BacktestData? {
        val f = historyFile()
        if (!f.exists()) return null
        val backup = BackupJsonParser.parse(f.readText())
        val history = ReplayHistory(
            sessions = backup.workoutSessions.filter { it.endTime != null },
            setsBySession = backup.workoutSets.groupBy { it.sessionId }
                .mapValues { (_, s) -> s.sortedWith(compareBy({ it.setNumber }, { it.id })) },
            initialOverrides = backup.exerciseStrengthOverrides.filter { it.sessionId == null },
            sessionOverrides = backup.exerciseStrengthOverrides.filter { it.sessionId != null }
                .groupBy { it.sessionId!! },
        )
        val unit = backup.userProfile.firstOrNull()?.weightUnit ?: WeightUnit.KG
        return BacktestData(backup, unit, history)
    }

    /** Prescriptions right after each session via the raw projector path (pre-policy semantics). */
    fun replayProjectorPrescriptions(data: BacktestData): List<Row> {
        val snapshot = data.newSnapshot()
        val projector = MuscleStrengthProjector()
        val rows = mutableListOf<Row>()
        ReplayEngine().run(data.history, snapshot) { sessionId, asOf, _, snap, _ ->
            for ((_, ids) in snap.muscleExerciseIds) {
                val proj = projector.project(snap.currentEstimates, snap.seedCoefficients, ids, asOf)
                for (id in ids.sorted()) {
                    val e1rm = proj.effectiveE1rm[id] ?: continue
                    val w = WeightFormatter.round(
                        DefaultProgressionEngine.fromOneRepMax(e1rm, REFERENCE_REPS), data.weightUnit,
                    )
                    rows += Row(sessionId, id, w)
                }
            }
        }
        return rows
    }

    fun writeBaseline(rows: List<Row>) {
        val arr = JSONArray()
        for (r in rows) {
            arr.put(JSONObject().put("s", r.sessionId).put("e", r.exerciseId).put("w", r.weightKg.toDouble()))
        }
        baselineFile().writeText(JSONObject().put("referenceReps", REFERENCE_REPS).put("rows", arr).toString(2))
    }

    fun readBaseline(): List<Row>? {
        val f = baselineFile()
        if (!f.exists()) return null
        val root = JSONObject(f.readText())
        val arr = root.getJSONArray("rows")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Row(o.getLong("s"), o.getLong("e"), o.getDouble("w").toFloat())
        }
    }
}
```

- [ ] **Step 3: Write the generator test**

Create `BacktestBaselineGeneratorTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One-shot baseline freezer. Skipped unless history.json is present locally AND no baseline
 * exists yet. Deleting baseline_prescriptions.json re-arms it — only do that BEFORE phase-1
 * behavior changes land, or from a jj commit at the pre-phase-1 baseline.
 */
class BacktestBaselineGeneratorTest {
    @Test
    fun freezeBaselineFromCurrentMain() {
        val data = BacktestHarness.load()
        assumeTrue("no local backtest history; skipping", data != null)
        assumeTrue("baseline already frozen; delete manually to regenerate", !BacktestHarness.baselineFile().exists())
        val rows = BacktestHarness.replayProjectorPrescriptions(data!!)
        assertTrue("history produced no prescriptions", rows.isNotEmpty())
        BacktestHarness.writeBaseline(rows)
        println("Frozen ${rows.size} baseline prescriptions to ${BacktestHarness.baselineFile()}")
    }
}
```

- [ ] **Step 4: Run it (skips without the local file — that's the expected CI-safe behavior)**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BacktestBaselineGeneratorTest"`
Expected: PASS (skipped via assumption if `history.json` absent; writes the baseline if present).

- [ ] **Step 5: Commit**

```bash
jj commit -m "test: backtest harness over exported real history + baseline freezer

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 6: USER ACTION (blocking for Task 7 only)**

Ask the user to: export history from the phone (History → ⋮ → Export), copy it to `app/src/test/resources/backtest/history.json`, then re-run the Step-4 command to freeze `baseline_prescriptions.json`. Confirm the printed row count is nonzero.

---

### Task 3: `PolicyState` derived during replay

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/policy/PolicyState.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ReplaySnapshot.kt` (add `exerciseEquipment`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStore.kt` (hold PolicyState)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (collect during replay)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/policy/PolicyStateBuilderTest.kt`

**Interfaces:**
- Consumes: `ReplaySnapshot` (gains `exerciseEquipment: Map<Long, Equipment>` constructor param, default `emptyMap()`), `DefaultProgressionEngine.rawToOneRepMax(weight: Float, reps: Int)` (internal, same module).
- Produces:
  - `data class FailureCeiling(val exerciseId: Long, val ceilingE1rm: Float, val isClear: Boolean, val sessionEndTime: Long)`
  - `data class HurtEvent(val muscle: MuscleGroup, val at: Long)`
  - `data class MuscleStress(val tooHardTimes: List<Long>, val rir01TimesByExercise: Map<Long, List<Long>>)`
  - `data class PolicyState(val ceilings: Map<Long, FailureCeiling>, val hurtEvents: List<HurtEvent>, val muscleStress: Map<MuscleGroup, MuscleStress>)` with `companion object { val EMPTY }`
  - `class PolicyStateBuilder { fun onSession(asOf: Long, sets: List<WorkoutSet>, snapshot: ReplaySnapshot); fun build(): PolicyState }`
  - `DerivedStateStore.Snapshot.policyState(): PolicyState`; `MutableDerivedState.putPolicyState(state: PolicyState)`

- [ ] **Step 1: Write the failing test**

Create `PolicyStateBuilderTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyStateBuilderTest {

    private val DAY = 24L * 60 * 60 * 1000

    private fun snapshot() = ReplaySnapshot(
        exerciseMuscle = mapOf(1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST, 3L to MuscleGroup.CHEST),
        seedCoefficients = mapOf(1L to 1.0f, 2L to 0.6f, 3L to 0f), // 3 is unloadable
        exerciseEquipment = mapOf(1L to Equipment.BARBELL, 2L to Equipment.DUMBBELL, 3L to Equipment.BODYWEIGHT),
    )

    private fun set(
        exerciseId: Long, weight: Float, reps: Int, feedback: SetFeedback,
        actualReps: Int? = null, setNumber: Int = 1, completedAt: Long? = null,
    ) = WorkoutSet(
        sessionId = 1L, exerciseId = exerciseId, setNumber = setNumber, targetWeight = weight,
        targetReps = reps, actualReps = actualReps, feedback = feedback, completedAt = completedAt,
    )

    @Test
    fun failureCreatesClearCeilingAtRawTargetRep1rm() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = 6)), snapshot())
        val c = b.build().ceilings.getValue(1L)
        assertEquals(DefaultProgressionEngine.rawToOneRepMax(80f, 10), c.ceilingE1rm, 1e-3f)
        assertTrue("shortfall of 4 reps is a clear miss", c.isClear)
        assertEquals(1_000L, c.sessionEndTime)
    }

    @Test
    fun oneRepShortfallIsMarginalAndUncountedIsClear() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = 9)), snapshot())
        assertFalse("1-rep miss is marginal", b.build().ceilings.getValue(1L).isClear)

        val b2 = PolicyStateBuilder()
        b2.onSession(1_000L, listOf(set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = null)), snapshot())
        assertTrue("uncounted miss is clear", b2.build().ceilings.getValue(1L).isClear)
    }

    @Test
    fun ceilingIsMinOverFailedSetsAndSupersededByCleanSession() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(
            set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = 6, setNumber = 1),
            set(1L, 70f, 10, SetFeedback.TOO_HARD, actualReps = 8, setNumber = 2),
        ), snapshot())
        assertEquals(DefaultProgressionEngine.rawToOneRepMax(70f, 10), b.build().ceilings.getValue(1L).ceilingE1rm, 1e-3f)

        // A newer session on the same exercise without failures clears the ceiling.
        b.onSession(2_000L, listOf(set(1L, 70f, 10, SetFeedback.RIR_0_1, actualReps = 10)), snapshot())
        assertNull(b.build().ceilings[1L])
    }

    @Test
    fun unloadableExercisesGetNoCeiling() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(set(3L, 0f, 10, SetFeedback.TOO_HARD)), snapshot())
        assertTrue(b.build().ceilings.isEmpty())
    }

    @Test
    fun hurtEventsAreDedupedPerMusclePerSession() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(
            set(1L, 80f, 10, SetFeedback.HURT, setNumber = 1),
            set(2L, 30f, 10, SetFeedback.HURT, setNumber = 1),
        ), snapshot())
        assertEquals(listOf(HurtEvent(MuscleGroup.CHEST, 1_000L)), b.build().hurtEvents)
    }

    @Test
    fun muscleStressTracksTooHardAndPerExerciseRir01ButNotBodyweight() {
        val b = PolicyStateBuilder()
        b.onSession(1_000L, listOf(
            set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = 6, completedAt = 900L),
            set(2L, 30f, 10, SetFeedback.RIR_0_1, setNumber = 1, completedAt = 910L),
            set(2L, 30f, 10, SetFeedback.RIR_0_1, setNumber = 2, completedAt = 920L),
            set(3L, 0f, 10, SetFeedback.TOO_HARD, completedAt = 930L), // bodyweight: exempt
        ), snapshot())
        val s = b.build().muscleStress.getValue(MuscleGroup.CHEST)
        assertEquals(listOf(900L), s.tooHardTimes)
        assertEquals(listOf(910L, 920L), s.rir01TimesByExercise.getValue(2L))
    }

    @Test
    fun stressOlderThanSevenDaysIsPruned() {
        val b = PolicyStateBuilder()
        b.onSession(0L, listOf(set(1L, 80f, 10, SetFeedback.TOO_HARD, actualReps = 6, completedAt = 0L)), snapshot())
        b.onSession(8 * DAY, listOf(set(2L, 30f, 10, SetFeedback.RIR_0_1, completedAt = 8 * DAY)), snapshot())
        val s = b.build().muscleStress.getValue(MuscleGroup.CHEST)
        assertTrue("old TOO_HARD pruned", s.tooHardTimes.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PolicyStateBuilderTest"`
Expected: compilation FAILURE — package `domain.policy` unresolved.

- [ ] **Step 3: Add `exerciseEquipment` to `ReplaySnapshot`**

In `ReplaySnapshot.kt`, change the constructor and loader:

```kotlin
class ReplaySnapshot(
    val exerciseMuscle: Map<Long, MuscleGroup>,
    val seedCoefficients: Map<Long, Float>,
    val exerciseEquipment: Map<Long, Equipment> = emptyMap(),
) {
```

(add `import io.github.fowles.stochastic_strength.data.model.Equipment`), and in `loadStaticFromDb` construct with:

```kotlin
return ReplaySnapshot(
    exerciseMuscle = exerciseMuscle,
    seedCoefficients = seedCoefficients,
    exerciseEquipment = allExercises.associate { it.id to it.equipment },
)
```

- [ ] **Step 4: Create `PolicyState.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot

/**
 * Prescription-policy inputs derived from replayed history. Pure projections of the set log —
 * rebuilt on every replay, never persisted (spec §4/§7: zero schema changes).
 */
data class FailureCeiling(
    val exerciseId: Long,
    /** min over the session's failed sets of rawToOneRepMax(weight, targetReps). */
    val ceilingE1rm: Float,
    /** true when any failed set missed by ≥2 reps or had no rep count. */
    val isClear: Boolean,
    val sessionEndTime: Long,
)

data class HurtEvent(val muscle: MuscleGroup, val at: Long)

data class MuscleStress(
    val tooHardTimes: List<Long>,
    /** RIR_0_1 timestamps per exercise — the cooldown rule triggers on >1 within the window on ONE exercise. */
    val rir01TimesByExercise: Map<Long, List<Long>>,
)

data class PolicyState(
    val ceilings: Map<Long, FailureCeiling>,
    val hurtEvents: List<HurtEvent>,
    val muscleStress: Map<MuscleGroup, MuscleStress>,
) {
    companion object {
        val EMPTY = PolicyState(emptyMap(), emptyList(), emptyMap())
    }
}

/** Accumulates PolicyState across replayed sessions, in session order. */
class PolicyStateBuilder {
    private companion object {
        const val STRESS_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
        const val HURT_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    }

    private val ceilings = mutableMapOf<Long, FailureCeiling>()
    private val hurtEvents = mutableListOf<HurtEvent>()
    private val tooHard = mutableMapOf<MuscleGroup, MutableList<Long>>()
    private val rir01 = mutableMapOf<MuscleGroup, MutableMap<Long, MutableList<Long>>>()

    fun onSession(asOf: Long, sets: List<WorkoutSet>, snapshot: ReplaySnapshot) {
        // Failure ceilings: the most recent session containing an exercise defines (or clears) its ceiling.
        sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
            if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
            val failures = exSets.filter { it.feedback == SetFeedback.TOO_HARD }
            if (failures.isEmpty()) {
                ceilings.remove(id)
            } else {
                val ceiling = failures.minOf { DefaultProgressionEngine.rawToOneRepMax(it.targetWeight, it.targetReps) }
                val clear = failures.any { it.actualReps == null || it.targetReps - it.actualReps >= 2 }
                ceilings[id] = FailureCeiling(id, ceiling, clear, asOf)
            }
        }

        // Hurt events: one per muscle per session.
        sets.filter { it.feedback == SetFeedback.HURT }
            .mapNotNull { snapshot.exerciseMuscle[it.exerciseId] }
            .distinct()
            .forEach { hurtEvents += HurtEvent(it, asOf) }
        hurtEvents.removeAll { asOf - it.at > HURT_RETENTION_MS }

        // Sore-muscle stress (bodyweight exempt, matching the old planner rule).
        for (s in sets) {
            val muscle = snapshot.exerciseMuscle[s.exerciseId] ?: continue
            if (snapshot.exerciseEquipment[s.exerciseId] == Equipment.BODYWEIGHT) continue
            val at = s.completedAt ?: asOf
            when (s.feedback) {
                SetFeedback.TOO_HARD -> tooHard.getOrPut(muscle) { mutableListOf() }.add(at)
                SetFeedback.RIR_0_1 ->
                    rir01.getOrPut(muscle) { mutableMapOf() }.getOrPut(s.exerciseId) { mutableListOf() }.add(at)
                else -> {}
            }
        }
        val cutoff = asOf - STRESS_WINDOW_MS
        tooHard.values.forEach { it.removeAll { t -> t < cutoff } }
        rir01.values.forEach { m -> m.values.forEach { it.removeAll { t -> t < cutoff } } }
    }

    fun build(): PolicyState {
        val stress = (tooHard.keys + rir01.keys).associateWith { m ->
            MuscleStress(
                tooHardTimes = tooHard[m].orEmpty().toList(),
                rir01TimesByExercise = rir01[m].orEmpty().mapValues { it.value.toList() },
            )
        }
        return PolicyState(ceilings.toMap(), hurtEvents.toList(), stress)
    }
}
```

- [ ] **Step 5: Run the builder test**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PolicyStateBuilderTest"`
Expected: PASS.

- [ ] **Step 6: Store PolicyState in `DerivedStateStore`**

In `DerivedStateStore.kt`: add `import io.github.fowles.stochastic_strength.domain.policy.PolicyState`. In `Snapshot`: add constructor param `private val policyState: PolicyState`, accessor `fun policyState(): PolicyState = policyState`, and change `empty()` to `Snapshot(emptyMap(), emptyList(), emptyList(), emptyMap(), PolicyState.EMPTY)`. In `MutableDerivedState`: add

```kotlin
private var policyState: PolicyState = PolicyState.EMPTY

fun putPolicyState(state: PolicyState) {
    policyState = state
}
```

and include `policyState = policyState` in `toSnapshot()`.

- [ ] **Step 7: Collect during replay in `WorkoutRepository.replayDerivedState`**

Add `import io.github.fowles.stochastic_strength.domain.policy.PolicyStateBuilder`. Inside `derivedState.rebuild { scratch -> ... }`, before `replayEngine.run`, create `val policyBuilder = PolicyStateBuilder()`; change the observer lambda's ignored params to use sets/snapshot and feed the builder; store after the run:

```kotlin
replayEngine.run(db, snapshot) { sessionId, asOf, sets, snap, result ->
    policyBuilder.onSession(asOf, sets, snap)
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
scratch.putPolicyState(policyBuilder.build())
```

- [ ] **Step 8: Run the full unit suite (store/repository touch many tests)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
jj commit -m "feat: derive PolicyState (failure ceilings, hurt events, muscle stress) in replay

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: `PrescriptionPolicy`

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/policy/PrescriptionPolicy.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimate.kt` (EstimatorConfig additions)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WeightFormatter.kt` (add `roundDown`)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/policy/PrescriptionPolicyTest.kt`

**Interfaces:**
- Consumes: Task 3's `PolicyState`/`FailureCeiling`/`HurtEvent`/`MuscleStress`; `ProgressionEngine.fromOneRepMax`; `WeightFormatter.round`.
- Produces:
  - `class PrescriptionPolicy(pooledE1rm: Map<Long, Float>, state: PolicyState, config: EstimatorConfig, progressionEngine: ProgressionEngine, weightUnit: WeightUnit, nowMs: Long)` with `fun prescribe(exercise: Exercise, sessionReps: Int): Float?`, `fun muscleRested(muscle: MuscleGroup): Boolean`, `fun hurtMultiplier(muscle: MuscleGroup): Float`.
  - `WeightFormatter.roundDown(kg: Float, unit: WeightUnit): Float`.
  - `EstimatorConfig` gains: `overloadDelta=0f`, `uncertaintyZ=0f`, `ceilingFactorClear=0.97f`, `ceilingExpiryMs=28d`, `hurtDepth=0.15f`, `hurtHalfLifeMs=14d`, `hurtFloor=0.6f`, `restCooldownMs=2d`.

- [ ] **Step 1: Write the failing test**

Create `PrescriptionPolicyTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrescriptionPolicyTest {

    private val DAY = 24L * 60 * 60 * 1000
    private val NOW = 100L * DAY

    private val bench = Exercise(id = 1L, name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL)

    private fun policy(
        pooled: Map<Long, Float> = mapOf(1L to 100f),
        state: PolicyState = PolicyState.EMPTY,
        unit: WeightUnit = WeightUnit.KG,
    ) = PrescriptionPolicy(pooled, state, EstimatorConfig(), DefaultProgressionEngine, unit, nowMs = NOW)

    private fun oldFormulaWeight(e1rm: Float, reps: Int, unit: WeightUnit = WeightUnit.KG) =
        WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(e1rm, reps), unit)

    @Test
    fun neutralPolicyMatchesTheOldFormulaExactly() {
        assertEquals(oldFormulaWeight(100f, 10), policy().prescribe(bench, 10)!!, 1e-4f)
        assertNull("no pooled e1rm -> null", policy(pooled = emptyMap()).prescribe(bench, 10))
    }

    // For the ceiling tests the pooled belief must sit ABOVE the cap, or the ceiling never binds:
    // rawToOneRepMax(80 kg, 10 reps) ≈ 109.5 kg 1RM, so pooled = 120 kg forces the bind.
    private val pooledAboveCeiling = mapOf(1L to 120f)

    @Test
    fun clearCeilingPrescribesStrictlyBelowTheFailedWeight() {
        val failedWeight = 80f
        val ceiling = DefaultProgressionEngine.rawToOneRepMax(failedWeight, 10)
        val state = PolicyState(
            ceilings = mapOf(1L to FailureCeiling(1L, ceiling, isClear = true, sessionEndTime = NOW - DAY)),
            hurtEvents = emptyList(),
            muscleStress = emptyMap(),
        )
        val unbound = policy(pooled = pooledAboveCeiling).prescribe(bench, 10)!!
        assertTrue("precondition: without the ceiling the target exceeds the failed weight", unbound > failedWeight)
        val w = policy(pooled = pooledAboveCeiling, state = state).prescribe(bench, 10)!!
        assertTrue("prescribed $w must be strictly below failed $failedWeight", w < failedWeight)
    }

    @Test
    fun marginalCeilingAllowsTheSameGridWeight() {
        val failedWeight = 80f
        val ceiling = DefaultProgressionEngine.rawToOneRepMax(failedWeight, 10)
        val state = PolicyState(
            ceilings = mapOf(1L to FailureCeiling(1L, ceiling, isClear = false, sessionEndTime = NOW - DAY)),
            hurtEvents = emptyList(),
            muscleStress = emptyMap(),
        )
        val w = policy(pooled = pooledAboveCeiling, state = state).prescribe(bench, 10)!!
        assertEquals("marginal miss re-prescribes the failed grid weight", failedWeight, w, 1e-3f)
    }

    @Test
    fun ceilingIsRepAwareAndExpires() {
        val ceiling = DefaultProgressionEngine.rawToOneRepMax(80f, 10)
        val fresh = FailureCeiling(1L, ceiling, isClear = true, sessionEndTime = NOW - DAY)
        val freshState = PolicyState(mapOf(1L to fresh), emptyList(), emptyMap())
        // At 5 reps the same 1RM cap allows a heavier bar than the failed 10-rep weight.
        assertTrue(policy(pooled = pooledAboveCeiling, state = freshState).prescribe(bench, 5)!! > 80f * 0.97f)

        val stale = fresh.copy(sessionEndTime = NOW - 29 * DAY)
        val staleState = PolicyState(mapOf(1L to stale), emptyList(), emptyMap())
        assertEquals(
            "expired ceiling does not bind",
            oldFormulaWeight(120f, 10),
            policy(pooled = pooledAboveCeiling, state = staleState).prescribe(bench, 10)!!,
            1e-4f,
        )
    }

    @Test
    fun hurtMultiplierDecaysAndFloors() {
        val p0 = policy(state = PolicyState(emptyMap(), listOf(HurtEvent(MuscleGroup.CHEST, NOW)), emptyMap()))
        assertEquals(0.85f, p0.hurtMultiplier(MuscleGroup.CHEST), 1e-3f)

        val p14 = policy(state = PolicyState(emptyMap(), listOf(HurtEvent(MuscleGroup.CHEST, NOW - 14 * DAY)), emptyMap()))
        assertEquals(1f - 0.15f / 2f, p14.hurtMultiplier(MuscleGroup.CHEST), 1e-3f)

        val many = List(8) { HurtEvent(MuscleGroup.CHEST, NOW) }
        val pFloor = policy(state = PolicyState(emptyMap(), many, emptyMap()))
        assertEquals(EstimatorConfig().hurtFloor, pFloor.hurtMultiplier(MuscleGroup.CHEST), 1e-3f)

        assertEquals("other muscles unaffected", 1f, p0.hurtMultiplier(MuscleGroup.QUADS), 0f)
    }

    @Test
    fun hurtLowersThePrescribedWeight() {
        val hurt = policy(state = PolicyState(emptyMap(), listOf(HurtEvent(MuscleGroup.CHEST, NOW)), emptyMap()))
        assertTrue(hurt.prescribe(bench, 10)!! < policy().prescribe(bench, 10)!!)
    }

    @Test
    fun muscleRestedMatchesTheOldPlannerRule() {
        val recent = NOW - DAY
        fun stressed(state: MuscleStress) = policy(
            state = PolicyState(emptyMap(), emptyList(), mapOf(MuscleGroup.CHEST to state)),
        )
        // Any TOO_HARD within 2 days blocks.
        assertFalse(stressed(MuscleStress(listOf(recent), emptyMap())).muscleRested(MuscleGroup.CHEST))
        // >1 RIR_0_1 on ONE exercise within 2 days blocks.
        assertFalse(stressed(MuscleStress(emptyList(), mapOf(1L to listOf(recent, recent - 1000)))).muscleRested(MuscleGroup.CHEST))
        // Single RIR_0_1, or split across two exercises, does not block.
        assertTrue(stressed(MuscleStress(emptyList(), mapOf(1L to listOf(recent)))).muscleRested(MuscleGroup.CHEST))
        assertTrue(stressed(MuscleStress(emptyList(), mapOf(1L to listOf(recent), 2L to listOf(recent - 1000)))).muscleRested(MuscleGroup.CHEST))
        // Older than 2 days does not block.
        assertTrue(stressed(MuscleStress(listOf(NOW - 3 * DAY), emptyMap())).muscleRested(MuscleGroup.CHEST))
        // Unknown muscle is rested.
        assertTrue(policy().muscleRested(MuscleGroup.BACK))
    }

    @Test
    fun roundDownSnapsToTheGridBelow() {
        assertEquals(77.5f, WeightFormatter.roundDown(79.9f, WeightUnit.KG), 1e-4f)
        assertEquals(80f, WeightFormatter.roundDown(80f, WeightUnit.KG), 1e-4f)
        assertEquals(WeightUnit.LBS.toKg(75f), WeightFormatter.roundDown(WeightUnit.LBS.toKg(79f), WeightUnit.LBS), 1e-3f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicyTest"`
Expected: compilation FAILURE — `PrescriptionPolicy` unresolved.

- [ ] **Step 3: Add config constants and `roundDown`**

Append to `EstimatorConfig` in `ExerciseEstimate.kt` (keep all existing fields):

```kotlin
    /** Overload push δ (log-space). Neutral in phase 1; activated and tuned in phase 2. */
    val overloadDelta: Float = 0f,
    /** Uncertainty shading z. Neutral in phase 1; needs pooled sigma (phase 2+). */
    val uncertaintyZ: Float = 0f,
    /** A CLEAR failure binds the ceiling at this fraction of the failed 1RM, with round-down. */
    val ceilingFactorClear: Float = 0.97f,
    /** Failure ceilings expire after this long (superseded earlier by any newer session). */
    val ceilingExpiryMs: Long = 28L * 24 * 60 * 60 * 1000,
    /** Immediate prescription reduction per HURT event (x(1 - depth) right after). */
    val hurtDepth: Float = 0.15f,
    /** HURT caution half-life. */
    val hurtHalfLifeMs: Long = 14L * 24 * 60 * 60 * 1000,
    /** Floor on the combined HURT multiplier. */
    val hurtFloor: Float = 0.6f,
    /** Sore-muscle planner cooldown window (was WorkoutPlanner.TWO_DAYS_MS). */
    val restCooldownMs: Long = 2L * 24 * 60 * 60 * 1000,
```

Add to `WeightFormatter`:

```kotlin
    /** Rounds DOWN to the prescription grid (used when a clear failure ceiling binds). */
    fun roundDown(kg: Float, unit: WeightUnit): Float {
        return if (unit == WeightUnit.KG) {
            floor(kg / 2.5f + 1e-4f) * 2.5f
        } else {
            val lbs = unit.fromKg(kg)
            unit.toKg(floor(lbs / 5f + 1e-4f) * 5f)
        }
    }
```

(add `import kotlin.math.floor`).

- [ ] **Step 4: Create `PrescriptionPolicy.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.ProgressionEngine
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The prescription policy (spec §4): every training decision between the projected belief and
 * the weight on the bar. Phase 1 scope: neutral z/δ knobs, failure ceiling, HURT caution,
 * sore-muscle cooldown. Fatigue discount and layoff easing arrive with the belief swap (phase 2).
 * Pure and read-only; all inputs derive from replayed history.
 */
class PrescriptionPolicy(
    private val pooledE1rm: Map<Long, Float>,
    private val state: PolicyState,
    private val config: EstimatorConfig = EstimatorConfig(),
    private val progressionEngine: ProgressionEngine,
    private val weightUnit: WeightUnit,
    private val nowMs: Long,
) {

    /** Final session weight in kg for a loadable exercise, or null when nothing is known about it. */
    fun prescribe(exercise: Exercise, sessionReps: Int): Float? {
        val pooled = pooledE1rm[exercise.id] ?: return null
        if (pooled <= 0f) return null

        var targetE1rm = exp(ln(pooled) + config.overloadDelta) // z·σ̃ joins in phase 2

        // Failure ceiling first (spec §4 order): the cap is on demonstrated capacity, so the
        // HURT caution below compounds under it rather than being floored by it.
        var clearCeiling = false
        var failedWeightAtReps = Float.MAX_VALUE
        val ceiling = state.ceilings[exercise.id]
        if (ceiling != null && nowMs - ceiling.sessionEndTime <= config.ceilingExpiryMs) {
            val cap = ceiling.ceilingE1rm * (if (ceiling.isClear) config.ceilingFactorClear else 1f)
            if (targetE1rm > cap) targetE1rm = cap
            if (ceiling.isClear) {
                clearCeiling = true
                failedWeightAtReps = progressionEngine.fromOneRepMax(ceiling.ceilingE1rm, sessionReps)
            }
        }

        targetE1rm *= hurtMultiplier(exercise.primaryMuscle)

        val raw = progressionEngine.fromOneRepMax(targetE1rm, sessionReps)
        val nearest = WeightFormatter.round(raw, weightUnit)
        // A CLEAR ceiling guarantees strictly-below-the-failed-weight even after grid rounding:
        // when nearest-rounding would land at/above the failed weight's equivalent at these reps
        // (possible on coarse grids for light lifts, since the 3% haircut can be under half a grid
        // step), round down instead. Far-below-cap targets keep nearest rounding.
        return if (clearCeiling && nearest >= failedWeightAtReps) WeightFormatter.roundDown(raw, weightUnit)
        else nearest
    }

    /** Combined HURT caution for a muscle: recent events multiply in, decaying with a half-life. */
    fun hurtMultiplier(muscle: MuscleGroup): Float {
        var m = 1f
        for (event in state.hurtEvents) {
            if (event.muscle != muscle) continue
            val age = (nowMs - event.at).coerceAtLeast(0L)
            m *= 1f - config.hurtDepth * 0.5f.pow(age.toFloat() / config.hurtHalfLifeMs)
        }
        return m.coerceAtLeast(config.hurtFloor)
    }

    /**
     * Sore-muscle cooldown (verbatim port of WorkoutPlanner.recentlyFailedMuscles): a muscle is
     * NOT rested when, within the window, a loaded exercise had any TOO_HARD or >1 RIR_0_1 set.
     */
    fun muscleRested(muscle: MuscleGroup): Boolean {
        val stress = state.muscleStress[muscle] ?: return true
        val cutoff = nowMs - config.restCooldownMs
        val anyTooHard = stress.tooHardTimes.any { it >= cutoff }
        val nearLimit = stress.rir01TimesByExercise.any { (_, times) -> times.count { it >= cutoff } > 1 }
        return !(anyTooHard || nearLimit)
    }
}
```

> Amended post-final-review: ceiling clamps before HURT (spec §4 order), and round-down triggers whenever nearest-rounding would reach the failed weight — not only when the cap binds.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicyTest"`
Expected: PASS. Also run `--tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimatorSimulationTest"` — expected PASS (config additions must not disturb the pinned constants).

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat: PrescriptionPolicy (failure ceiling, HURT caution, rest cooldown; neutral z/delta)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Rewire planner and repository through the policy

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (buildPlanner)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSetDao.kt` (delete now-unused query)
- Test (modify): `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt`
- Test (modify): `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerOverrideTest.kt`

**Interfaces:**
- Consumes: `PrescriptionPolicy` (Task 4), `PolicyStateBuilder` (Task 3), `DerivedStateStore.Snapshot.policyState()`.
- Produces: `WorkoutPlanner(availableExercises, policy: PrescriptionPolicy, weightUnit, locationId, random, coefficientSource, progressionEngine, pacingEstimator, exerciseE1rmOverrides)` — `prescribedE1rm`, `recentHistory`, and `nowMs` constructor params REMOVED; `recentlyFailedMuscles` and `TWO_DAYS_MS` deleted.

Accepted micro-delta (documented in the spec): the sore-muscle rule now reads replay-derived stress from **completed** sessions; sets from an abandoned (never-finished) session no longer count toward the cooldown. Two further conservative micro-deltas surfaced in final review: timestamp-less sets count at session end time, and stress accrues from exercises outside the current plannable set.

- [ ] **Step 1: Update the test helpers first (they define the target API)**

In `WorkoutPlannerTest.kt`, replace the `planner(...)` and `lbsPlanner()` helpers (keep `strengthsToPrescribedE1rm` and everything else):

```kotlin
    private fun planner(
        exercises: List<Exercise> = emptyList(),
        strengths: Map<MuscleGroup, MuscleGroupStrength> = emptyMap(),
        random: Random = Random(0),
        recentHistory: Map<Long, List<WorkoutSet>> = emptyMap(),
        nowMs: Long = System.currentTimeMillis(),
        pacingEstimator: ExercisePacingEstimator = ExercisePacingEstimator.EMPTY,
        coefficientSource: CoefficientSource = ExerciseCoefficients,
    ): WorkoutPlanner {
        val builder = PolicyStateBuilder()
        if (recentHistory.isNotEmpty()) {
            val snap = ReplaySnapshot(
                exerciseMuscle = exercises.associate { it.id to it.primaryMuscle },
                seedCoefficients = exercises.associate { it.id to (coefficientSource.get(it) ?: 0f) },
                exerciseEquipment = exercises.associate { it.id to it.equipment },
            )
            builder.onSession(asOf = nowMs, sets = recentHistory.values.flatten(), snapshot = snap)
        }
        val policy = PrescriptionPolicy(
            pooledE1rm = strengthsToPrescribedE1rm(exercises, strengths, coefficientSource),
            state = builder.build(),
            config = EstimatorConfig(),
            progressionEngine = DefaultProgressionEngine,
            weightUnit = WeightUnit.KG,
            nowMs = nowMs,
        )
        return WorkoutPlanner(
            availableExercises = exercises,
            policy = policy,
            weightUnit = WeightUnit.KG,
            locationId = null,
            random = random,
            pacingEstimator = pacingEstimator,
            coefficientSource = coefficientSource,
        )
    }

    private fun lbsPlanner() = WorkoutPlanner(
        availableExercises = emptyList(),
        policy = PrescriptionPolicy(
            pooledE1rm = emptyMap(),
            state = PolicyState.EMPTY,
            config = EstimatorConfig(),
            progressionEngine = DefaultProgressionEngine,
            weightUnit = WeightUnit.LBS,
            nowMs = 0L,
        ),
        weightUnit = WeightUnit.LBS,
        locationId = null,
    )
```

Add imports to the test file:

```kotlin
import io.github.fowles.stochastic_strength.domain.policy.PolicyState
import io.github.fowles.stochastic_strength.domain.policy.PolicyStateBuilder
import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
```

All existing test bodies (including the seven recently-failed-muscle tests) stay untouched — the helper routes `recentHistory` through the real `PolicyStateBuilder`, preserving their semantics.

In `WorkoutPlannerOverrideTest.kt`, replace the `planner(...)` helper:

```kotlin
    private fun planner(overrides: Map<Long, Float>) = WorkoutPlanner(
        availableExercises = listOf(ex(1, "Barbell Bench Press"), ex(2, "Incline Barbell Bench Press")),
        policy = PrescriptionPolicy(
            pooledE1rm = prescribed,
            state = PolicyState.EMPTY,
            config = EstimatorConfig(),
            progressionEngine = DefaultProgressionEngine,
            weightUnit = WeightUnit.KG,
            nowMs = 0L,
        ),
        weightUnit = WeightUnit.KG,
        locationId = null,
        random = Random(1),
        exerciseE1rmOverrides = overrides,
    )
```

with the same four imports added.

- [ ] **Step 2: Run to verify the tests fail to compile**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`
Expected: compilation FAILURE — `WorkoutPlanner` has no `policy` parameter yet.

- [ ] **Step 3: Rewire `WorkoutPlanner`**

In `WorkoutPlanner.kt`:
1. Delete `private const val TWO_DAYS_MS = ...`.
2. Constructor becomes:

```kotlin
class WorkoutPlanner(
    val availableExercises: List<Exercise>,
    private val policy: PrescriptionPolicy,
    val weightUnit: WeightUnit,
    val locationId: Long?,
    private val random: Random = Random.Default,
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
    private val pacingEstimator: ExercisePacingEstimator = ExercisePacingEstimator.EMPTY,
    private val exerciseE1rmOverrides: Map<Long, Float> = emptyMap(),
) {
```

(`prescribedE1rm`, `recentHistory`, `nowMs` removed; add `import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy`, drop the now-unused `SetFeedback` and `WorkoutSet` imports.)
3. Delete the whole `recentlyFailedMuscles` lazy block.
4. Replace `muscleGroupRested`:

```kotlin
    private fun muscleGroupRested(exercise: Exercise): Boolean =
        exercise.equipment == Equipment.BODYWEIGHT || policy.muscleRested(exercise.primaryMuscle)
```

5. Replace `weightForExercise`:

```kotlin
    private fun weightForExercise(exercise: Exercise, sessionReps: Int): Float {
        val coeff = coefficientSource.get(exercise) ?: return 0f
        if (coeff <= 0f) return 0f // unloadable (bodyweight/banded): no prescription
        exerciseE1rmOverrides[exercise.id]?.let { e1rm ->
            // Manual override bypasses the policy entirely (spec §4).
            if (e1rm <= 0f) return 0f
            return WeightFormatter.round(progressionEngine.fromOneRepMax(e1rm, sessionReps), weightUnit)
        }
        return policy.prescribe(exercise, sessionReps) ?: 0f
    }
```

- [ ] **Step 4: Rewire `WorkoutRepository.buildPlanner`**

Delete the `history` query block (`val history = if (available.isNotEmpty()) ... else emptyMap()`), add `import io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy`, and replace the planner construction:

```kotlin
        val policy = PrescriptionPolicy(
            pooledE1rm = prescribedE1rm,
            state = derivedState.snapshot().policyState(),
            config = EstimatorConfig(),
            progressionEngine = progressionEngine,
            weightUnit = weightUnit,
            nowMs = now,
        )
        return WorkoutPlanner(
            availableExercises = available,
            policy = policy,
            weightUnit = weightUnit,
            locationId = locationId,
            coefficientSource = effectiveCoefficients,
            progressionEngine = progressionEngine,
            pacingEstimator = pacingEstimator,
            exerciseE1rmOverrides = exerciseOverrides,
        )
```

- [ ] **Step 5: Delete the now-unused DAO query**

In `WorkoutSetDao.kt`, delete the `getRecentSetsForExercises` function together with its `@Query` annotation. Verify no remaining references: `rg -n "getRecentSetsForExercises" app/src` must return nothing.

- [ ] **Step 6: Run the affected tests, then the full suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest" --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerOverrideTest"`
Expected: PASS.
Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
jj commit -m "refactor: planner prescribes through PrescriptionPolicy; sore-muscle rule moves to policy

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: HURT leaves the estimator

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimateUpdater.kt` (delete `hurt`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimate.kt` (delete `hurtFactor`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SessionProgressionStepper.kt` (drop HURT propagation)
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/SessionProgressionStepperTest.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimateUpdaterTest.kt`
- Modify: `docs/adaptation/03-exercise-estimates.md`, `CLAUDE.md` (keep shipped-behavior docs truthful)

**Interfaces:**
- Consumes: nothing new.
- Produces: `ExerciseEstimateUpdater` without `hurt(...)`; `EstimatorConfig` without `hurtFactor`; `SessionProgressionStepper.step` that never touches estimates on HURT (policy owns pain via Task 3's `HurtEvent`s + Task 4's `hurtMultiplier`).

- [ ] **Step 1: Rewrite the behavior tests first**

In `SessionProgressionStepperTest.kt`, replace `hurtBacksOffEveryLoadedExerciseInTheMuscle` with:

```kotlin
    @Test
    fun hurtLeavesEstimatesUntouched() {
        val snap = snapshot()
        val before1 = snap.currentEstimates.getValue(1L).lnE
        val before2 = snap.currentEstimates.getValue(2L).lnE
        val result = stepper.step(
            sets = listOf(set(2L, weight = 60f, reps = 5, feedback = SetFeedback.HURT)),
            snapshot = snap,
            asOf = 2_000L,
        )
        // Pain is a policy concern (PrescriptionPolicy.hurtMultiplier); capacity history stays intact.
        assertEquals(before1, snap.currentEstimates.getValue(1L).lnE, 1e-6f)
        assertEquals(before2, snap.currentEstimates.getValue(2L).lnE, 1e-6f)
        assertTrue("hurt-only session emits no projection step", result.steps.isEmpty())
    }
```

(delete the unused `ln` import if the compiler flags it). In `ExerciseEstimateUpdaterTest.kt`, delete the `hurtBacksOffByConfiguredFactor` test.

- [ ] **Step 2: Run to verify the stepper test fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.SessionProgressionStepperTest"`
Expected: FAIL — estimates currently move by ln(0.85).

- [ ] **Step 3: Delete the estimator's HURT machinery**

In `SessionProgressionStepper.kt`: update the class KDoc first line to `Pure per-session core of progression: per-exercise fold → projection of each affected muscle. HURT never touches estimates (pain is handled by PrescriptionPolicy at read time).`, drop the now-unused `import io.github.fowles.stochastic_strength.data.model.SetFeedback`, and replace the whole `step` function with:

```kotlin
    fun step(sets: List<WorkoutSet>, snapshot: ReplaySnapshot, asOf: Long): StepResult {
        if (sets.isEmpty()) return StepResult(emptyList())

        // Per-exercise fold from the session aggregate.
        val affectedMuscles = mutableSetOf<MuscleGroup>()
        sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
            if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
            val agg = SessionSignalExtractor.aggregateSession(exSets) ?: return@forEach
            val prior = snapshot.currentEstimates[id] ?: return@forEach
            snapshot.currentEstimates[id] = updater.fold(prior, agg.est1RM, agg.bracketConfidence, asOf)
            snapshot.exerciseMuscle[id]?.let { affectedMuscles.add(it) }
        }

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
```

In `ExerciseEstimateUpdater.kt`: delete the `hurt(...)` function.

In `ExerciseEstimate.kt`: delete the `hurtFactor` field from `EstimatorConfig` (Task 4's `hurtDepth`/`hurtHalfLifeMs`/`hurtFloor` are its replacement).

- [ ] **Step 4: Run the progression suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.*"`
Expected: PASS (the simulation never emits HURT; its pins are unaffected).

- [ ] **Step 5: Keep the docs truthful**

In `docs/adaptation/03-exercise-estimates.md`, replace the whole `## Pain is muscle-wide and multiplicative` section with:

```markdown
## Pain never touches the estimate

HURT carries no load signal and — since the belief+policy reframe (phase 1) — no longer
alters any estimate. It is recorded as a muscle-level policy event during replay and applied
at prescription time as a decaying caution multiplier (×(1 − 0.15) immediately, healing with
a ~2-week half-life, floored). See `domain/policy/PrescriptionPolicy.kt` and
`docs/superpowers/specs/2026-07-06-belief-policy-reframe-design.md` §4.
```

In `CLAUDE.md`, in the "For one session" list, replace item 1 (`**HURT** is muscle-level: ...`) with:

```markdown
1. **HURT** never touches estimates: it becomes a muscle-level policy event; `PrescriptionPolicy` applies a decaying caution multiplier (×0.85 immediately, ~2-week half-life) at prescription time.
```

and in the paragraph beginning `Session weight is derived per exercise`, replace `passed to the planner as `prescribedE1rm`` with `wrapped in `PrescriptionPolicy` (failure ceiling, HURT caution, rest cooldown) and passed to the planner`. Add a `domain/policy/` line to the Layers block: `domain/policy/    Prescription policy: failure ceilings, HURT caution, rest cooldown (PolicyState derived in replay)`.

- [ ] **Step 6: Full suite and commit**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS.

```bash
jj commit -m "feat: HURT moves out of the estimator into PrescriptionPolicy

Capacity history stays intact on pain; prescriptions still back off
immediately (x0.85) and now heal with a ~2-week half-life instead of
requiring the estimate to relearn the lost 15%.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: Backtest comparison, band pin, and end-to-end verification

Requires the Task 2 USER ACTION (frozen baseline) to have happened.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestHarness.kt` (add policy-path replay; equipment in snapshot)
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestComparisonTest.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProdBssPrescriptionTest.kt` (policy-path assertion)

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces: `BacktestHarness.replayPolicyPrescriptions(data): List<Row>`; the pinned delta band.

- [ ] **Step 1: Extend the harness with the policy path**

In `BacktestHarness.kt`: change `BacktestData.newSnapshot()` to also pass `exerciseEquipment = backup.exercises.associate { it.id to it.equipment }`, and add (with imports `io.github.fowles.stochastic_strength.domain.policy.PolicyStateBuilder`, `io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy`, `io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig`):

```kotlin
    /** Prescriptions right after each session via the production policy path (post-phase-1 semantics). */
    fun replayPolicyPrescriptions(data: BacktestData): List<Row> {
        val snapshot = data.newSnapshot()
        val projector = MuscleStrengthProjector()
        val builder = PolicyStateBuilder()
        val exercisesById = data.backup.exercises.associateBy { it.id }
        val rows = mutableListOf<Row>()
        ReplayEngine().run(data.history, snapshot) { sessionId, asOf, sets, snap, _ ->
            builder.onSession(asOf, sets, snap)
            val policyState = builder.build()
            for ((_, ids) in snap.muscleExerciseIds) {
                val proj = projector.project(snap.currentEstimates, snap.seedCoefficients, ids, asOf)
                val policy = PrescriptionPolicy(
                    pooledE1rm = proj.effectiveE1rm,
                    state = policyState,
                    config = EstimatorConfig(),
                    progressionEngine = DefaultProgressionEngine,
                    weightUnit = data.weightUnit,
                    nowMs = asOf,
                )
                for (id in ids.sorted()) {
                    val exercise = exercisesById[id] ?: continue
                    val w = policy.prescribe(exercise, REFERENCE_REPS) ?: continue
                    rows += Row(sessionId, id, w)
                }
            }
        }
        return rows
    }
```

- [ ] **Step 2: Write the comparison test**

Create `BacktestComparisonTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Old-vs-new gate over the real exported history (spec §9). Skipped when the local fixture
 * files are absent. BAND is pinned after inspecting the printed delta table — phase-1 deltas
 * must be attributable to the intended HURT-healing / failure-ceiling semantic changes only.
 */
class BacktestComparisonTest {

    // Provisional. Tighten to (observed worst delta + 0.05) in Step 4 and record the observed
    // value in a comment here.
    private val BAND = 0.25f

    @Test
    fun policyPrescriptionsStayWithinBandOfFrozenBaselineAndNeverGoNaN() {
        val data = BacktestHarness.load()
        assumeTrue("no local backtest history; skipping", data != null)
        val baseline = BacktestHarness.readBaseline()
        assumeTrue("baseline not frozen; run BacktestBaselineGeneratorTest first", baseline != null)

        val current = BacktestHarness.replayPolicyPrescriptions(data!!)
        assertTrue("no prescriptions produced", current.isNotEmpty())
        current.forEach { assertFalse("NaN weight at $it", it.weightKg.isNaN()) }

        val baseByKey = baseline!!.associateBy { it.sessionId to it.exerciseId }
        var worst = 0f
        var worstDesc = ""
        val report = StringBuilder("session exercise old new delta\n")
        for (row in current) {
            val old = baseByKey[row.sessionId to row.exerciseId] ?: continue
            if (old.weightKg <= 0f) continue
            val rel = abs(row.weightKg - old.weightKg) / old.weightKg
            if (rel > 0.02f) {
                report.appendLine("${row.sessionId} ${row.exerciseId} ${old.weightKg} ${row.weightKg} ${(rel * 100).roundToInt()}%")
            }
            if (rel > worst) {
                worst = rel
                worstDesc = "session=${row.sessionId} exercise=${row.exerciseId} old=${old.weightKg} new=${row.weightKg}"
            }
        }
        println(report)
        println("worst relative delta: ${(worst * 100).roundToInt()}% ($worstDesc)")
        assertTrue("worst delta $worst ($worstDesc) exceeds band $BAND", worst <= BAND)
    }
}
```

- [ ] **Step 3: Add the policy-path pin to `ProdBssPrescriptionTest`**

Add this test to the class (imports to add: `io.github.fowles.stochastic_strength.data.model.Equipment`, `io.github.fowles.stochastic_strength.data.model.Exercise`, `io.github.fowles.stochastic_strength.domain.policy.PolicyStateBuilder`, `io.github.fowles.stochastic_strength.domain.policy.PrescriptionPolicy`, `io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig`):

```kotlin
    @Test
    fun policyPathAlsoPrescribesTheDemonstrated20lb() {
        val exerciseMuscle = seedCoef.keys.associateWith { MuscleGroup.QUADS }
        val snapshot = ReplaySnapshot(exerciseMuscle = exerciseMuscle, seedCoefficients = seedCoef)
        for ((id, e1rm) in initials) snapshot.currentEstimates[id] = ExerciseEstimate.seed(e1rm, at = 0)

        val stepper = SessionProgressionStepper()
        val builder = PolicyStateBuilder()
        for (sessionId in listOf(12L, 14L, 15L, 16L, 18L)) {
            val sessionSets = sets.filter { it.sessionId == sessionId }
            stepper.step(sessionSets, snapshot, endTimes[sessionId]!!)
            builder.onSession(endTimes[sessionId]!!, sessionSets, snapshot)
        }

        val proj = MuscleStrengthProjector().project(
            estimates = snapshot.currentEstimates,
            seedCoef = seedCoef,
            muscleExerciseIds = seedCoef.keys.toList(),
            now = EXPORTED_AT,
        )
        val policy = PrescriptionPolicy(
            pooledE1rm = proj.effectiveE1rm,
            state = builder.build(),
            config = EstimatorConfig(),
            progressionEngine = DefaultProgressionEngine,
            weightUnit = WeightUnit.LBS,
            nowMs = EXPORTED_AT,
        )
        val bss = Exercise(id = 55L, name = "Bulgarian Split Squat", primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.DUMBBELL)
        val weightKg = policy.prescribe(bss, 10)!!
        // The session-18 clear ceiling (~25.3 kg 1RM) sits ABOVE the demonstrated-capacity target
        // (~16.9 kg 1RM), so it must not bind: the estimator's 20 lb answer passes through.
        assertEquals(20, WeightUnit.LBS.fromKg(weightKg).toInt())
    }
```

- [ ] **Step 4: Run everything, inspect, pin the band**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.*" --tests "io.github.fowles.stochastic_strength.domain.ProdBssPrescriptionTest"`
Expected: PASS, with the delta table printed.

Then: read the printed table. Every >2% delta must be attributable to a HURT event or a failure ceiling in the surrounding sessions (cross-check against the history). If an unexplained delta appears, STOP and debug before pinning (use superpowers:systematic-debugging). Once explained: set `BAND = <observed worst> + 0.05` in `BacktestComparisonTest`, record the observed value and its cause in the comment above it, and re-run to confirm PASS.

- [ ] **Step 5: Full verification**

Run: `./gradlew :app:testDebugUnitTest` — expected PASS.
Run: `./gradlew :app:lint` — expected clean.
Run: `./gradlew :app:connectedAndroidTest` — attempt directly (an emulator is typically running); expected PASS. If no device is available, report that it was skipped rather than claiming it passed.

- [ ] **Step 6: Commit**

```bash
jj commit -m "test: backtest comparison gate + ProdBss policy-path pin; band pinned

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Completion checklist (end of phase 1)

- All 7 tasks committed; `./gradlew :app:testDebugUnitTest` and `:app:lint` green; connectedAndroidTest attempted.
- Backtest delta table inspected and band pinned (requires the user's exported history).
- The spec's phase-1 bullet is fully covered: policy layer live in production wiring, HURT out of the estimator, `muscleRested` moved, z/δ present-but-neutral, backtest harness + frozen baseline in place.
- Release/version bump is the user's call (phases are shippable; convention is a `version: bump` commit when releasing).
- Update memory (`project_fable_estimator_review.md` or a new phase-1 memory) and mark plan checkboxes done — the orchestrating session does this, not subagents.
