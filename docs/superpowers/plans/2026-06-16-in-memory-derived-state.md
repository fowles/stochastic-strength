# In-Memory Derived State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the three derived-state tables (`muscle_group_strength`, `baseline_history`, `coefficient_history`) out of Room and into an in-memory `DerivedStateStore` rebuilt by `replayDerivedState` at app start.

**Architecture:** Today `replayDerivedState` already wipes and rebuilds those three tables on every cold start (called from `DerivedStateBackfill` in `StochasticStrengthApp.onCreate`) and again on every session finish. They provide no durability benefit. They are read only via `suspend` calls (no `Flow<>` returns), so replacing them with an in-memory store is a drop-in. We introduce a single `DerivedStateStore` with an immutable `Snapshot` + a `Mutable` builder used inside `rebuild { ... }`. The store is owned by `StochasticStrengthApp` and threaded through `WorkoutRepository`.

**Migration strategy:** Strangler-fig. Each task leaves the build + test suite green. Steps in order:
1. Build the store with JVM unit tests.
2. Wire the store as a constructor dependency (no behavior change).
3. Add dual-writes (Room + store) so both are always in sync.
4. Switch internal reads to store.
5. Switch debug ViewModel reads to store (via repository).
6. Update tests to read/write through the store.
7. Drop the Room writes.
8. Drop the entities, DAOs, and tables (schema v13 → v14).

**Tech Stack:** Kotlin, Jetpack Compose, Room, Coroutines. Module: `app/`.

---

## File Structure

**Create:**
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStore.kt` — the store class (Snapshot, MutableDerivedState, rebuild, accessors)
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStoreTest.kt` — JVM unit tests for store semantics

**Modify (production):**
- `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt` — instantiate `DerivedStateStore` singleton and pass to repository
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` — accept store in constructor; dual-write then drop Room writes; read from store
- `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt` — bump version to 14, drop entities + accessors, add `MIGRATION_13_14`
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt` — read latest coefficients from repository, not direct DAO
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt` — same

**Delete (final task):**
- `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/BaselineHistoryDao.kt`
- `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/CoefficientHistoryDao.kt`
- `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/MuscleGroupStrengthDao.kt`

**Keep but un-`@Entity`-ify** (final task; they're still transport types):
- `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineHistory.kt`
- `app/src/main/java/io/github/fowles/stochastic_strength/data/model/CoefficientHistory.kt`
- `app/src/main/java/io/github/fowles/stochastic_strength/data/model/MuscleGroupStrength.kt`

**Modify (tests):**
- `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/ReplayDerivedStateTest.kt`
- `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/LiveInputWritesTest.kt`
- `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`
- `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt`
- `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/DerivedStateBackfillTest.kt`
- `app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt`

---

## Task 1: Create `DerivedStateStore` with JVM unit tests

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStore.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStoreTest.kt`

### Step 1.1: Write failing tests for the store

- [x] **Create the test file**

```kotlin
package io.github.fowles.stochastic_strength.domain.derived

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DerivedStateStoreTest {

    @Test fun emptyStoreReturnsEmptyResults() {
        val store = DerivedStateStore()
        val snap = store.snapshot()
        assertTrue(snap.allMuscleGroupStrengths().isEmpty())
        assertTrue(snap.allBaselineHistory().isEmpty())
        assertNull(snap.muscleGroupStrength(MuscleGroup.CHEST))
        assertTrue(snap.baselineHistoryForMuscle(MuscleGroup.CHEST).isEmpty())
        assertTrue(snap.coefficientHistoryForExercise(7L).isEmpty())
        assertTrue(snap.coefficientHistoryLatestPerExercise().isEmpty())
        assertTrue(snap.coefficientHistoryMostRecent(5).isEmpty())
    }

    @Test fun rebuildPopulatesAllThreeStores() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
            mut.insertBaselineHistory(baselineRow(MuscleGroup.CHEST, ts = 10L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 1L, value = 1.2f, ts = 10L))
        }
        val snap = store.snapshot()
        assertEquals(100f, snap.muscleGroupStrength(MuscleGroup.CHEST)?.baselineWeight)
        assertEquals(1, snap.allBaselineHistory().size)
        assertEquals(1, snap.coefficientHistoryForExercise(1L).size)
    }

    @Test fun rebuildAssignsAutoIncrementIdsStartingAtOne() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            val a = mut.insertBaselineHistory(baselineRow(MuscleGroup.CHEST, ts = 10L))
            val b = mut.insertBaselineHistory(baselineRow(MuscleGroup.CHEST, ts = 20L))
            assertEquals(1L, a)
            assertEquals(2L, b)
        }
        val ids = store.snapshot().allBaselineHistory().map { it.id }
        assertEquals(listOf(1L, 2L), ids)
    }

    @Test fun rebuildIsAtomicOnException() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        }
        val before = store.snapshot()

        try {
            store.rebuild { mut ->
                mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 200f))
                throw IllegalStateException("boom")
            }
            fail("expected exception")
        } catch (_: IllegalStateException) {
            // expected
        }
        val after = store.snapshot()
        assertEquals(100f, after.muscleGroupStrength(MuscleGroup.CHEST)?.baselineWeight)
        assertEquals(before.allMuscleGroupStrengths(), after.allMuscleGroupStrengths())
    }

    @Test fun baselineHistoryForMuscleReturnsTimestampAscending() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.insertBaselineHistory(baselineRow(MuscleGroup.CHEST, ts = 30L))
            mut.insertBaselineHistory(baselineRow(MuscleGroup.CHEST, ts = 10L))
            mut.insertBaselineHistory(baselineRow(MuscleGroup.QUADS, ts = 20L))
        }
        val chest = store.snapshot().baselineHistoryForMuscle(MuscleGroup.CHEST)
        assertEquals(listOf(10L, 30L), chest.map { it.timestamp })
    }

    @Test fun coefficientLatestPerExerciseReturnsHighestComputedAt() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.insertCoefficientHistory(coefRow(exerciseId = 1L, value = 1.0f, ts = 10L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 1L, value = 1.5f, ts = 20L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 2L, value = 0.8f, ts = 15L))
        }
        val latest = store.snapshot().coefficientHistoryLatestPerExercise().associateBy { it.exerciseId }
        assertEquals(1.5f, latest[1L]?.coefficient)
        assertEquals(0.8f, latest[2L]?.coefficient)
        assertEquals(2, latest.size)
    }

    @Test fun coefficientMostRecentSortsDescendingByComputedAt() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.insertCoefficientHistory(coefRow(exerciseId = 1L, value = 1.0f, ts = 10L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 2L, value = 1.0f, ts = 30L))
            mut.insertCoefficientHistory(coefRow(exerciseId = 3L, value = 1.0f, ts = 20L))
        }
        val mostRecent = store.snapshot().coefficientHistoryMostRecent(limit = 2)
        assertEquals(listOf(30L, 20L), mostRecent.map { it.computedAt })
    }

    @Test fun snapshotIsImmutableAfterReturn() = runTest {
        val store = DerivedStateStore()
        store.rebuild { mut ->
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
        }
        val first = store.snapshot()
        store.rebuild { mut ->
            mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 200f))
        }
        // The previously captured snapshot must still reflect the pre-rebuild value.
        assertEquals(100f, first.muscleGroupStrength(MuscleGroup.CHEST)?.baselineWeight)
        assertEquals(200f, store.snapshot().muscleGroupStrength(MuscleGroup.CHEST)?.baselineWeight)
    }

    @Test fun mutableReadsReflectInProgressWrites() = runTest {
        val store = DerivedStateStore()
        var midRebuildLatest: Float? = null
        store.rebuild { mut ->
            mut.insertCoefficientHistory(coefRow(exerciseId = 1L, value = 1.2f, ts = 10L))
            midRebuildLatest = mut.coefficientHistoryLatestPerExercise()
                .firstOrNull { it.exerciseId == 1L }?.coefficient
        }
        assertEquals(1.2f, midRebuildLatest)
    }

    private fun baselineRow(muscle: MuscleGroup, ts: Long) = BaselineHistory(
        sessionId = null,
        muscleGroup = muscle,
        previousBaseline = 0f,
        newBaseline = 100f,
        changeReason = BaselineChangeReason.INITIAL,
        timestamp = ts,
    )

    private fun coefRow(exerciseId: Long, value: Float, ts: Long) = CoefficientHistory(
        exerciseId = exerciseId,
        coefficient = value,
        heuristicName = "test",
        computedAt = ts,
    )
}
```

- [x] **Run the tests to confirm they fail to compile** (class does not yet exist)

```
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.derived.DerivedStateStoreTest"
```

Expected: compilation failure on `DerivedStateStore`.

### Step 1.2: Implement `DerivedStateStore`

- [x] **Create the file**

```kotlin
package io.github.fowles.stochastic_strength.domain.derived

import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory replacement for the muscle_group_strength, baseline_history, and
 * coefficient_history Room tables. Rebuilt from scratch by
 * [io.github.fowles.stochastic_strength.domain.WorkoutRepository.replayDerivedState]
 * on every cold start and every session finish.
 *
 * Single source of truth is an immutable [Snapshot] swapped atomically at the
 * end of [rebuild]. If the rebuild block throws, the live snapshot is preserved.
 */
class DerivedStateStore {
    private val rebuildMutex = Mutex()

    @Volatile
    private var live: Snapshot = Snapshot.empty()

    fun snapshot(): Snapshot = live

    /**
     * Atomically rebuild the store. The block receives a [MutableDerivedState]
     * that supports both reads (reflecting in-progress writes) and writes.
     * On normal return, the mutable view becomes the new live snapshot.
     * On exception, the previous snapshot is retained.
     */
    suspend fun rebuild(block: suspend (MutableDerivedState) -> Unit) {
        rebuildMutex.withLock {
            val scratch = MutableDerivedState()
            block(scratch)
            live = scratch.toSnapshot()
        }
    }

    class Snapshot internal constructor(
        private val muscleStrengths: Map<MuscleGroup, MuscleGroupStrength>,
        private val baselineHistory: List<BaselineHistory>,
        private val coefficientHistory: List<CoefficientHistory>,
    ) {
        fun muscleGroupStrength(muscle: MuscleGroup): MuscleGroupStrength? = muscleStrengths[muscle]

        fun allMuscleGroupStrengths(): List<MuscleGroupStrength> = muscleStrengths.values.toList()

        fun allBaselineHistory(): List<BaselineHistory> = baselineHistory

        fun baselineHistoryForMuscle(muscle: MuscleGroup): List<BaselineHistory> =
            baselineHistory.filter { it.muscleGroup == muscle }.sortedBy { it.timestamp }

        fun coefficientHistoryForExercise(exerciseId: Long): List<CoefficientHistory> =
            coefficientHistory.filter { it.exerciseId == exerciseId }.sortedBy { it.computedAt }

        fun coefficientHistoryLatestPerExercise(): List<CoefficientHistory> =
            coefficientHistory
                .groupBy { it.exerciseId }
                .mapNotNull { (_, rows) -> rows.maxByOrNull { it.computedAt } }

        fun coefficientHistoryMostRecent(limit: Int): List<CoefficientHistory> =
            coefficientHistory.sortedByDescending { it.computedAt }.take(limit)

        companion object {
            fun empty() = Snapshot(emptyMap(), emptyList(), emptyList())
        }
    }
}

class MutableDerivedState internal constructor() {
    private val muscleStrengths = mutableMapOf<MuscleGroup, MuscleGroupStrength>()
    private val baselineHistory = mutableListOf<BaselineHistory>()
    private val coefficientHistory = mutableListOf<CoefficientHistory>()
    private var nextBaselineId: Long = 1
    private var nextCoefficientId: Long = 1

    fun upsertMuscleGroupStrength(strength: MuscleGroupStrength) {
        muscleStrengths[strength.muscleGroup] = strength
    }

    fun insertBaselineHistory(row: BaselineHistory): Long {
        val id = nextBaselineId++
        baselineHistory.add(row.copy(id = id))
        return id
    }

    fun insertCoefficientHistory(row: CoefficientHistory): Long {
        val id = nextCoefficientId++
        coefficientHistory.add(row.copy(id = id))
        return id
    }

    // Read accessors — symmetric with Snapshot, used during rebuild.
    fun muscleGroupStrength(muscle: MuscleGroup): MuscleGroupStrength? = muscleStrengths[muscle]

    fun allMuscleGroupStrengths(): List<MuscleGroupStrength> = muscleStrengths.values.toList()

    fun allBaselineHistory(): List<BaselineHistory> = baselineHistory.toList()

    fun baselineHistoryForMuscle(muscle: MuscleGroup): List<BaselineHistory> =
        baselineHistory.filter { it.muscleGroup == muscle }.sortedBy { it.timestamp }

    fun coefficientHistoryLatestPerExercise(): List<CoefficientHistory> =
        coefficientHistory
            .groupBy { it.exerciseId }
            .mapNotNull { (_, rows) -> rows.maxByOrNull { it.computedAt } }

    internal fun toSnapshot(): DerivedStateStore.Snapshot = DerivedStateStore.Snapshot(
        muscleStrengths = muscleStrengths.toMap(),
        baselineHistory = baselineHistory.toList(),
        coefficientHistory = coefficientHistory.toList(),
    )
}
```

- [x] **Run the tests to confirm they pass**

```
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.derived.DerivedStateStoreTest"
```

Expected: 9 tests, all pass.

### Step 1.3: Commit

- [x] **Commit**

```
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStore.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStoreTest.kt
git commit -m "feat(domain): add DerivedStateStore for in-memory derived projections"
```

---

## Task 2: Wire `DerivedStateStore` through `WorkoutRepository` (no behavior change)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt:24-30`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt:33-40`

### Step 2.1: Add constructor dependency

- [x] **Edit `WorkoutRepository.kt`**

Add the import:
```kotlin
import io.github.fowles.stochastic_strength.domain.derived.DerivedStateStore
```

Change the constructor signature (currently lines 24-30):
```kotlin
class WorkoutRepository(
    private val db: AppDatabase,
    val derivedState: DerivedStateStore = DerivedStateStore(),
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
    private val heuristic: CoefficientHeuristic? = null,
    private val normalizer: BaselineNormalizer? = null,
    private val baselineHeuristic: BaselineHeuristic,
) {
```

Note: `derivedState` is `val` (public) so tests and debug ViewModels can read snapshots. Default value lets existing test constructors compile.

### Step 2.2: Wire the singleton in the Application class

- [x] **Edit `StochasticStrengthApp.kt`**

Add import:
```kotlin
import io.github.fowles.stochastic_strength.domain.derived.DerivedStateStore
```

Replace the `workoutRepository` lazy initializer (currently lines 33-40):
```kotlin
val derivedStateStore = DerivedStateStore()
val workoutRepository: WorkoutRepository by lazy {
    WorkoutRepository(
        database,
        derivedState = derivedStateStore,
        heuristic = EstCoefConsensusHeuristic(),
        normalizer = SeedNormalizer(),
        baselineHeuristic = EstBaselineConsensusHeuristic(),
    )
}
```

### Step 2.3: Build + run existing tests

- [x] **Build**

```
./gradlew :app:assembleDebug
```

Expected: success.

- [x] **Run unit tests**

```
./gradlew :app:testDebugUnitTest
```

Expected: success.

- [x] **Run instrumented tests**

```
./gradlew :app:connectedAndroidTest
```

Expected: success. Behavior is unchanged because nothing reads from or writes to the new store yet.

### Step 2.4: Commit

- [x] **Commit**

```
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt
git commit -m "feat(domain): plumb DerivedStateStore through WorkoutRepository"
```

---

## Task 3: Dual-write — every Room write also updates `DerivedStateStore`

**Goal:** Every existing write to `muscleGroupStrengthDao`, `baselineHistoryDao`, `coefficientHistoryDao` happens in lockstep with a corresponding mutation on a `MutableDerivedState`. After this task, the live snapshot is fully populated and reads can be switched independently.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

### Step 3.1: Restructure `replayDerivedState` to nest inside `derivedState.rebuild`

- [x] **Edit `replayDerivedState`** (currently lines 281-341)

Replace the body so the entire existing logic runs inside `derivedState.rebuild { scratch -> ... }`. The `scratch` parameter is a `MutableDerivedState` available to every write helper called downstream. Pattern:

```kotlin
suspend fun replayDerivedState(
    reductionsBySession: Map<Long, Map<Long, Float>> = emptyMap(),
) = replayMutex.withLock {
    db.withTransaction {
        db.baselineHistoryDao().deleteAll()
        db.coefficientHistoryDao().deleteAll()
        db.muscleGroupStrengthDao().deleteAll()

        derivedState.rebuild { scratch ->
            val snapshot = ReplaySnapshot.loadStaticFromDb(db)
            val initials = db.baselineOverrideDao().getInitials()
            val overridesBySession = db.baselineOverrideDao().getNonInitials()
                .groupBy { it.sessionId!! }

            for (init in initials) {
                snapshot.currentBaselines[init.muscleGroup] = init.baselineWeight
                db.muscleGroupStrengthDao().upsert(
                    MuscleGroupStrength(muscleGroup = init.muscleGroup, baselineWeight = init.baselineWeight)
                )
                scratch.upsertMuscleGroupStrength(
                    MuscleGroupStrength(muscleGroup = init.muscleGroup, baselineWeight = init.baselineWeight)
                )
                val row = BaselineHistory(
                    sessionId = null,
                    muscleGroup = init.muscleGroup,
                    previousBaseline = 0f,
                    newBaseline = init.baselineWeight,
                    changeReason = BaselineChangeReason.INITIAL,
                    timestamp = init.asOf,
                )
                db.baselineHistoryDao().insert(row)
                scratch.insertBaselineHistory(row)
                snapshot.baselineHistoryByMuscle.getOrPut(init.muscleGroup) { mutableListOf() }.add(row)
            }

            val sessions = db.workoutSessionDao().getAll()
                .filter { it.endTime != null }
                .sortedWith(compareBy({ it.endTime!! }, { it.id }))

            for (session in sessions) {
                overridesBySession[session.id]?.forEach { o ->
                    val prev = snapshot.currentBaselines[o.muscleGroup] ?: 0f
                    snapshot.currentBaselines[o.muscleGroup] = o.baselineWeight
                    db.muscleGroupStrengthDao().upsert(
                        MuscleGroupStrength(muscleGroup = o.muscleGroup, baselineWeight = o.baselineWeight)
                    )
                    scratch.upsertMuscleGroupStrength(
                        MuscleGroupStrength(muscleGroup = o.muscleGroup, baselineWeight = o.baselineWeight)
                    )
                    val row = BaselineHistory(
                        sessionId = session.id,
                        muscleGroup = o.muscleGroup,
                        previousBaseline = prev,
                        newBaseline = o.baselineWeight,
                        changeReason = BaselineChangeReason.OVERRIDE,
                        timestamp = o.asOf,
                    )
                    db.baselineHistoryDao().insert(row)
                    scratch.insertBaselineHistory(row)
                    snapshot.baselineHistoryByMuscle.getOrPut(o.muscleGroup) { mutableListOf() }.add(row)
                }
                applySessionProgression(
                    session.id,
                    snapshot,
                    asOf = session.endTime!!,
                    exerciseReductions = reductionsBySession[session.id] ?: emptyMap(),
                    scratch = scratch,
                )
            }
        }
    }
}
```

Note: `applySessionProgression` now takes a `scratch: MutableDerivedState` parameter.

### Step 3.2: Thread `scratch` through `applySessionProgression`, `applyBaselineProposal`, `recomputeCoefficients`, `applyBaselineNormalization`

- [x] **Update `applySessionProgression` signature** (currently lines 78-101)

Add the parameter:
```kotlin
private suspend fun applySessionProgression(
    sessionId: Long,
    snapshot: ReplaySnapshot,
    asOf: Long,
    exerciseReductions: Map<Long, Float>,
    scratch: MutableDerivedState,
) {
    val input = buildBaselineComputationInput(sessionId, snapshot, asOf, exerciseReductions)
        ?: return
    val setsByMuscle = input.sets.groupBy { input.exerciseMuscle[it.exerciseId] }
    for (proposal in baselineHeuristic.compute(input)) {
        applyBaselineProposal(
            proposal = proposal,
            sessionId = sessionId,
            snapshot = snapshot,
            weightUnit = input.weightUnit,
            sessionReps = input.sessionReps,
            minReductionsByMuscle = input.minReductionFractions,
            setsByMuscle = setsByMuscle,
            asOf = asOf,
            scratch = scratch,
        )
    }
    recomputeCoefficients(snapshot, asOf, scratch)
    applyBaselineNormalization(snapshot, asOf, sessionId, scratch)
}
```

- [x] **Update `applyBaselineProposal`** (currently lines 137-172)

Add the parameter and the in-memory writes. Replace the body of the function to include both `db.*` writes and `scratch.*` writes for the muscle strength upsert and history insert:

```kotlin
private suspend fun applyBaselineProposal(
    proposal: BaselineProposal,
    sessionId: Long,
    snapshot: ReplaySnapshot,
    weightUnit: WeightUnit,
    sessionReps: Int,
    minReductionsByMuscle: Map<MuscleGroup, Float>,
    setsByMuscle: Map<MuscleGroup?, List<WorkoutSet>>,
    asOf: Long,
    scratch: MutableDerivedState,
) {
    val current = snapshot.currentBaselines[proposal.muscleGroup] ?: return
    val rounded = WeightFormatter.round(proposal.newBaseline, weightUnit)
    val strength = MuscleGroupStrength(muscleGroup = proposal.muscleGroup, baselineWeight = rounded)
    db.muscleGroupStrengthDao().upsert(strength)
    scratch.upsertMuscleGroupStrength(strength)
    snapshot.progressionBaselines[sessionId to proposal.muscleGroup] = current
    snapshot.currentBaselines[proposal.muscleGroup] = rounded
    val muscleFeedbacks = setsByMuscle[proposal.muscleGroup].orEmpty()
        .mapNotNull { it.feedback }
    val historyRow = BaselineHistory(
        sessionId = sessionId,
        muscleGroup = proposal.muscleGroup,
        previousBaseline = current,
        newBaseline = rounded,
        changeReason = BaselineChangeReason.PROGRESSION,
        feedbacks = muscleFeedbacks.joinToString(",") { it.name }.ifEmpty { null },
        sessionReps = sessionReps,
        minReductionFraction = minReductionsByMuscle[proposal.muscleGroup],
        timestamp = asOf,
        heuristicName = baselineHeuristic.name,
        heuristicMetadata = proposal.metadata,
    )
    db.baselineHistoryDao().insert(historyRow)
    scratch.insertBaselineHistory(historyRow)
    snapshot.baselineHistoryByMuscle.getOrPut(proposal.muscleGroup) { mutableListOf() }
        .add(historyRow)
}
```

- [x] **Update `recomputeCoefficients`** (currently lines 190-210)

```kotlin
private suspend fun recomputeCoefficients(
    snapshot: ReplaySnapshot,
    asOf: Long,
    scratch: MutableDerivedState,
) {
    val heuristic = heuristic ?: return
    val results = heuristic.compute(snapshot.filteredCoefficientInput(asOf))
    if (results.isEmpty()) return
    val latestByExercise = db.coefficientHistoryDao().getLatestPerExercise()
        .associateBy { it.exerciseId }
    for (result in results) {
        val row = CoefficientHistory(
            exerciseId = result.exerciseId,
            previousCoefficient = latestByExercise[result.exerciseId]?.coefficient
                ?: snapshot.seedCoefficients[result.exerciseId],
            coefficient = result.coefficient,
            heuristicName = heuristic.name,
            heuristicMetadata = result.metadata,
            computedAt = asOf,
        )
        db.coefficientHistoryDao().insert(row)
        scratch.insertCoefficientHistory(row)
        snapshot.currentCoefficients[result.exerciseId] = result.coefficient
    }
}
```

- [x] **Update `applyBaselineNormalization`** (currently lines 212-270)

```kotlin
private suspend fun applyBaselineNormalization(
    snapshot: ReplaySnapshot,
    asOf: Long,
    sessionId: Long,
    scratch: MutableDerivedState,
) {
    val normalizer = normalizer ?: return
    val input = snapshot.filteredNormalizationInput(asOf)
    val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
    val threshold = BaselineNormalizationThreshold.forUnit(weightUnit)

    val proposals = normalizer.compute(input)
    if (proposals.isEmpty()) return

    val latestCoefByExercise = db.coefficientHistoryDao().getLatestPerExercise()
        .associateBy { it.exerciseId }
    for (proposal in proposals) {
        val oldBaseline = snapshot.currentBaselines[proposal.muscleGroup] ?: continue
        if (oldBaseline <= 0f || proposal.scale <= 0f) continue
        val rawNew = oldBaseline / proposal.scale
        val newBaseline = WeightFormatter.round(rawNew, weightUnit)
        if (kotlin.math.abs(newBaseline - oldBaseline) < threshold) continue
        if (newBaseline <= 0f) continue
        val mEffective = oldBaseline / newBaseline

        val newStrength = MuscleGroupStrength(muscleGroup = proposal.muscleGroup, baselineWeight = newBaseline)
        db.muscleGroupStrengthDao().upsert(newStrength)
        scratch.upsertMuscleGroupStrength(newStrength)
        snapshot.currentBaselines[proposal.muscleGroup] = newBaseline
        val row = BaselineHistory(
            sessionId = sessionId,
            muscleGroup = proposal.muscleGroup,
            previousBaseline = oldBaseline,
            newBaseline = newBaseline,
            changeReason = BaselineChangeReason.NORMALIZATION,
            timestamp = asOf,
        )
        db.baselineHistoryDao().insert(row)
        scratch.insertBaselineHistory(row)
        snapshot.baselineHistoryByMuscle.getOrPut(proposal.muscleGroup) { mutableListOf() }.add(row)

        val inGroup = input.exercises.filter {
            it.exercise.primaryMuscle == proposal.muscleGroup && it.currentCoefficient > 0f
        }
        for (snap in inGroup) {
            val newCoef = snap.currentCoefficient * mEffective
            val coefRow = CoefficientHistory(
                exerciseId = snap.exercise.id,
                previousCoefficient = latestCoefByExercise[snap.exercise.id]?.coefficient
                    ?: snap.currentCoefficient,
                coefficient = newCoef,
                heuristicName = "baseline_normalization",
                heuristicMetadata = proposal.metadata,
                computedAt = asOf,
            )
            db.coefficientHistoryDao().insert(coefRow)
            scratch.insertCoefficientHistory(coefRow)
            snapshot.currentCoefficients[snap.exercise.id] = newCoef
        }
    }
}
```

Note: add `import io.github.fowles.stochastic_strength.domain.derived.MutableDerivedState` to the file.

### Step 3.3: Build + verify tests

- [x] **Build + unit tests**

```
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: success.

- [x] **Instrumented tests**

```
./gradlew :app:connectedAndroidTest
```

Expected: success. All existing assertions still pass — DAOs remain authoritative. The store now contains the same data.

### Step 3.4: Commit

- [x] **Commit**

```
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt
git commit -m "feat(domain): dual-write derived state to DerivedStateStore"
```

---

## Task 4: Switch all internal `WorkoutRepository` reads to the store

**Goal:** Production reads of the three DAOs inside `WorkoutRepository` go through `derivedState.snapshot()` instead. Tests still read from DAOs at this point.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

### Step 4.1: Switch reads

- [x] **`effectiveCoefficientSource`** (currently lines 37-41)

```kotlin
private fun effectiveCoefficientSource(): UserCoefficientSource {
    val latest = derivedState.snapshot().coefficientHistoryLatestPerExercise()
        .associate { it.exerciseId to it.coefficient }
    return UserCoefficientSource(latest)
}
```

Note: removed `suspend`.

- [x] **`buildPlanner`** (currently line 50)

Change:
```kotlin
val dbStrengths = db.muscleGroupStrengthDao().getAll().associateBy { it.muscleGroup }
```
to:
```kotlin
val dbStrengths = derivedState.snapshot().allMuscleGroupStrengths().associateBy { it.muscleGroup }
```

- [x] **`recomputeCoefficients`** (line 194 inside it)

Change:
```kotlin
val latestByExercise = db.coefficientHistoryDao().getLatestPerExercise()
    .associateBy { it.exerciseId }
```
to:
```kotlin
val latestByExercise = scratch.coefficientHistoryLatestPerExercise()
    .associateBy { it.exerciseId }
```

- [x] **`applyBaselineNormalization`** (line 225 inside it)

Same substitution: read latest from `scratch.coefficientHistoryLatestPerExercise()`.

- [x] **`getMuscleGroupStrengths`** (line 411)

```kotlin
suspend fun getMuscleGroupStrengths(): List<MuscleGroupStrength> =
    derivedState.snapshot().allMuscleGroupStrengths()
```

- [x] **`getRecentCoefficientChanges`** (line 414)

```kotlin
suspend fun getRecentCoefficientChanges(limit: Int = 2): List<CoefficientRow> {
    val rows = derivedState.snapshot().coefficientHistoryMostRecent(limit)
    // ... rest unchanged
}
```

- [x] **`getAllCoefficientRows`** (line 436)

```kotlin
val latestByExercise = derivedState.snapshot().coefficientHistoryLatestPerExercise()
    .associateBy { it.exerciseId }
```

- [x] **`getBaselineEvents`** (line 456)

```kotlin
suspend fun getBaselineEvents(muscleGroup: MuscleGroup): List<BaselineHistory> =
    derivedState.snapshot().baselineHistoryForMuscle(muscleGroup)
```

- [x] **`getCoefficientEvents`** (line 459)

```kotlin
suspend fun getCoefficientEvents(exerciseId: Long): List<CoefficientHistory> =
    derivedState.snapshot().coefficientHistoryForExercise(exerciseId)
```

### Step 4.2: Build + verify tests

- [x] **Build, unit tests, instrumented tests**

```
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:connectedAndroidTest
```

Expected: success. Tests still read from DAOs and still pass because writes still go to both.

### Step 4.3: Commit

- [x] **Commit**

```
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt
git commit -m "refactor(domain): WorkoutRepository reads from DerivedStateStore"
```

---

## Task 5: Switch debug ViewModel reads to the store via the repository

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt:145-147`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt:73-75`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` — add a public accessor

### Step 5.1: Expose a public accessor on `WorkoutRepository`

- [x] **Add a method**

After `getCoefficientEvents` in `WorkoutRepository.kt`:

```kotlin
suspend fun getLatestCoefficientPerExercise(): Map<Long, Float> =
    derivedState.snapshot().coefficientHistoryLatestPerExercise()
        .associate { it.exerciseId to it.coefficient }
```

### Step 5.2: Use it from `MuscleBaselineDetailViewModel`

- [x] **Edit `MuscleBaselineDetailViewModel.kt`** (lines 145-147)

Replace:
```kotlin
val latestUserCoefficients = app.database.coefficientHistoryDao()
    .getLatestPerExercise()
    .associate { it.exerciseId to it.coefficient }
```
with:
```kotlin
val latestUserCoefficients = repository.getLatestCoefficientPerExercise()
```

### Step 5.3: Use it from `ExerciseCoefficientDetailViewModel`

- [x] **Edit `ExerciseCoefficientDetailViewModel.kt`** (lines 73-75)

Replace:
```kotlin
val latestUserCoefficients = app.database.coefficientHistoryDao()
    .getLatestPerExercise()
    .associate { it.exerciseId to it.coefficient }
```
with:
```kotlin
val latestUserCoefficients = repository.getLatestCoefficientPerExercise()
```

### Step 5.4: Build + verify tests

- [x] **Build + tests**

```
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:connectedAndroidTest
```

Expected: success.

### Step 5.5: Commit

- [x] **Commit**

```
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt
git commit -m "refactor(ui): debug VMs read derived state via repository"
```

---

## Task 6: Update tests to read/write through the store

**Goal:** Make tests authoritative on the store, not on DAOs. After this, the DAOs can be removed without test breakage.

**Files:**
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/ReplayDerivedStateTest.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/LiveInputWritesTest.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/DerivedStateBackfillTest.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt`

### Step 6.1: Substitution pattern

For each test file:

- [x] **Replace DAO reads**

| Old | New |
|-----|-----|
| `db.muscleGroupStrengthDao().getAll()` | `repository.derivedState.snapshot().allMuscleGroupStrengths()` |
| `db.muscleGroupStrengthDao().get(muscle)` | `repository.derivedState.snapshot().muscleGroupStrength(muscle)` |
| `db.baselineHistoryDao().getAll()` | `repository.derivedState.snapshot().allBaselineHistory()` |
| `db.baselineHistoryDao().getForMuscle(muscle)` | `repository.derivedState.snapshot().baselineHistoryForMuscle(muscle)` |
| `db.coefficientHistoryDao().getForExercise(id)` | `repository.derivedState.snapshot().coefficientHistoryForExercise(id)` |
| `db.coefficientHistoryDao().getLatestPerExercise()` | `repository.derivedState.snapshot().coefficientHistoryLatestPerExercise()` |
| `db.coefficientHistoryDao().getAll()` | `repository.derivedState.snapshot().allBaselineHistory()` then filter / sort as needed (see test) |

For `coefficientHistoryDao.getAll()`, since the store doesn't expose `all`, build the list as:
```kotlin
repository.derivedState.snapshot().let { snap ->
    // Collect via iteration of all exercise ids the test cares about, or expose a helper.
}
```
If a test genuinely needs every row in computedAt order, add a helper on `Snapshot`:
```kotlin
fun allCoefficientHistory(): List<CoefficientHistory> = coefficientHistory
```
Add this helper to `DerivedStateStore.kt` and a corresponding store unit test.

- [x] **Replace DAO writes (test setup)**

`WorkoutRepositoryDebugTest` and `WorkoutSessionControllerTest` write rows directly to set up test state. These writes belong inside a `rebuild` block. Pattern:

```kotlin
// Before:
db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
db.coefficientHistoryDao().insert(CoefficientHistory(...))

// After:
repository.derivedState.rebuild { mut ->
    mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
    mut.insertCoefficientHistory(CoefficientHistory(...))
}
```

Note: if the test then calls `replayDerivedState`, that will wipe these inserts. Re-read the test to see whether the setup writes are meant to seed the store before a code path under test (in which case use `rebuild`) or simulate already-replayed state (in which case keep DAOs for now and rely on dual-write to keep the store in sync).

In practice: each writer-style setup in these test files is preparing state for the subject under test. Replace with `rebuild`.

### Step 6.2: Edit `ReplayDerivedStateTest.kt`

- [x] **Apply substitution pattern** to lines 57-59, 62-64, 84, 90, 105, 225

Use `repository.derivedState.snapshot()` accessors instead of DAO calls. For the `coefs1`/`coefs2` lists that test idempotency by reading `db.coefficientHistoryDao().getAll()`, use a new `Snapshot.allCoefficientHistory()` helper (add it in step 6.1).

### Step 6.3: Edit `LiveInputWritesTest.kt`

- [x] **Apply substitution pattern** to lines 72, 77

### Step 6.4: Edit `WorkoutRepositoryTest.kt`

- [x] **Apply substitution pattern** to lines 66, 67, 81, 83, 118, 163

### Step 6.5: Edit `WorkoutRepositoryDebugTest.kt`

- [x] **Apply substitution pattern** including the seed-data writes at lines 66, 110, 114, 118, 137, 150, 156, 162, 184, 188, 192

Setup writes become `repository.derivedState.rebuild { mut -> mut.insert*(...) }` blocks. Reads become `repository.derivedState.snapshot().*` calls.

### Step 6.6: Edit `DerivedStateBackfillTest.kt`

- [x] **Apply substitution pattern** to lines 137, 146, 148

### Step 6.7: Edit `WorkoutSessionControllerTest.kt`

- [x] **Apply substitution pattern** to lines 53, 54

These are setup writes:
```kotlin
db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.QUADS, 100f))
```
become:
```kotlin
repository.derivedState.rebuild { mut ->
    mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
    mut.upsertMuscleGroupStrength(MuscleGroupStrength(MuscleGroup.QUADS, 100f))
}
```

### Step 6.8: Build + run instrumented tests

- [x] **Run all instrumented tests**

```
./gradlew :app:connectedAndroidTest
```

Expected: success. All tests now assert against the store.

### Step 6.9: Commit

- [x] **Commit**

```
git add app/src/androidTest/ app/src/main/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStore.kt
git commit -m "test: assert derived state through DerivedStateStore"
```

---

## Task 7: Drop Room writes for derived state

**Goal:** Remove every `db.muscleGroupStrengthDao()`, `db.baselineHistoryDao()`, `db.coefficientHistoryDao()` call from `WorkoutRepository`. The store is now the sole writer.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

### Step 7.1: Delete the DAO writes

- [x] **In `applyBaselineProposal`**

Delete:
```kotlin
db.muscleGroupStrengthDao().upsert(strength)
db.baselineHistoryDao().insert(historyRow)
```

- [x] **In `recomputeCoefficients`**

Delete:
```kotlin
db.coefficientHistoryDao().insert(row)
```

- [x] **In `applyBaselineNormalization`**

Delete:
```kotlin
db.muscleGroupStrengthDao().upsert(newStrength)
db.baselineHistoryDao().insert(row)
db.coefficientHistoryDao().insert(coefRow)
```

- [x] **In `replayDerivedState`**

Delete the three `deleteAll` calls (the store's `rebuild` already discards previous state):
```kotlin
db.baselineHistoryDao().deleteAll()
db.coefficientHistoryDao().deleteAll()
db.muscleGroupStrengthDao().deleteAll()
```

Also delete the `db.muscleGroupStrengthDao().upsert(...)` and `db.baselineHistoryDao().insert(...)` and `db.muscleGroupStrengthDao().upsert(...)` calls in the initials + overrides loops.

### Step 7.2: Build + verify

- [x] **Build, unit, instrumented**

```
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:connectedAndroidTest
```

Expected: success.

### Step 7.3: Commit

- [x] **Commit**

```
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt
git commit -m "refactor(domain): remove Room writes for derived state"
```

---

## Task 8: Drop Room entities, DAOs, and tables; bump schema

**Files:**
- Delete: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/BaselineHistoryDao.kt`
- Delete: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/CoefficientHistoryDao.kt`
- Delete: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/MuscleGroupStrengthDao.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineHistory.kt` — drop `@Entity`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/CoefficientHistory.kt` — drop `@Entity` and `@Index`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/MuscleGroupStrength.kt` — drop `@Entity` and `@PrimaryKey`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt` — bump version, remove entities + DAOs, add migration

### Step 8.1: Strip `@Entity` from the three models

- [x] **`BaselineHistory.kt`**

```kotlin
package io.github.fowles.stochastic_strength.data.model

data class BaselineHistory(
    val id: Long = 0,
    val sessionId: Long?,
    val muscleGroup: MuscleGroup,
    val previousBaseline: Float,
    val newBaseline: Float,
    val changeReason: BaselineChangeReason,
    val feedbacks: String? = null,
    val sessionReps: Int? = null,
    val minReductionFraction: Float? = null,
    val timestamp: Long,
    val heuristicName: String? = null,
    val heuristicMetadata: String? = null,
)
```

- [x] **`CoefficientHistory.kt`**

```kotlin
package io.github.fowles.stochastic_strength.data.model

data class CoefficientHistory(
    val id: Long = 0,
    val exerciseId: Long,
    val previousCoefficient: Float? = null,
    val coefficient: Float,
    val heuristicName: String,
    val heuristicMetadata: String? = null,
    val computedAt: Long,
)
```

- [x] **`MuscleGroupStrength.kt`**

```kotlin
package io.github.fowles.stochastic_strength.data.model

data class MuscleGroupStrength(
    val muscleGroup: MuscleGroup,
    val baselineWeight: Float,
)
```

### Step 8.2: Delete the three DAO files

- [x] **Delete the files**

```
rm app/src/main/java/io/github/fowles/stochastic_strength/data/dao/BaselineHistoryDao.kt
rm app/src/main/java/io/github/fowles/stochastic_strength/data/dao/CoefficientHistoryDao.kt
rm app/src/main/java/io/github/fowles/stochastic_strength/data/dao/MuscleGroupStrengthDao.kt
```

### Step 8.3: Update `AppDatabase.kt`

- [x] **Remove imports** for the three deleted DAOs and the three soon-to-be-non-`@Entity` model classes' DAO + entity registrations.

- [x] **Update the `@Database(entities = ...)` list** — remove `MuscleGroupStrength::class`, `BaselineHistory::class`, `CoefficientHistory::class`.

- [x] **Bump version** to 14:
```kotlin
version = 14,
```

- [x] **Remove abstract DAO accessors** for `muscleGroupStrengthDao()`, `baselineHistoryDao()`, `coefficientHistoryDao()`.

- [x] **Add `MIGRATION_13_14`** inside the companion object, before `MIGRATION_12_13` won't compile since order doesn't matter for `addMigrations`. Place after `MIGRATION_12_13`:

```kotlin
internal val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS muscle_group_strength")
        db.execSQL("DROP TABLE IF EXISTS baseline_history")
        db.execSQL("DROP TABLE IF EXISTS coefficient_history")
    }
}
```

- [x] **Register the migration** in `buildDatabase`:

```kotlin
.addMigrations(
    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
)
```

### Step 8.4: Build + verify

- [x] **Build**

```
./gradlew :app:assembleDebug
```

Expected: success. If something still references one of the deleted DAOs, fix the reference (likely a missed import).

- [x] **Unit tests**

```
./gradlew :app:testDebugUnitTest
```

Expected: success.

- [x] **Instrumented tests**

```
./gradlew :app:connectedAndroidTest
```

Expected: success. Room schema validation is the highest-risk failure here; if it complains about the `13.json` → `14.json` migration, double-check the `DROP TABLE` migration matches what Room expects.

### Step 8.5: Commit

- [x] **Commit**

```
git add app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineHistory.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/data/model/CoefficientHistory.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/data/model/MuscleGroupStrength.kt
git rm app/src/main/java/io/github/fowles/stochastic_strength/data/dao/BaselineHistoryDao.kt \
       app/src/main/java/io/github/fowles/stochastic_strength/data/dao/CoefficientHistoryDao.kt \
       app/src/main/java/io/github/fowles/stochastic_strength/data/dao/MuscleGroupStrengthDao.kt
# include the generated schema JSON change too
git add app/schemas/
git commit -m "refactor(data): drop derived-state tables; schema v13→v14"
```

---

## Task 9: Final verification

### Step 9.1: Full test sweep

- [x] **All tests**

```
./gradlew :app:testDebugUnitTest :app:connectedAndroidTest :app:lint
```

Expected: success.

### Step 9.2: Manual smoke test

- [x] **Cold start the app** on the connected emulator.

- [x] **Open the debug muscle baseline detail screen** — confirm chart points and event list render.

- [x] **Open the debug exercise coefficient detail screen** — confirm chart points and event list render.

- [x] **Finish a workout session** — confirm progression applies and the summary screen renders the updated baselines.

### Step 9.3: Done — no separate commit; the work is integrated.

---

## Notes for the implementer

- **Why `Mutex` and not `synchronized`?** Suspending APIs and `db.withTransaction` participate in coroutine cancellation; `kotlinx.coroutines.sync.Mutex` plays well with that. The store's contention is rare (only during replay), so the cost is negligible.
- **Why not Flow?** No reader subscribes reactively today (we verified). If a future reader needs reactivity, the store can grow a `StateFlow<Snapshot>` without breaking existing callers.
- **`scratch` parameter threading is ugly** — it's a temporary state for tasks 3–7. In task 7, after DAO writes are gone, we could consider folding the rebuild logic to live entirely on the store and shrinking the function signatures, but that's a follow-up cleanup, not part of this plan.
- **`derivedState` accessor is public** because tests and debug ViewModels read it. This is intentional. The `Snapshot` class is read-only by construction.
- **Application restart side-effect**: after the schema migration, the three tables are dropped from disk and the store is built from scratch via `DerivedStateBackfill.run()`. Until that coroutine completes (asynchronously, on `applicationScope`), readers see an empty `Snapshot`. This already matches the current behavior — pre-task-1, the three tables were also wiped at app start and only re-populated after the same async backfill.
