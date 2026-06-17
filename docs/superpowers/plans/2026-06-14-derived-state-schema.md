# Replay-Based Derived State Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make derived workout state (baselines, coefficients, history tables) a pure, idempotent function of recorded inputs. Replace the version-gated `DerivedStateBackfill` with a wipe-and-replay routine that runs safely from any state, and clean up the schema so inputs and derived state are properly separated.

**Architecture:** Schema gets reshaped to make `baseline_override` and `exercise_hurt_state` user-input tables; `baseline_history` and `coefficient_history` are pure derived rebuild targets. A `ReplaySnapshot` holds the in-memory replay state (DB read once at the top, dynamic maps updated as the loop walks sessions). `replayDerivedState()` wipes derived tables and walks every completed session in `(endTime, id)` order, calling a snapshot-aware `applySessionProgression(sessionId, snapshot, asOf)` for each. The launch-time backfill and the session-end pipeline both route through `replayDerivedState`.

**Tech Stack:** Kotlin, Jetpack Compose, Room (v12, all-SQL migration), JUnit4, `androidx.room.testing.MigrationTestHelper` for instrumented migration tests, jj for version control.

**Spec:** [`docs/superpowers/specs/2026-06-14-derived-state-schema-design.md`](../specs/2026-06-14-derived-state-schema-design.md)

**Commit convention:** This project uses jj. Each task's "commit" step is `jj describe -m "..." && jj new` — `jj describe` sets the message on the current working-copy change; `jj new` starts a fresh empty change for the next task. The working copy already snapshots automatically, so there is no separate `add` step. Do not use `git commit`.

**Build/test cheat sheet:**
- Unit test single class: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.<ClassName>"`
- All unit tests: `./gradlew :app:testDebugUnitTest`
- Instrumented test single class: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.<package>.<ClassName>"` (emulator must be running; typically already is on this dev box)
- Full build: `./gradlew :app:assembleDebug`

---

## Phase 1: Heuristic Clock Parameterization

### Task 1: `EstCoefConsensusHeuristic` derives "now" from input

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristic.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/EstCoefConsensusHeuristicTest.kt` (existing; add a new case)

- [x] **Step 1: Write the failing test**

Add this test to `EstCoefConsensusHeuristicTest`:

```kotlin
@Test
fun `compute uses max sessionTime from input as now, not wall clock`() {
    // Two sessions: one "old" (10 days ago relative to input's max) and one "new" (the max).
    // If the heuristic correctly uses max sessionTime as now, the older session gets
    // exp(-10d * ln2 / 14d) ≈ 0.61 recency. If it incorrectly uses wall clock,
    // both sessions look ancient and get near-zero recency, the totalWeight test below fails.
    val newT = 1_700_000_000_000L
    val oldT = newT - 10L * 24 * 60 * 60 * 1000

    val sets = listOf(
        WorkoutSet(id = 1, sessionId = 1, exerciseId = 100, setNumber = 1,
            targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = oldT, actualReps = 5),
        WorkoutSet(id = 2, sessionId = 2, exerciseId = 100, setNumber = 1,
            targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_2_4,
            completedAt = newT, actualReps = 5),
    )
    val input = CoefficientComputationInput(
        sets = sets,
        sessionTimes = mapOf(1L to oldT, 2L to newT),
        exerciseMuscle = mapOf(100L to MuscleGroup.CHEST),
        baselines = mapOf((1L to MuscleGroup.CHEST) to 100f, (2L to MuscleGroup.CHEST) to 100f),
        currentCoefficients = mapOf(100L to 1.0f),
    )

    val results = EstCoefConsensusHeuristic().compute(input)

    // With now = newT, totalWeight ≈ 1.0 (new session) + 0.61 (old session) > minEvidenceWeight=1.5.
    // With wall-clock now (= System.currentTimeMillis(), years past newT), both weights ≈ 0,
    // totalWeight < minEvidenceWeight, results would be empty.
    assertTrue("expected at least one result; was empty (heuristic likely using wall clock)", results.isNotEmpty())
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest.compute uses max sessionTime from input as now, not wall clock"`
Expected: FAIL — `results.isEmpty()` because default `now = System::currentTimeMillis` gives ancient sessions weight 0.

- [x] **Step 3: Implement**

Edit `EstCoefConsensusHeuristic.kt`:

1. Remove `now: () -> Long = System::currentTimeMillis` from the constructor parameter list.
2. In `computeH1`, replace `val nowT = now()` with `val nowT = signals.maxOf { it.sessionTime }`.
3. Remove the `now` field from the class (no longer needed).

The signature should become:

```kotlin
class EstCoefConsensusHeuristic(
    private val tauHalfMs: Long = 14L * 24 * 60 * 60 * 1000,
    private val minEvidenceWeight: Float = 1.5f,
    private val minOutlierSessions: Int = 2,
    private val tauConsensusThreshold: Float = ln(1.05f),
    private val tauOutlierThreshold: Float = LN_110,
    private val alpha: Float = 0.2f,
    private val maxLogStep: Float = ln(1.05f),
    private val minRelativeChange: Float = 0.005f,
) : CoefficientHeuristic {
```

And in `computeH1`:

```kotlin
internal fun computeH1(signals: List<SessionSignal>): H1Proposal? {
    if (signals.isEmpty()) return null
    val nowT = signals.maxOf { it.sessionTime }
    // ... rest unchanged
}
```

- [x] **Step 4: Run all heuristic tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.EstCoefConsensusHeuristicTest"`
Expected: PASS for all tests (including any existing ones that previously injected a custom `now` — they should still work as long as their session times are recent relative to each other; if any test fails because it was using a `now` that drifted from session times, fix that test to put session times near its desired `now`).

- [x] **Step 5: Check for callers**

Run: `grep -rn "EstCoefConsensusHeuristic(" app/src --include="*.kt"`
Expected: any caller that passed `now =` must be updated. There should be a use in `StochasticStrengthApp.kt` and possibly in tests. Remove the `now =` argument from each call. Run the unit-test suite again to confirm nothing else broke:

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS

- [x] **Step 6: Commit**

```bash
jj describe -m "feat(domain): EstCoefConsensusHeuristic derives now from input"
jj new
```

---

## Phase 2: ReplaySnapshot Domain Class

### Task 2: Create `ReplaySnapshot` with static fields, dynamic maps, and filter methods

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ReplaySnapshot.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ReplaySnapshotTest.kt`

- [x] **Step 1: Write the failing test**

Create the test file:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplaySnapshotTest {

    @Test
    fun `filteredCoefficientInput drops sessions and sets newer than asOf`() {
        val snapshot = ReplaySnapshot(
            allSets = listOf(
                set(id = 1, sessionId = 1, completedAt = 100),
                set(id = 2, sessionId = 2, completedAt = 200),
                set(id = 3, sessionId = 3, completedAt = 300),
            ),
            allSessionTimes = mapOf(1L to 100L, 2L to 200L, 3L to 300L),
            exerciseMuscle = mapOf(100L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(100L to 1.0f),
        )
        snapshot.currentCoefficients[100L] = 1.05f
        snapshot.progressionBaselines[1L to MuscleGroup.CHEST] = 100f
        snapshot.progressionBaselines[2L to MuscleGroup.CHEST] = 102f

        val filtered = snapshot.filteredCoefficientInput(asOf = 200L)

        assertEquals(setOf(1L, 2L), filtered.sessionTimes.keys)
        assertEquals(listOf(1L, 2L), filtered.sets.map { it.id })
        assertEquals(1.05f, filtered.currentCoefficients[100L])
        assertEquals(100f, filtered.baselines[1L to MuscleGroup.CHEST])
    }

    @Test
    fun `filteredCoefficientInput includes set with null completedAt when its session is included`() {
        val snapshot = ReplaySnapshot(
            allSets = listOf(set(id = 1, sessionId = 1, completedAt = null)),
            allSessionTimes = mapOf(1L to 100L),
            exerciseMuscle = mapOf(100L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(100L to 1.0f),
        )

        val filtered = snapshot.filteredCoefficientInput(asOf = 200L)

        assertEquals(1, filtered.sets.size)
    }

    @Test
    fun `filteredCoefficientInput excludes set with null completedAt when its session is excluded`() {
        val snapshot = ReplaySnapshot(
            allSets = listOf(set(id = 1, sessionId = 1, completedAt = null)),
            allSessionTimes = mapOf(1L to 500L),
            exerciseMuscle = mapOf(100L to MuscleGroup.CHEST),
            seedCoefficients = mapOf(100L to 1.0f),
        )

        val filtered = snapshot.filteredCoefficientInput(asOf = 200L)

        assertTrue(filtered.sets.isEmpty())
    }

    private fun set(id: Long, sessionId: Long, completedAt: Long?): WorkoutSet =
        WorkoutSet(
            id = id,
            sessionId = sessionId,
            exerciseId = 100,
            setNumber = 1,
            targetWeight = 100f,
            targetReps = 5,
            actualReps = 5,
            feedback = SetFeedback.RIR_2_4,
            completedAt = completedAt,
            durationSeconds = null,
        )
}
```

- [x] **Step 2: Run test to verify it fails to compile**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ReplaySnapshotTest"`
Expected: FAIL (compile error: `ReplaySnapshot` does not exist).

- [x] **Step 3: Implement `ReplaySnapshot`**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/ReplaySnapshot.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

/**
 * Holds the inputs and evolving derived state for one full replay of [WorkoutRepository.replayDerivedState].
 *
 * Static fields are loaded once from the DB at the top of replay. Dynamic maps are mutated as each
 * session's step writes its derived rows. The filter methods produce per-session inputs without
 * re-reading the DB.
 */
class ReplaySnapshot(
    val allSets: List<WorkoutSet>,
    val allSessionTimes: Map<Long, Long>,
    val exerciseMuscle: Map<Long, MuscleGroup>,
    val seedCoefficients: Map<Long, Float>,
    val allExercises: List<Exercise> = emptyList(),
) {
    val currentCoefficients: MutableMap<Long, Float> = seedCoefficients.toMutableMap()
    val currentBaselines: MutableMap<MuscleGroup, Float> = mutableMapOf()
    val progressionBaselines: MutableMap<Pair<Long, MuscleGroup>, Float> = mutableMapOf()

    fun filteredCoefficientInput(asOf: Long): CoefficientComputationInput {
        val sessionTimes = allSessionTimes.filterValues { it <= asOf }
        val sets = allSets.filter { set ->
            val ca = set.completedAt
            if (ca != null) ca <= asOf else set.sessionId in sessionTimes
        }
        return CoefficientComputationInput(
            sets = sets,
            sessionTimes = sessionTimes,
            exerciseMuscle = exerciseMuscle,
            baselines = progressionBaselines.toMap(),
            currentCoefficients = currentCoefficients.toMap(),
        )
    }

    fun filteredNormalizationInput(asOf: Long): BaselineNormalizationInput {
        val sessionTimes = allSessionTimes.filterValues { it <= asOf }
        val sets = allSets.filter { set ->
            val ca = set.completedAt
            if (ca != null) ca <= asOf else set.sessionId in sessionTimes
        }
        val snapshots = allExercises.map { ex ->
            val seed = seedCoefficients[ex.id] ?: 0f
            val current = currentCoefficients[ex.id] ?: seed
            ExerciseCoefficientSnapshot(ex, seed, current)
        }
        return BaselineNormalizationInput(
            sets = sets,
            exercises = snapshots,
            baselines = currentBaselines.toMap(),
        )
    }
}
```

- [x] **Step 4: Run test to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ReplaySnapshotTest"`
Expected: PASS for all three tests.

- [x] **Step 5: Commit**

```bash
jj describe -m "feat(domain): add ReplaySnapshot with per-asOf input filters"
jj new
```

---

## Phase 3: Schema Overhaul

> Phase 3 is one logical unit: Room compilation requires entity/DAO/migration consistency, so the changes in Tasks 3–9 commit together at the end of Task 9. Tasks 3–8 build code; Task 9 wires it into `AppDatabase`, rewrites the migration, regenerates the v12 schema JSON, and brings the project back to green. Migration tests in Task 10 validate the end state.
>
> Do **not** run `assembleDebug` until Task 9 — intermediate states will not compile.

### Task 3: New `BaselineOverride` entity + DAO

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineOverride.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/BaselineOverrideDao.kt`

- [x] **Step 1: Create the entity**

```kotlin
// BaselineOverride.kt
package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * User-authored baseline adjustment.
 *
 * - `sessionId = null` means the *initial* baseline for [muscleGroup], used as the replay
 *   starting point. At most one such row per muscle.
 * - `sessionId = N` means the user manually adjusted the baseline at session N. At most one
 *   row per (sessionId, muscleGroup) pair.
 */
@Entity(tableName = "baseline_override")
data class BaselineOverride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long? = null,
    val muscleGroup: MuscleGroup,
    val baselineWeight: Float,
    val asOf: Long,
)
```

- [x] **Step 2: Create the DAO**

```kotlin
// BaselineOverrideDao.kt
package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup

@Dao
interface BaselineOverrideDao {

    @Query("SELECT * FROM baseline_override WHERE sessionId IS NULL")
    suspend fun getInitials(): List<BaselineOverride>

    @Query("SELECT * FROM baseline_override WHERE sessionId IS NOT NULL")
    suspend fun getNonInitials(): List<BaselineOverride>

    @Query("SELECT * FROM baseline_override WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: Long): List<BaselineOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(override: BaselineOverride): Long

    @Query("DELETE FROM baseline_override WHERE sessionId IS NULL AND muscleGroup = :muscleGroup")
    suspend fun deleteInitialFor(muscleGroup: MuscleGroup)

    @Query("DELETE FROM baseline_override WHERE sessionId = :sessionId AND muscleGroup = :muscleGroup")
    suspend fun deleteForSession(sessionId: Long, muscleGroup: MuscleGroup)
}
```

(No test here yet — this is exercised through Room, validated in Task 10's migration tests and Task 15's replay tests.)

### Task 4: New `ExerciseHurtState` entity + DAO

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/ExerciseHurtState.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/ExerciseHurtStateDao.kt`

- [x] **Step 1: Create the entity**

```kotlin
// ExerciseHurtState.kt
package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pure input — never written by replay.
 *
 * A row is present iff the exercise has been marked at least once. `isHurt` reflects the current
 * state (`true` = marked, `false` = explicitly cleared). The absence of a row is read identically
 * to `isHurt = false`. `asOf` is the timestamp of the most recent state change.
 */
@Entity(tableName = "exercise_hurt_state")
data class ExerciseHurtState(
    @PrimaryKey val exerciseId: Long,
    val isHurt: Boolean,
    val asOf: Long,
)
```

- [x] **Step 2: Create the DAO**

```kotlin
// ExerciseHurtStateDao.kt
package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.ExerciseHurtState
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseHurtStateDao {

    @Query("SELECT * FROM exercise_hurt_state WHERE exerciseId = :exerciseId")
    suspend fun get(exerciseId: Long): ExerciseHurtState?

    @Query("SELECT * FROM exercise_hurt_state")
    suspend fun getAll(): List<ExerciseHurtState>

    @Query("SELECT * FROM exercise_hurt_state")
    fun observeAll(): Flow<List<ExerciseHurtState>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ExerciseHurtState)
}
```

### Task 5: Rename `BaselineChangeLog` → `BaselineHistory`; expand the enum

**Files:**
- Rename / modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeLog.kt` → `BaselineHistory.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeReason.kt` (or wherever the enum lives — search if unsure)
- Rename / modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/BaselineChangeLogDao.kt` → `BaselineHistoryDao.kt`

- [x] **Step 1: Locate the enum**

Run: `grep -rn "enum class BaselineChangeReason\|BaselineChangeReason {" app/src/main --include="*.kt"`
Note the file path. Open it.

- [x] **Step 2: Update the enum**

The current enum is `BaselineChangeReason { PROGRESSION, MANUAL_OVERRIDE, NORMALIZATION }`. Change to:

```kotlin
enum class BaselineChangeReason {
    INITIAL,
    OVERRIDE,
    PROGRESSION,
    NORMALIZATION,
}
```

`MANUAL_OVERRIDE` is removed (its rows migrate to `baseline_override` in the migration); `INITIAL` and `OVERRIDE` are added.

- [x] **Step 3: Rename the entity file and class**

Delete `BaselineChangeLog.kt`. Create `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineHistory.kt`:

```kotlin
package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "baseline_history")
data class BaselineHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long?,
    val muscleGroup: MuscleGroup,
    val previousBaseline: Float,
    val newBaseline: Float,
    val changeReason: BaselineChangeReason,
    val feedbacks: String? = null,
    val sessionReps: Int? = null,
    val minReductionFraction: Float? = null,
    val timestamp: Long,
)
```

Note: `sessionId` becomes nullable to accommodate `INITIAL` rows (which have no session).

- [x] **Step 4: Rename and update the DAO**

Delete `BaselineChangeLogDao.kt`. Create `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/BaselineHistoryDao.kt`. Mirror every existing method from `BaselineChangeLogDao`, but with the type and table name updated. At minimum it needs:

```kotlin
package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineHistory
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface BaselineHistoryDao {

    @Query("SELECT * FROM baseline_history ORDER BY timestamp ASC")
    suspend fun getAll(): List<BaselineHistory>

    @Query("SELECT * FROM baseline_history WHERE muscleGroup = :muscleGroup ORDER BY timestamp ASC")
    suspend fun getForMuscle(muscleGroup: MuscleGroup): List<BaselineHistory>

    @Query("SELECT * FROM baseline_history ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<BaselineHistory>>

    @Insert
    suspend fun insert(row: BaselineHistory): Long

    @Query("DELETE FROM baseline_history")
    suspend fun deleteAll()

    @Query("DELETE FROM baseline_history WHERE changeReason IN ('PROGRESSION','NORMALIZATION','INITIAL','OVERRIDE')")
    suspend fun deleteDerived()
    // (deleteDerived is equivalent to deleteAll today since all reasons are derived;
    // kept as a named method for clarity in case future reasons are inputs.)
}
```

Cross-check with the original `BaselineChangeLogDao` for any methods you missed and bring them over with renamed return types.

- [x] **Step 5: Update all references in the codebase**

```bash
grep -rln "BaselineChangeLog\b" app/src --include="*.kt"
```

For each match, replace `BaselineChangeLog` with `BaselineHistory` and `BaselineChangeLogDao` with `BaselineHistoryDao`. Also update method calls:
- `db.baselineChangeLogDao()` → `db.baselineHistoryDao()`

Don't worry about getting the build green here — that happens in Task 9.

### Task 6: Rename `CoefficientChangeLog` → `CoefficientHistory`

**Files:**
- Rename / modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/CoefficientChangeLog.kt` → `CoefficientHistory.kt`
- Rename / modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/CoefficientChangeLogDao.kt` → `CoefficientHistoryDao.kt`

- [x] **Step 1: Rename the entity**

Delete `CoefficientChangeLog.kt`. Create `app/src/main/java/io/github/fowles/stochastic_strength/data/model/CoefficientHistory.kt`:

```kotlin
package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "coefficient_history",
    indices = [Index("exerciseId")],
)
data class CoefficientHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val previousCoefficient: Float? = null,
    val coefficient: Float,
    val heuristicName: String,
    val heuristicMetadata: String? = null,
    val computedAt: Long,
)
```

- [x] **Step 2: Rename and update the DAO**

Delete `CoefficientChangeLogDao.kt`. Create `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/CoefficientHistoryDao.kt` by mirroring every method from the original DAO, replacing types and the table name. At minimum:

```kotlin
package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.CoefficientHistory

@Dao
interface CoefficientHistoryDao {

    @Query("SELECT * FROM coefficient_history WHERE exerciseId = :exerciseId ORDER BY computedAt ASC")
    suspend fun getForExercise(exerciseId: Long): List<CoefficientHistory>

    @Query(
        "SELECT * FROM coefficient_history c " +
            "WHERE c.computedAt = (SELECT MAX(c2.computedAt) FROM coefficient_history c2 WHERE c2.exerciseId = c.exerciseId)"
    )
    suspend fun getLatestPerExercise(): List<CoefficientHistory>

    @Query("SELECT * FROM coefficient_history ORDER BY computedAt ASC")
    suspend fun getAll(): List<CoefficientHistory>

    @Insert
    suspend fun insert(row: CoefficientHistory): Long

    @Query("DELETE FROM coefficient_history")
    suspend fun deleteAll()
}
```

Cross-check with the original `CoefficientChangeLogDao` and bring over any methods missed.

- [x] **Step 3: Update all references**

```bash
grep -rln "CoefficientChangeLog\b" app/src --include="*.kt"
```

For each match, replace `CoefficientChangeLog` with `CoefficientHistory` and `CoefficientChangeLogDao` with `CoefficientHistoryDao`. Update `db.coefficientChangeLogDao()` → `db.coefficientHistoryDao()`.

### Task 7: Drop `Exercise.hurtFlag` field; drop `UserProfile.derivedStateVersion`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/Exercise.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/UserProfile.kt`

- [x] **Step 1: Drop hurtFlag from Exercise**

```kotlin
// Exercise.kt
@Entity(tableName = "exercises")
data class Exercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val primaryMuscle: MuscleGroup,
    val secondaryMuscles: List<MuscleGroup> = emptyList(),
    val equipment: Equipment,
    val isDisliked: Boolean = false,
    val isUnilateral: Boolean = false,
    val isTimed: Boolean = false,
)
```

- [x] **Step 2: Drop derivedStateVersion from UserProfile**

```kotlin
// UserProfile.kt
@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Long = 1,
    val sex: Sex,
    val strengthLevel: StrengthLevel,
    val weightUnit: WeightUnit,
    val preferredExerciseCount: Int? = null,
)
```

- [x] **Step 3: Adjust readers temporarily**

This will break two clusters of callers:
- Anything reading `exercise.hurtFlag` (ExercisesScreen, ExerciseDetailScreen, ExerciseDetailViewModel) — leave those broken for now; Phase 5 reroutes them to `exercise_hurt_state`.
- Anything in `WorkoutRepository.applySessionProgression` that sets `hurtFlag = true` — delete that block (lines 79–84 in the current file). The functionality moves to live recording in Phase 5.
- `DerivedStateBackfill` reads `profile.derivedStateVersion` — that whole class is rewritten in Task 20; for now just remove the read (its compile errors are fine to surface).

For Phase 3 to leave the project in a buildable state at the end of Task 9, you may need to *temporarily* stub UI reads to compile. The cleanest interim: change `exercise.hurtFlag` reads to `false` literals in the four UI sites so the build stays green; Phase 5 wires them up properly. Note each stub site with `// TEMP: replaced by exercise_hurt_state wiring in Phase 5` to make Phase 5 cleanup easy.

### Task 8: New `AppDatabase` wiring + rewritten `MIGRATION_11_12` + regenerated v12 schema JSON

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt`
- Delete + regenerate: `app/schemas/io.github.fowles.stochastic_strength.data.AppDatabase/12.json`

- [x] **Step 1: Discover the v11 `exercises` column list**

Open `app/schemas/io.github.fowles.stochastic_strength.data.AppDatabase/11.json` and find the `exercises` table's `fields` array. Note column names + Room types + nullability + defaults verbatim. You will need this for the recreate-table SQL in Step 4.

- [x] **Step 2: Update `AppDatabase` entities list and abstract DAO accessors**

In `AppDatabase.kt`:

```kotlin
@Database(
    entities = [
        Exercise::class,
        KnownLocation::class,
        LocationExcludedExercise::class,
        WorkoutSession::class,
        WorkoutSet::class,
        UserProfile::class,
        MuscleGroupStrength::class,
        BaselineHistory::class,
        CoefficientHistory::class,
        BaselineOverride::class,
        ExerciseHurtState::class,
    ],
    version = 12,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun knownLocationDao(): KnownLocationDao
    abstract fun locationExcludedExerciseDao(): LocationExcludedExerciseDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun workoutSetDao(): WorkoutSetDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun muscleGroupStrengthDao(): MuscleGroupStrengthDao
    abstract fun baselineHistoryDao(): BaselineHistoryDao
    abstract fun coefficientHistoryDao(): CoefficientHistoryDao
    abstract fun baselineOverrideDao(): BaselineOverrideDao
    abstract fun exerciseHurtStateDao(): ExerciseHurtStateDao
    // ...
}
```

Don't forget to add the new imports for the new entity / DAO classes.

- [x] **Step 3: Delete the existing v12 schema JSON**

```bash
rm app/schemas/io.github.fowles.stochastic_strength.data.AppDatabase/12.json
```

Room will regenerate it on the next build.

- [x] **Step 4: Replace `MIGRATION_11_12`**

Replace the existing `MIGRATION_11_12` definition in `AppDatabase.kt` with the SQL from the spec's "Migration v11 → v12" section, expanded with the exact `exercises` column list you collected in Step 1. The full SQL is reproduced here for convenience — adapt the `exercises_new` column list and the `INSERT INTO exercises_new` SELECT clause to match the v11 schema exactly:

```kotlin
internal val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. user_profile: drop actualRepsBackfilled (v11 added it), recreate-table.
        db.execSQL("""
            CREATE TABLE `user_profile_new` (
                `id` INTEGER NOT NULL,
                `sex` TEXT NOT NULL,
                `strengthLevel` TEXT NOT NULL,
                `weightUnit` TEXT NOT NULL,
                `preferredExerciseCount` INTEGER,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO user_profile_new (id, sex, strengthLevel, weightUnit, preferredExerciseCount)
                SELECT id, sex, strengthLevel, weightUnit, preferredExerciseCount FROM user_profile
        """.trimIndent())
        db.execSQL("DROP TABLE user_profile")
        db.execSQL("ALTER TABLE user_profile_new RENAME TO user_profile")

        // 2. baseline_override (new input table).
        db.execSQL("""
            CREATE TABLE `baseline_override` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER,
                `muscleGroup` TEXT NOT NULL,
                `baselineWeight` REAL NOT NULL,
                `asOf` INTEGER NOT NULL
            )
        """.trimIndent())

        // 3. Move MANUAL_OVERRIDE rows from baseline_change_log to baseline_override.
        db.execSQL("""
            INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf)
                SELECT sessionId, muscleGroup, newBaseline, timestamp
                FROM baseline_change_log
                WHERE changeReason = 'MANUAL_OVERRIDE'
        """.trimIndent())

        // 4. Synthesize initial baselines per muscle.
        db.execSQL("""
            INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf)
                SELECT NULL, b1.muscleGroup, b1.previousBaseline, 0
                FROM baseline_change_log b1
                WHERE b1.id = (
                    SELECT MIN(b2.id) FROM baseline_change_log b2
                    WHERE b2.muscleGroup = b1.muscleGroup
                )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf)
                SELECT NULL, muscleGroup, baselineWeight, 0
                FROM muscle_group_strength
                WHERE muscleGroup NOT IN (SELECT DISTINCT muscleGroup FROM baseline_change_log)
        """.trimIndent())

        // 5. exercise_hurt_state (new input table).
        db.execSQL("""
            CREATE TABLE `exercise_hurt_state` (
                `exerciseId` INTEGER PRIMARY KEY NOT NULL,
                `isHurt` INTEGER NOT NULL,
                `asOf` INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO exercise_hurt_state (exerciseId, isHurt, asOf)
                SELECT id, hurtFlag, 0 FROM exercises WHERE hurtFlag = 1
        """.trimIndent())

        // 6. exercises: drop hurtFlag (recreate-table). FILL IN the column list from v11.
        db.execSQL("""
            CREATE TABLE `exercises_new` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `name` TEXT NOT NULL,
                `primaryMuscle` TEXT NOT NULL,
                `secondaryMuscles` TEXT NOT NULL,
                `equipment` TEXT NOT NULL,
                `isDisliked` INTEGER NOT NULL DEFAULT 0,
                `isUnilateral` INTEGER NOT NULL DEFAULT 0,
                `isTimed` INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO exercises_new (id, name, primaryMuscle, secondaryMuscles, equipment, isDisliked, isUnilateral, isTimed)
                SELECT id, name, primaryMuscle, secondaryMuscles, equipment, isDisliked, isUnilateral, isTimed FROM exercises
        """.trimIndent())
        db.execSQL("DROP TABLE exercises")
        db.execSQL("ALTER TABLE exercises_new RENAME TO exercises")

        // 7. Remove migrated MANUAL_OVERRIDE rows from the (about-to-be-renamed) history.
        db.execSQL("DELETE FROM baseline_change_log WHERE changeReason = 'MANUAL_OVERRIDE'")

        // 8. Rename derived-state tables.
        db.execSQL("ALTER TABLE baseline_change_log RENAME TO baseline_history")
        db.execSQL("ALTER TABLE coefficient_change_log RENAME TO coefficient_history")
    }
}
```

**Verify the `exercises_new` column list matches what you noted in Step 1.** If v11 has extra columns (e.g., `seedCoefficient`), add them in both the `CREATE TABLE` and the `INSERT … SELECT`. The list in this snippet is a best-guess; the source of truth is `11.json`.

- [x] **Step 5: Run a full build**

Run: `./gradlew :app:assembleDebug`
Expected: PASS. Build failures here usually indicate:
- A column mismatch in the recreate-table SQL — re-check against `11.json`.
- A reference to the old `BaselineChangeLog` / `CoefficientChangeLog` / `hurtFlag` / `derivedStateVersion` you missed. Search and update.
- The regenerated `12.json` will appear; if its `exercises` columns don't match the recreate-table SQL, Room will fail validation at runtime. Open the new `12.json` and confirm the `exercises` `fields` array matches the recreated columns.

### Task 9: Commit Phase 3

- [x] **Step 1: Sanity-check what's about to commit**

```bash
jj diff --stat
```

Expected: changes across data/model (renames + new entities + dropped fields), data/dao (renames + new DAOs), AppDatabase (entities list + abstract methods + migration body), `app/schemas/.../12.json` regenerated.

- [x] **Step 2: Commit**

```bash
jj describe -m "feat(data): rewrite v11→v12 schema for input/derived separation"
jj new
```

### Task 10: Migration tests

**Files:**
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/data/MigrationTest.kt`

The existing `migrate11To12_*` tests assert the *old* end state. Replace them with these. (Other migration tests in the file — for 9→10, 10→11, etc. — stay as they are.)

- [x] **Step 1: Replace the v11→v12 test cluster**

Inside `MigrationTest`, remove every test method whose name begins with `migrate11To12_`. Add these in their place. Adjust column names in seed-data SQL to match the v11 `user_profile` and `exercises` schemas exactly (column list comes from `11.json`).

```kotlin
@Test
fun migrate11To12_dropsActualRepsBackfilledFromUserProfile() {
    helper.createDatabase(TEST_DB, 11).use { db ->
        db.execSQL("""
            INSERT INTO user_profile
                (id, sex, strengthLevel, weightUnit, preferredExerciseCount, actualRepsBackfilled)
                VALUES (1, 'MALE', 'NOVICE', 'KG', NULL, 1)
        """.trimIndent())
    }
    val migrated = helper.runMigrationsAndValidate(TEST_DB, 12, true, AppDatabase.MIGRATION_11_12)
    migrated.query("PRAGMA table_info(user_profile)").use { c ->
        val names = mutableListOf<String>()
        while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
        assertFalse(names.contains("actualRepsBackfilled"))
        assertFalse(names.contains("derivedStateVersion"))
    }
}

@Test
fun migrate11To12_movesManualOverrideRowsIntoBaselineOverride() {
    helper.createDatabase(TEST_DB, 11).use { db ->
        // Insert a workout_session row referenced by the override (sessionId=7).
        db.execSQL("""
            INSERT INTO workout_sessions (id, startTime, endTime)
                VALUES (7, 1700000000000, 1700000010000)
        """.trimIndent())
        db.execSQL("""
            INSERT INTO baseline_change_log
                (id, sessionId, muscleGroup, previousBaseline, newBaseline, changeReason, feedbacks, sessionReps, minReductionFraction, timestamp)
                VALUES (1, 7, 'CHEST', 80.0, 82.5, 'MANUAL_OVERRIDE', NULL, NULL, NULL, 1700000005000)
        """.trimIndent())
    }
    val migrated = helper.runMigrationsAndValidate(TEST_DB, 12, true, AppDatabase.MIGRATION_11_12)
    migrated.query("SELECT sessionId, muscleGroup, baselineWeight, asOf FROM baseline_override").use { c ->
        val rows = mutableListOf<List<Any?>>()
        while (c.moveToNext()) rows += listOf(
            if (c.isNull(0)) null else c.getLong(0),
            c.getString(1),
            c.getFloat(2),
            c.getLong(3),
        )
        assertTrue("expected the MANUAL_OVERRIDE row migrated; rows=$rows",
            rows.any { it[0] == 7L && it[1] == "CHEST" && it[2] == 82.5f })
    }
    migrated.query(
        "SELECT COUNT(*) FROM baseline_history WHERE changeReason = 'MANUAL_OVERRIDE'"
    ).use { c ->
        c.moveToFirst()
        assertEquals(0, c.getInt(0))
    }
}

@Test
fun migrate11To12_synthesizesInitialBaselineFromEarliestLogPreviousBaseline() {
    helper.createDatabase(TEST_DB, 11).use { db ->
        db.execSQL("""
            INSERT INTO workout_sessions (id, startTime, endTime)
                VALUES (1, 1, 2), (2, 3, 4)
        """.trimIndent())
        db.execSQL("""
            INSERT INTO baseline_change_log
                (id, sessionId, muscleGroup, previousBaseline, newBaseline, changeReason, feedbacks, sessionReps, minReductionFraction, timestamp)
                VALUES
                    (10, 1, 'CHEST', 50.0, 52.5, 'PROGRESSION', 'RIR_2_4', 5, NULL, 1),
                    (20, 2, 'CHEST', 52.5, 55.0, 'PROGRESSION', 'RIR_2_4', 5, NULL, 3)
        """.trimIndent())
    }
    val migrated = helper.runMigrationsAndValidate(TEST_DB, 12, true, AppDatabase.MIGRATION_11_12)
    migrated.query(
        "SELECT baselineWeight FROM baseline_override WHERE sessionId IS NULL AND muscleGroup = 'CHEST'"
    ).use { c ->
        assertTrue(c.moveToFirst())
        assertEquals(50.0f, c.getFloat(0))
    }
}

@Test
fun migrate11To12_copiesCurrentBaselineForMuscleWithNoLog() {
    helper.createDatabase(TEST_DB, 11).use { db ->
        db.execSQL("""
            INSERT INTO muscle_group_strength (muscleGroup, baselineWeight)
                VALUES ('TRICEPS', 30.0)
        """.trimIndent())
    }
    val migrated = helper.runMigrationsAndValidate(TEST_DB, 12, true, AppDatabase.MIGRATION_11_12)
    migrated.query(
        "SELECT baselineWeight FROM baseline_override WHERE sessionId IS NULL AND muscleGroup = 'TRICEPS'"
    ).use { c ->
        assertTrue(c.moveToFirst())
        assertEquals(30.0f, c.getFloat(0))
    }
}

@Test
fun migrate11To12_migratesHurtFlagToExerciseHurtState() {
    helper.createDatabase(TEST_DB, 11).use { db ->
        // Insert one hurt exercise and one not-hurt; only the hurt one should appear in the new table.
        db.execSQL("""
            INSERT INTO exercises (id, name, primaryMuscle, secondaryMuscles, equipment, isDisliked, hurtFlag, isUnilateral, isTimed)
                VALUES
                    (1, 'Bench', 'CHEST', '', 'BARBELL', 0, 1, 0, 0),
                    (2, 'Squat', 'QUADS', '', 'BARBELL', 0, 0, 0, 0)
        """.trimIndent())
    }
    val migrated = helper.runMigrationsAndValidate(TEST_DB, 12, true, AppDatabase.MIGRATION_11_12)
    migrated.query("SELECT exerciseId, isHurt FROM exercise_hurt_state").use { c ->
        val rows = mutableListOf<Pair<Long, Int>>()
        while (c.moveToNext()) rows += c.getLong(0) to c.getInt(1)
        assertEquals(listOf(1L to 1), rows)
    }
    migrated.query("PRAGMA table_info(exercises)").use { c ->
        val names = mutableListOf<String>()
        while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
        assertFalse("hurtFlag should be dropped from exercises", names.contains("hurtFlag"))
    }
}

@Test
fun migrate11To12_renamesHistoryTables() {
    helper.createDatabase(TEST_DB, 11).use { /* empty seed */ }
    val migrated = helper.runMigrationsAndValidate(TEST_DB, 12, true, AppDatabase.MIGRATION_11_12)
    // Old names should be gone, new names should exist. Using sqlite_master to enumerate.
    val tables = mutableListOf<String>()
    migrated.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
        while (c.moveToNext()) tables += c.getString(0)
    }
    assertFalse(tables.contains("baseline_change_log"))
    assertFalse(tables.contains("coefficient_change_log"))
    assertTrue(tables.contains("baseline_history"))
    assertTrue(tables.contains("coefficient_history"))
}
```

- [x] **Step 2: Run the migration tests**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.data.MigrationTest"`
Expected: PASS for all `migrate11To12_*` tests. Other migration tests in the file should still pass too.

If a test fails because the v11 seed SQL doesn't match the v11 schema exactly (column count mismatch, missing required column, etc.), open `app/schemas/.../11.json` for that table and fix the INSERT.

- [x] **Step 3: Commit**

```bash
jj describe -m "test(data): cover rewritten v11→v12 migration"
jj new
```

---

## Phase 4: Replay Implementation

### Task 11: Add `Mutex` to `WorkoutRepository`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [x] **Step 1: Add the mutex**

At the top of `WorkoutRepository`, add:

```kotlin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// inside the class body:
private val replayMutex = Mutex()
```

(Use the mutex inside `replayDerivedState` — Task 14.)

(No test; verified indirectly by Task 15's replay determinism tests.)

### Task 12: Refactor `recomputeCoefficients(snapshot, asOf)`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [x] **Step 1: Replace the signature and body**

Change `recomputeCoefficients(asOf: Long? = null)` to take a snapshot and an `asOf`. The new body uses `snapshot.filteredCoefficientInput(asOf)` and writes to the snapshot's `currentCoefficients` in addition to inserting `coefficient_history` rows.

```kotlin
internal suspend fun recomputeCoefficients(snapshot: ReplaySnapshot, asOf: Long) {
    if (heuristics.isEmpty()) return
    val input = snapshot.filteredCoefficientInput(asOf)
    val candidatesByExercise = mutableMapOf<Long, MutableList<Pair<String, CoefficientResult>>>()
    for (heuristic in heuristics) {
        for (result in heuristic.compute(input)) {
            candidatesByExercise.getOrPut(result.exerciseId) { mutableListOf() }
                .add(heuristic.name to result)
        }
    }
    val latestByExercise = db.coefficientHistoryDao().getLatestPerExercise()
        .associateBy { it.exerciseId }
    for ((exerciseId, candidates) in candidatesByExercise) {
        val (winnerName, winner) = mergeHeuristicResults(candidates) ?: continue
        db.coefficientHistoryDao().insert(
            CoefficientHistory(
                exerciseId = exerciseId,
                previousCoefficient = latestByExercise[exerciseId]?.coefficient
                    ?: snapshot.seedCoefficients[exerciseId],
                coefficient = winner.coefficient,
                heuristicName = winnerName,
                heuristicMetadata = winner.metadata,
                computedAt = asOf,
            )
        )
        snapshot.currentCoefficients[exerciseId] = winner.coefficient
    }
}
```

Note: the function now writes inside the caller's transaction (no internal `db.withTransaction`); replay opens one transaction at the top.

### Task 13: Refactor `applyBaselineNormalization(snapshot, asOf, sessionId)`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [x] **Step 1: Replace signature and body**

```kotlin
internal suspend fun applyBaselineNormalization(
    snapshot: ReplaySnapshot,
    asOf: Long,
    sessionId: Long,
) {
    if (normalizers.isEmpty()) return
    val input = snapshot.filteredNormalizationInput(asOf)
    val weightUnit = db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG
    val threshold = BaselineNormalizationThreshold.forUnit(weightUnit)

    val proposals = normalizers.flatMap { it.compute(input) }
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

        db.muscleGroupStrengthDao().upsert(
            MuscleGroupStrength(muscleGroup = proposal.muscleGroup, baselineWeight = newBaseline)
        )
        snapshot.currentBaselines[proposal.muscleGroup] = newBaseline
        db.baselineHistoryDao().insert(
            BaselineHistory(
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
            db.coefficientHistoryDao().insert(
                CoefficientHistory(
                    exerciseId = snap.exercise.id,
                    previousCoefficient = latestCoefByExercise[snap.exercise.id]?.coefficient
                        ?: snap.currentCoefficient,
                    coefficient = newCoef,
                    heuristicName = "baseline_normalization",
                    heuristicMetadata = proposal.metadata,
                    computedAt = asOf,
                )
            )
            snapshot.currentCoefficients[snap.exercise.id] = newCoef
        }
    }
}
```

(No internal `db.withTransaction`; replay holds the transaction.)

### Task 14: Refactor `applySessionProgression(sessionId, snapshot, asOf)`; remove hurtFlag mutation and the `recomputeDerivedState` call

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [x] **Step 1: Replace signature and body**

The old `applySessionProgression(sessionId: Long, exerciseReductions: Map<...> = emptyMap())` becomes:

```kotlin
suspend fun applySessionProgression(
    sessionId: Long,
    snapshot: ReplaySnapshot,
    asOf: Long,
    exerciseReductions: Map<Long, Float> = emptyMap(),
) {
    val sets = db.workoutSetDao().getSetsForSession(sessionId)
    if (sets.isEmpty()) return

    val exerciseIds = sets.map { it.exerciseId }.toSet()
    val exerciseById = db.exerciseDao().getByIds(exerciseIds).associateBy { it.id }

    // (hurtFlag side effect REMOVED — moved to live recording in Phase 5.)

    val sessionReps = sets.firstOrNull { exerciseById[it.exerciseId]?.isTimed != true }?.targetReps ?: 5

    val effectiveCoefficients = effectiveCoefficientSource()
    val exercisesByMuscle = exerciseById.values
        .filter { (effectiveCoefficients.get(it) ?: 0f) > 0f }
        .groupBy { it.primaryMuscle }
    for ((muscleGroup, muscleExercises) in exercisesByMuscle) {
        val allFeedbacks = muscleExercises.flatMap { exercise ->
            sets.filter { it.exerciseId == exercise.id }.mapNotNull { it.feedback }
        }
        if (allFeedbacks.isEmpty()) continue

        val current = snapshot.currentBaselines[muscleGroup] ?: continue
        val minReduction = muscleExercises.mapNotNull { exerciseReductions[it.id] }.maxOrNull() ?: 0f
        val newBaseline = progressionEngine.computeNextBaseline(current, allFeedbacks, minReduction, sessionReps)
        val roundedNewBaseline = WeightFormatter.round(newBaseline, weightUnit)
        db.muscleGroupStrengthDao().upsert(
            MuscleGroupStrength(muscleGroup = muscleGroup, baselineWeight = roundedNewBaseline)
        )
        snapshot.progressionBaselines[sessionId to muscleGroup] = current
        snapshot.currentBaselines[muscleGroup] = roundedNewBaseline
        db.baselineHistoryDao().insert(
            BaselineHistory(
                sessionId = sessionId,
                muscleGroup = muscleGroup,
                previousBaseline = current,
                newBaseline = roundedNewBaseline,
                changeReason = BaselineChangeReason.PROGRESSION,
                feedbacks = allFeedbacks.joinToString(",") { it.name },
                sessionReps = sessionReps,
                minReductionFraction = if (minReduction > 0f) minReduction else null,
                timestamp = asOf,
            )
        )
    }
    recomputeCoefficients(snapshot, asOf)
    applyBaselineNormalization(snapshot, asOf, sessionId)
}
```

Verify: there is no `recomputeDerivedState(...)` call left in this function.

Note: `weightUnit` here is the field on `WorkoutRepository`; if there isn't one, source it the same way the current code does (e.g., `db.userProfileDao().getProfile()?.weightUnit ?: WeightUnit.KG`).

### Task 15: Implement `replayDerivedState()`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ReplaySnapshot.kt` (add `loadStaticFromDb`)

- [x] **Step 1: Add a static-loader to `ReplaySnapshot`**

Append to `ReplaySnapshot.kt`:

```kotlin
companion object {
    suspend fun loadStaticFromDb(
        db: AppDatabase,
        coefficientSource: UserCoefficientSource,
    ): ReplaySnapshot {
        val allExercises = db.exerciseDao().getAll()
        val activeExercises = db.exerciseDao().getActive()
        val allSets = db.workoutSetDao().getAll()
        val allSessionTimes = db.workoutSessionDao().getAll().associate { it.id to it.startTime }
        val exerciseMuscle = allExercises.associate { it.id to it.primaryMuscle }
        val seedCoefficients = activeExercises.associate { ex ->
            ex.id to (coefficientSource.get(ex) ?: ex.seedCoefficient)
        }
        return ReplaySnapshot(
            allSets = allSets,
            allSessionTimes = allSessionTimes,
            exerciseMuscle = exerciseMuscle,
            seedCoefficients = seedCoefficients,
            allExercises = allExercises,
        )
    }
}
```

If your imports don't have `AppDatabase` and `UserCoefficientSource` paths handy, search for them:

```bash
grep -rn "class AppDatabase\|interface UserCoefficientSource\|class UserCoefficientSource" app/src/main --include="*.kt"
```

- [x] **Step 2: Add `replayDerivedState()` to `WorkoutRepository`**

```kotlin
suspend fun replayDerivedState() = replayMutex.withLock {
    db.withTransaction {
        db.baselineHistoryDao().deleteAll()
        db.coefficientHistoryDao().deleteAll()
        db.muscleGroupStrengthDao().deleteAll()

        val snapshot = ReplaySnapshot.loadStaticFromDb(db, coefficientSource)
        val initials = db.baselineOverrideDao().getInitials()
        val overridesBySession = db.baselineOverrideDao().getNonInitials()
            .groupBy { it.sessionId!! }

        for (init in initials) {
            snapshot.currentBaselines[init.muscleGroup] = init.baselineWeight
            db.muscleGroupStrengthDao().upsert(
                MuscleGroupStrength(muscleGroup = init.muscleGroup, baselineWeight = init.baselineWeight)
            )
            db.baselineHistoryDao().insert(
                BaselineHistory(
                    sessionId = null,
                    muscleGroup = init.muscleGroup,
                    previousBaseline = 0f,
                    newBaseline = init.baselineWeight,
                    changeReason = BaselineChangeReason.INITIAL,
                    timestamp = init.asOf,
                )
            )
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
                db.baselineHistoryDao().insert(
                    BaselineHistory(
                        sessionId = session.id,
                        muscleGroup = o.muscleGroup,
                        previousBaseline = prev,
                        newBaseline = o.baselineWeight,
                        changeReason = BaselineChangeReason.OVERRIDE,
                        timestamp = o.asOf,
                    )
                )
            }
            applySessionProgression(session.id, snapshot, asOf = session.endTime!!)
        }
    }
}
```

- [x] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: PASS. Fix any import or unresolved-reference errors before moving on.

- [x] **Step 4: Commit Phase 4 so far**

```bash
jj describe -m "feat(domain): replayDerivedState wipes and rebuilds via ReplaySnapshot"
jj new
```

### Task 16: Replay determinism + behavior tests

**Files:**
- Create: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/ReplayDerivedStateTest.kt`

- [x] **Step 1: Write the tests**

```kotlin
package io.github.fowles.stochastic_strength.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReplayDerivedStateTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.reset(context, scope)
        db = AppDatabase.getInstance(context, scope)
        repository = WorkoutRepository(
            db,
            heuristics = listOf(EstCoefConsensusHeuristic()),
            normalizers = listOf(SeedNormalizer()),
        )
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.reset(context, scope)
    }

    @Test
    fun replay_isIdempotent() = runBlocking {
        seedSmallHistory()

        repository.replayDerivedState()
        val baselines1 = db.baselineHistoryDao().getAll().map { it.toComparable() }
        val coefs1 = db.coefficientHistoryDao().getAll().map { it.toComparable() }
        val strengths1 = db.muscleGroupStrengthDao().getAll().map { it.muscleGroup to it.baselineWeight }

        repository.replayDerivedState()
        val baselines2 = db.baselineHistoryDao().getAll().map { it.toComparable() }
        val coefs2 = db.coefficientHistoryDao().getAll().map { it.toComparable() }
        val strengths2 = db.muscleGroupStrengthDao().getAll().map { it.muscleGroup to it.baselineWeight }

        assertEquals(baselines1, baselines2)
        assertEquals(coefs1, coefs2)
        assertEquals(strengths1, strengths2)
    }

    @Test
    fun replay_appliesManualOverridesAtSessionBoundary() = runBlocking {
        seedSmallHistory()
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = SESSION_2_ID,
            muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 999f,
            asOf = SESSION_2_START,
        ))

        repository.replayDerivedState()

        // The OVERRIDE row for session 2 should record the override.
        val overrides = db.baselineHistoryDao().getAll()
            .filter { it.changeReason == BaselineChangeReason.OVERRIDE && it.muscleGroup == MuscleGroup.CHEST }
        assertEquals(1, overrides.size)
        assertEquals(999f, overrides[0].newBaseline)

        // The PROGRESSION row for session 2 should see previousBaseline = 999f.
        val progressionForSession2 = db.baselineHistoryDao().getAll()
            .firstOrNull {
                it.changeReason == BaselineChangeReason.PROGRESSION &&
                    it.sessionId == SESSION_2_ID && it.muscleGroup == MuscleGroup.CHEST
            }
        assertNotNull("expected a PROGRESSION row for session 2 CHEST", progressionForSession2)
        assertEquals(999f, progressionForSession2!!.previousBaseline)
    }

    @Test
    fun replay_reconstructsHistoricalTrajectory() = runBlocking {
        seedTwoPhaseTrainingHistory()

        repository.replayDerivedState()

        val coefs = db.coefficientHistoryDao().getForExercise(BENCH_EXERCISE_ID)
        // Two-phase: should NOT be monotonically rising (phase 1 had TOO_HARD, phase 2 had confident RIR).
        // We assert there is at least one row whose coefficient is below the previous row's coefficient.
        val droppedAtLeastOnce = coefs.zipWithNext().any { (a, b) -> b.coefficient < a.coefficient }
        assertTrue("coefficient should dip during the TOO_HARD phase, then recover; coefs=${coefs.map { it.coefficient }}",
            droppedAtLeastOnce)
    }

    // ----- helpers below: seed minimal but realistic histories -----

    private fun BaselineHistory.toComparable() = listOf(
        sessionId, muscleGroup, previousBaseline, newBaseline, changeReason, timestamp,
    )

    private fun CoefficientHistory.toComparable() = listOf(
        exerciseId, previousCoefficient, coefficient, heuristicName, computedAt,
    )

    private suspend fun seedSmallHistory() {
        // Profile + one CHEST exercise + 2 sessions with completed sets + initial baseline override.
        db.userProfileDao().insert(UserProfile(
            sex = Sex.MALE, strengthLevel = StrengthLevel.NOVICE, weightUnit = WeightUnit.KG))
        db.exerciseDao().insert(Exercise(
            id = BENCH_EXERCISE_ID, name = "Bench", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL))
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = null, muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 80f, asOf = 0))

        db.workoutSessionDao().insert(WorkoutSession(
            id = SESSION_1_ID, startTime = SESSION_1_START, endTime = SESSION_1_START + 1000))
        repeat(3) { i ->
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = SESSION_1_ID, exerciseId = BENCH_EXERCISE_ID, setNumber = i + 1,
                targetWeight = 80f, targetReps = 5, actualReps = 5,
                feedback = SetFeedback.RIR_2_4, completedAt = SESSION_1_START + i * 100L))
        }

        db.workoutSessionDao().insert(WorkoutSession(
            id = SESSION_2_ID, startTime = SESSION_2_START, endTime = SESSION_2_START + 1000))
        repeat(3) { i ->
            db.workoutSetDao().insert(WorkoutSet(
                sessionId = SESSION_2_ID, exerciseId = BENCH_EXERCISE_ID, setNumber = i + 1,
                targetWeight = 82.5f, targetReps = 5, actualReps = 5,
                feedback = SetFeedback.RIR_2_4, completedAt = SESSION_2_START + i * 100L))
        }
    }

    private suspend fun seedTwoPhaseTrainingHistory() {
        db.userProfileDao().insert(UserProfile(
            sex = Sex.MALE, strengthLevel = StrengthLevel.NOVICE, weightUnit = WeightUnit.KG))
        db.exerciseDao().insert(Exercise(
            id = BENCH_EXERCISE_ID, name = "Bench", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL))
        // Second exercise in CHEST so the heuristic's H2 sibling logic has consensus partners.
        db.exerciseDao().insert(Exercise(
            id = BENCH_EXERCISE_ID + 1, name = "Dip", primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BODYWEIGHT))
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = null, muscleGroup = MuscleGroup.CHEST,
            baselineWeight = 80f, asOf = 0))

        // Phase 1: sessions 1 and 2 with TOO_HARD feedback → coefficient should be pushed down.
        for ((idx, sessionId) in listOf(SESSION_1_ID, SESSION_2_ID).withIndex()) {
            val t = SESSION_1_START + idx * 24L * 60 * 60 * 1000
            db.workoutSessionDao().insert(WorkoutSession(id = sessionId, startTime = t, endTime = t + 1000))
            for (exId in listOf(BENCH_EXERCISE_ID, BENCH_EXERCISE_ID + 1)) {
                repeat(3) { i ->
                    db.workoutSetDao().insert(WorkoutSet(
                        sessionId = sessionId, exerciseId = exId, setNumber = i + 1,
                        targetWeight = 80f, targetReps = 5, actualReps = 2,
                        feedback = SetFeedback.TOO_HARD, completedAt = t + i * 100L))
                }
            }
        }
        // Phase 2: sessions 3-5 with confident RIR_0_1 feedback → coefficient should rise.
        for ((idx, sessionId) in listOf(3L, 4L, 5L).withIndex()) {
            val t = SESSION_1_START + (2 + idx) * 24L * 60 * 60 * 1000
            db.workoutSessionDao().insert(WorkoutSession(id = sessionId, startTime = t, endTime = t + 1000))
            for (exId in listOf(BENCH_EXERCISE_ID, BENCH_EXERCISE_ID + 1)) {
                repeat(3) { i ->
                    db.workoutSetDao().insert(WorkoutSet(
                        sessionId = sessionId, exerciseId = exId, setNumber = i + 1,
                        targetWeight = 80f, targetReps = 5, actualReps = 5,
                        feedback = SetFeedback.RIR_0_1, completedAt = t + i * 100L))
                }
            }
        }
    }

    companion object {
        private const val BENCH_EXERCISE_ID = 100L
        private const val SESSION_1_ID = 1L
        private const val SESSION_2_ID = 2L
        private const val SESSION_1_START = 1_700_000_000_000L
        private const val SESSION_2_START = 1_700_086_400_000L  // +1 day
    }
}
```

- [x] **Step 2: Run the tests**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.ReplayDerivedStateTest"`
Expected: PASS for all three tests. If `replay_reconstructsHistoricalTrajectory` fails, the most likely cause is that `applySessionProgression` is being called with `asOf` after all sessions exist (no per-session filter) — verify Task 14's `snapshot.filteredCoefficientInput(asOf)` is being used.

- [x] **Step 3: Commit**

```bash
jj describe -m "test(domain): replay determinism, override boundary, trajectory shape"
jj new
```

---

## Phase 5: Input Writes from Live Paths

### Task 17: `applyManualBaselineOverrides` writes only `baseline_override`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [x] **Step 1: Update the body**

```kotlin
suspend fun applyManualBaselineOverrides(sessionId: Long, overrides: Map<MuscleGroup, Float>) {
    if (overrides.isEmpty()) return
    val session = db.workoutSessionDao().getById(sessionId)
    val asOf = session?.startTime ?: System.currentTimeMillis()
    for ((muscleGroup, newBaseline) in overrides) {
        db.baselineOverrideDao().insert(
            BaselineOverride(
                sessionId = sessionId,
                muscleGroup = muscleGroup,
                baselineWeight = newBaseline,
                asOf = asOf,
            )
        )
    }
}
```

Remove the calls to `muscleGroupStrengthDao().upsert` and `baselineHistoryDao().insert` that used to be in this function — those are now derived and rebuilt by replay.

- [x] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: PASS.

- [x] **Step 3: Commit**

```bash
jj describe -m "refactor(domain): applyManualBaselineOverrides writes baseline_override only"
jj new
```

### Task 18: `WorkoutSessionController.recordFeedback` writes `exercise_hurt_state` on HURT

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`

- [x] **Step 1: Update `recordFeedback`**

Inside the `scope.launch { ... }` block in `recordFeedback`, after the `database.workoutSetDao().insert(...)` line, add:

```kotlin
if (feedback == SetFeedback.HURT) {
    database.exerciseHurtStateDao().upsert(
        ExerciseHurtState(
            exerciseId = planned.exercise.id,
            isHurt = true,
            asOf = System.currentTimeMillis(),
        )
    )
}
```

Add the necessary import for `ExerciseHurtState`.

- [x] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: PASS.

- [x] **Step 3: Commit**

```bash
jj describe -m "feat(ui): record exercise hurt state on HURT feedback"
jj new
```

### Task 19: `ExerciseDetailViewModel.toggleHurtFlag` writes to `exercise_hurt_state`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailViewModel.kt`

- [x] **Step 1: Update `toggleHurtFlag`**

Replace the current body (which mutates `Exercise.hurtFlag`) with one that reads/writes `ExerciseHurtState`. The ViewModel will likely need access to the DAO via the repository or directly via `app.database`.

```kotlin
fun toggleHurtFlag() {
    val exercise = _state.value.exercise ?: return
    viewModelScope.launch {
        val currentlyHurt = _state.value.isHurt
        database.exerciseHurtStateDao().upsert(
            ExerciseHurtState(
                exerciseId = exercise.id,
                isHurt = !currentlyHurt,
                asOf = System.currentTimeMillis(),
            )
        )
        _state.value = _state.value.copy(isHurt = !currentlyHurt)
    }
}
```

You will also need to add an `isHurt: Boolean` field to the ViewModel's state, populated when the exercise loads:

```kotlin
private suspend fun loadExercise(id: Long) {
    val exercise = database.exerciseDao().getById(id)
    val hurtState = database.exerciseHurtStateDao().get(id)
    _state.value = _state.value.copy(
        exercise = exercise,
        isHurt = hurtState?.isHurt ?: false,
    )
}
```

Inspect the existing ViewModel to find the right place for these wiring changes; the names of the load function may differ.

- [x] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: PASS.

### Task 20: UI screens read `isHurt` from `exercise_hurt_state`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExerciseDetailScreen.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/exercises/ExercisesScreen.kt`

- [x] **Step 1: ExerciseDetailScreen**

Replace `exercise.hurtFlag` reads with the `isHurt` state field passed in from the ViewModel. Three call sites:
- The button colors `if (exercise.hurtFlag) ...` → `if (state.isHurt) ...`
- `checked = exercise.hurtFlag` → `checked = state.isHurt`
- Anywhere else.

Remove any `// TEMP:` stubs from Task 7.

- [x] **Step 2: ExercisesScreen**

The list shows a hurt indicator per exercise. Replace `if (exercise.hurtFlag)` with a lookup against a `Map<Long, Boolean>` (exerciseId → isHurt) that the screen-level ViewModel provides. Easiest path: add a method on the screen's ViewModel that exposes a `Flow<Map<Long, Boolean>>` by `observeAll()` on `ExerciseHurtStateDao`, mapping each row.

Inspect the screen's ViewModel for the existing pattern; mimic it. If no ViewModel exists for `ExercisesScreen`, use the existing `database.exerciseHurtStateDao().getAll()` from wherever exercises are currently loaded.

- [x] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: PASS.

- [x] **Step 4: Commit Phase 5 UI**

```bash
jj describe -m "refactor(ui): read hurt state from exercise_hurt_state table"
jj new
```

### Task 20a: Live-flow input write tests

**Files:**
- Create: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/LiveInputWritesTest.kt`

- [x] **Step 1: Write the tests**

```kotlin
package io.github.fowles.stochastic_strength.domain

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveInputWritesTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.reset(context, scope)
        db = AppDatabase.getInstance(context, scope)
        repository = WorkoutRepository(
            db,
            heuristics = listOf(EstCoefConsensusHeuristic()),
            normalizers = listOf(SeedNormalizer()),
        )
        runBlocking {
            db.userProfileDao().insert(UserProfile(
                sex = Sex.MALE, strengthLevel = StrengthLevel.NOVICE, weightUnit = WeightUnit.KG))
        }
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppDatabase.reset(context, scope)
    }

    @Test
    fun applyManualBaselineOverrides_writesOverrideRowOnly() = runBlocking {
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(
            startTime = 1_700_000_000_000L, endTime = null))

        repository.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.CHEST to 95f))

        val overrides = db.baselineOverrideDao().getForSession(sessionId)
        assertEquals(1, overrides.size)
        assertEquals(95f, overrides[0].baselineWeight)

        // Should NOT have written muscle_group_strength or baseline_history.
        // (The session has no endTime, so replay would skip it; nothing should be derived from it.)
        val strengths = db.muscleGroupStrengthDao().getAll()
        assertTrue("expected no muscle_group_strength row from manual override write; got $strengths",
            strengths.none { it.muscleGroup == MuscleGroup.CHEST })
        val history = db.baselineHistoryDao().getAll()
        assertTrue("expected no baseline_history row from manual override write; got $history",
            history.isEmpty())
    }

    @Test
    fun applySessionProgression_doesNotMutateHurtState() = runBlocking {
        // Pre-seed: hurt cleared explicitly.
        db.exerciseDao().insert(Exercise(
            id = 100L, name = "Bench", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL))
        db.exerciseHurtStateDao().upsert(ExerciseHurtState(
            exerciseId = 100L, isHurt = false, asOf = 0L))
        db.baselineOverrideDao().insert(BaselineOverride(
            sessionId = null, muscleGroup = MuscleGroup.CHEST, baselineWeight = 80f, asOf = 0))

        // A session with HURT feedback that goes through the replay path (not the live recordFeedback).
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(
            startTime = 1_700_000_000_000L, endTime = 1_700_000_001_000L))
        db.workoutSetDao().insert(WorkoutSet(
            sessionId = sessionId, exerciseId = 100L, setNumber = 1,
            targetWeight = 80f, targetReps = 5, actualReps = null,
            feedback = SetFeedback.HURT, completedAt = 1_700_000_000_500L))

        repository.replayDerivedState()

        val state = db.exerciseHurtStateDao().get(100L)
        assertEquals("replay must not mutate exercise_hurt_state; user cleared it explicitly",
            false, state?.isHurt)
    }
}
```

- [x] **Step 2: Run**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.LiveInputWritesTest"`
Expected: PASS.

- [x] **Step 3: Commit**

```bash
jj describe -m "test(domain): cover manual override write and hurt-state non-mutation"
jj new
```

---

## Phase 6: Backfill + Session-End Wiring

### Task 21: Simplify `DerivedStateBackfill`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/DerivedStateBackfill.kt`

- [x] **Step 1: Replace the class body**

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase

/**
 * Launch-time orchestrator. Both steps are idempotent, so this can run on every launch.
 */
class DerivedStateBackfill(
    private val database: AppDatabase,
    private val repository: WorkoutRepository,
) {
    suspend fun run() {
        val profile = database.userProfileDao().getProfile() ?: return
        ActualRepsBackfill(database, profile.weightUnit).run()
        repository.replayDerivedState()
    }
}
```

`CURRENT_VERSION` and the `when` arm go away.

- [x] **Step 2: Update `DerivedStateBackfillTest`**

Open `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/DerivedStateBackfillTest.kt`. Remove tests that referenced `derivedStateVersion` or `CURRENT_VERSION`. Replace them with:

```kotlin
@Test
fun run_runsActualRepsBackfillAndReplay() = runBlocking {
    seedProfile()
    // Seed enough data that ActualRepsBackfill has something to do and replay produces rows.
    // (You can reuse the helpers from ReplayDerivedStateTest if convenient.)

    DerivedStateBackfill(db, repository).run()

    val baselines = db.baselineHistoryDao().getAll()
    assertTrue("expected replay to produce baseline_history rows", baselines.isNotEmpty())
}

@Test
fun run_isIdempotent() = runBlocking {
    seedProfile()
    DerivedStateBackfill(db, repository).run()
    val baselines1 = db.baselineHistoryDao().getAll().map { it.toComparable() }
    DerivedStateBackfill(db, repository).run()
    val baselines2 = db.baselineHistoryDao().getAll().map { it.toComparable() }
    assertEquals(baselines1, baselines2)
}
```

Adapt the helpers (`seedProfile`, `BaselineHistory.toComparable()`) to whatever exists in the file or copy from `ReplayDerivedStateTest`.

- [x] **Step 3: Run the tests**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.DerivedStateBackfillTest"`
Expected: PASS.

- [x] **Step 4: Commit**

```bash
jj describe -m "refactor(domain): DerivedStateBackfill drops version gating"
jj new
```

### Task 22: Live session-end calls `replayDerivedState`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`

- [x] **Step 1: Find the session-end call**

At `WorkoutSessionController.kt:311`, the existing call is something like `repository.applySessionProgression(done.sessionId, reductions)`. The reductions still need to flow into the progression step; the simplest approach is a new repository helper that combines "store reductions for this session, then replay."

Add to `WorkoutRepository`:

```kotlin
suspend fun finishSession(sessionId: Long, exerciseReductions: Map<Long, Float>) {
    // Persist reductions so `applySessionProgression` (called inside replay) can apply them.
    // Since reductions are session-specific transient data and there's no table for them today,
    // pass them through via a single-shot field on WorkoutRepository:
    pendingReductions = sessionId to exerciseReductions
    try {
        replayDerivedState()
    } finally {
        pendingReductions = null
    }
}

private var pendingReductions: Pair<Long, Map<Long, Float>>? = null
```

And inside `applySessionProgression`, after computing `exerciseReductions` from the argument, fall back to `pendingReductions` if the argument is empty and the IDs match:

```kotlin
val effectiveReductions = exerciseReductions.takeIf { it.isNotEmpty() }
    ?: pendingReductions?.let { (id, r) -> if (id == sessionId) r else null }
    ?: emptyMap()
```

(If this single-shot field feels too hacky, an alternative is a small `session_progression_meta` table with `(sessionId PK, exerciseId, reductionFraction)` rows that the live path writes and replay reads. Out of scope for this plan unless the simple approach hits a wall.)

Then in `WorkoutSessionController.kt:311`, change the call:

```kotlin
repository.finishSession(done.sessionId, reductions)
```

- [x] **Step 2: Build and run integration tests**

```bash
./gradlew :app:assembleDebug
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.*"
```
Expected: PASS.

- [x] **Step 3: Commit**

```bash
jj describe -m "feat(ui): session end runs full replay via finishSession"
jj new
```

---

## Phase 7: Cleanup

### Task 23: Remove `recomputeDerivedState`, public `buildCoefficientInput`, `buildNormalizationInput`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [x] **Step 1: Delete unused functions**

Confirm there are no callers:

```bash
grep -rn "recomputeDerivedState\|buildCoefficientInput\|buildNormalizationInput" app/src --include="*.kt"
```

If any matches outside the function definitions themselves, address them before deletion. Then delete:
- `suspend fun recomputeDerivedState(...)` from `WorkoutRepository`.
- `internal suspend fun buildCoefficientInput(): ...` from `WorkoutRepository`.
- `internal suspend fun buildNormalizationInput(): ...` from `WorkoutRepository`.

- [x] **Step 2: Run full test suites**

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedAndroidTest
```
Expected: PASS for both. Any failures here likely indicate a test that still references the deleted functions — update it.

- [x] **Step 3: Commit**

```bash
jj describe -m "refactor(domain): remove obsolete input builders and recomputeDerivedState"
jj new
```

### Task 24: Final regression sweep

- [x] **Step 1: Run everything**

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedAndroidTest
./gradlew :app:lint
```
Expected: PASS / clean for all four. Triage any failures.

- [x] **Step 2: Sanity-check the app**

Launch the app on the emulator (`./gradlew :app:installDebug && adb shell am start -n io.github.fowles.stochastic_strength/.MainActivity`) and walk through:
- Open the app cold — replay should run during `DerivedStateBackfill` without crashing.
- Start a workout, do a couple of sets with mixed feedback, finish — session end should complete (full replay runs).
- Open the coefficient-history debug screen — should show rows.
- Toggle hurt on an exercise — list/detail should update.

Any UI regression noted goes into `CLAUDE_TODO.md` for follow-up, not inline fixes here unless trivial.

- [x] **Step 3: Final commit if anything was tweaked during sanity check**

```bash
jj describe -m "chore: final regression sweep adjustments"
jj new
```

(Skip this commit if nothing changed.)
