# Per-User Exercise Coefficients Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the global hardcoded `ExerciseCoefficients` with a live per-user coefficient system that adapts from session history via pluggable heuristics.

**Architecture:** Append-only `coefficient_change_log` table stores every derived coefficient update. `UserCoefficientSource` implements `CoefficientSource` with DB-backed user values and global fallback. `WorkoutRepository` gains `buildCoefficientInput()` (assembles cross-exercise session snapshots from `workout_sets` + `baseline_change_log`) and `recomputeCoefficients()` (runs all heuristics, merges, upserts). `recomputeCoefficients()` is called at the end of `applySessionProgression` for incremental updates and can be called directly for a full history rescan.

**Tech Stack:** Kotlin, Room 2.8.4, JUnit 4, AndroidX instrumented tests (in-memory Room DB).

---

## File Map

| Action | Path | Purpose |
|--------|------|---------|
| **Create** | `data/model/CoefficientChangeLog.kt` | Room entity for append-only coefficient log |
| **Create** | `data/dao/CoefficientChangeLogDao.kt` | DAO: insert, getAll, getLatestPerExercise |
| **Modify** | `data/AppDatabase.kt` | Add entity + DAO + MIGRATION_8_9 + bump version to 9 |
| **Create** | `domain/CoefficientHeuristic.kt` | `SetSnapshot`, `ExerciseSessionSnapshot`, `CoefficientComputationInput`, `CoefficientResult`, `CoefficientHeuristic` interface |
| **Create** | `domain/UserCoefficientSource.kt` | `CoefficientSource` backed by user log rows, falls back to `ExerciseCoefficients` |
| **Modify** | `data/dao/WorkoutSetDao.kt` | Add `getAll()` query needed by snapshot assembly |
| **Modify** | `domain/WorkoutRepository.kt` | Add `heuristics` param, `buildCoefficientInput()`, `mergeHeuristicResults()`, `recomputeCoefficients()`; wire into `buildPlanner` and `applySessionProgression` |
| **Create** | `test/.../domain/UserCoefficientSourceTest.kt` | Unit tests for fallback behaviour |
| **Modify** | `androidTest/.../domain/WorkoutRepositoryTest.kt` | Add 3 new instrumented tests |

---

## Task 1: DB layer — entity, DAO, and migration

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/CoefficientChangeLog.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/CoefficientChangeLogDao.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt`

- [ ] **Step 1: Create `CoefficientChangeLog.kt`**

```kotlin
package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "coefficient_change_log")
data class CoefficientChangeLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val previousCoefficient: Float? = null,
    val coefficient: Float,
    val heuristicName: String,
    val heuristicMetadata: String? = null,
    val computedAt: Long,
)
```

- [ ] **Step 2: Create `CoefficientChangeLogDao.kt`**

```kotlin
package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.CoefficientChangeLog

@Dao
interface CoefficientChangeLogDao {
    @Insert
    suspend fun insert(entry: CoefficientChangeLog)

    @Query("SELECT * FROM coefficient_change_log ORDER BY computedAt ASC")
    suspend fun getAll(): List<CoefficientChangeLog>

    @Query("SELECT * FROM coefficient_change_log WHERE id IN (SELECT MAX(id) FROM coefficient_change_log GROUP BY exerciseId)")
    suspend fun getLatestPerExercise(): List<CoefficientChangeLog>
}
```

- [ ] **Step 3: Update `AppDatabase.kt`**

Add `CoefficientChangeLog::class` to the `@Database` entities list:
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
        BaselineChangeLog::class,
        CoefficientChangeLog::class,
    ],
    version = 9,
    exportSchema = false,
)
```

Add the DAO accessor after `baselineChangeLogDao()`:
```kotlin
abstract fun coefficientChangeLogDao(): CoefficientChangeLogDao
```

Add the migration before the `@Volatile` line:
```kotlin
private val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `coefficient_change_log` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `exerciseId` INTEGER NOT NULL,
                `previousCoefficient` REAL,
                `coefficient` REAL NOT NULL,
                `heuristicName` TEXT NOT NULL,
                `heuristicMetadata` TEXT,
                `computedAt` INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
```

Add `MIGRATION_8_9` to the `addMigrations(...)` call:
```kotlin
.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
```

Add the import at the top of the file:
```kotlin
import io.github.fowles.stochastic_strength.data.dao.CoefficientChangeLogDao
import io.github.fowles.stochastic_strength.data.model.CoefficientChangeLog
```

- [ ] **Step 4: Build to verify DB compiles**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`. No unresolved references.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add CoefficientChangeLog entity, DAO, and MIGRATION_8_9"
```

---

## Task 2: Heuristic types

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientHeuristic.kt`

- [ ] **Step 1: Create `CoefficientHeuristic.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback

data class SetSnapshot(
    val targetWeight: Float,
    val feedback: SetFeedback?,
)

data class ExerciseSessionSnapshot(
    val exerciseId: Long,
    val sessionId: Long,
    val sessionTime: Long,
    val targetReps: Int,
    val muscleBaseline: Float,
    val sets: List<SetSnapshot>,
)

data class CoefficientComputationInput(
    val history: List<ExerciseSessionSnapshot>,
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

- [ ] **Step 2: Build to verify**

```bash
./gradlew :app:assembleDebug
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
jj commit -m "feat: add CoefficientHeuristic interface and snapshot types"
```

---

## Task 3: `UserCoefficientSource` (TDD)

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/UserCoefficientSourceTest.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/UserCoefficientSource.kt`

- [ ] **Step 1: Write the failing tests**

Create `UserCoefficientSourceTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserCoefficientSourceTest {

    private val bench = Exercise(
        id = 1L,
        name = "Barbell Bench Press",
        primaryMuscle = MuscleGroup.CHEST,
        equipment = Equipment.BARBELL,
    )

    @Test
    fun userCoefficientTakesPriorityOverGlobal() {
        val source = UserCoefficientSource(mapOf(1L to 0.75f))
        assertEquals(0.75f, source.get(bench)!!, 0.001f)
    }

    @Test
    fun fallsBackToGlobalWhenNoUserCoefficient() {
        val source = UserCoefficientSource(emptyMap())
        assertEquals(1.0f, source.get(bench)!!, 0.001f)
    }

    @Test
    fun returnsNullForUnknownExerciseWithNoUserCoefficient() {
        val unknown = Exercise(
            id = 99L,
            name = "Unknown Exercise",
            primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL,
        )
        val source = UserCoefficientSource(emptyMap())
        assertNull(source.get(unknown))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.UserCoefficientSourceTest"
```
Expected: FAILED — `UserCoefficientSource` does not exist.

- [ ] **Step 3: Implement `UserCoefficientSource.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise

class UserCoefficientSource(
    private val userCoefficients: Map<Long, Float>,
    private val fallback: CoefficientSource = ExerciseCoefficients,
) : CoefficientSource {
    override fun get(exercise: Exercise): Float? =
        userCoefficients[exercise.id] ?: fallback.get(exercise)
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.UserCoefficientSourceTest"
```
Expected: `BUILD SUCCESSFUL`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add UserCoefficientSource with global fallback"
```

---

## Task 4: `WorkoutSetDao.getAll()` and `buildCoefficientInput()` (TDD)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/WorkoutSetDao.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

- [ ] **Step 1: Add `getAll()` to `WorkoutSetDao`**

Add after the existing `getFirst()` query:

```kotlin
@Query("SELECT * FROM workout_sets")
suspend fun getAll(): List<WorkoutSet>
```

- [ ] **Step 2: Write the failing instrumented test**

Add to `WorkoutRepositoryTest`:

```kotlin
@Test
fun buildCoefficientInput_assembles_snapshots_from_sets_and_baseline_log() = runBlocking {
    db.exerciseDao().insertAll(listOf(
        Exercise(
            name = "Barbell Bench Press",
            primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL,
        )
    ))
    val exerciseId = db.exerciseDao().getActive().first().id
    val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 5000L))
    db.workoutSetDao().insert(WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = 1,
        targetWeight = 80f,
        targetReps = 5,
        feedback = SetFeedback.RIR_2_4,
    ))
    db.workoutSetDao().insert(WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = 2,
        targetWeight = 75f,
        targetReps = 5,
        feedback = SetFeedback.TOO_HARD,
    ))
    db.baselineChangeLogDao().insert(
        BaselineChangeLog(
            sessionId = sessionId,
            muscleGroup = MuscleGroup.CHEST,
            previousBaseline = 100f,
            newBaseline = 95f,
            changeReason = BaselineChangeReason.PROGRESSION,
            timestamp = 5000L,
        )
    )

    val input = repository.buildCoefficientInput()

    assertEquals(1, input.history.size)
    val snap = input.history.first()
    assertEquals(exerciseId, snap.exerciseId)
    assertEquals(sessionId, snap.sessionId)
    assertEquals(5000L, snap.sessionTime)
    assertEquals(5, snap.targetReps)
    assertEquals(100f, snap.muscleBaseline, 0.001f)
    assertEquals(2, snap.sets.size)
    assertEquals(80f, snap.sets[0].targetWeight, 0.001f)
    assertEquals(SetFeedback.RIR_2_4, snap.sets[0].feedback)
    assertEquals(75f, snap.sets[1].targetWeight, 0.001f)
    assertEquals(SetFeedback.TOO_HARD, snap.sets[1].feedback)
    assertEquals(1.0f, input.currentCoefficients[exerciseId]!!, 0.001f)
}
```

- [ ] **Step 3: Run test to verify it fails**

```bash
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest.buildCoefficientInput_assembles_snapshots_from_sets_and_baseline_log"
```
Expected: FAILED — `buildCoefficientInput` does not exist.

- [ ] **Step 4: Implement `buildCoefficientInput()` in `WorkoutRepository`**

Add `heuristics` constructor parameter and `buildCoefficientInput()`. Also add the required import for `BaselineChangeReason`. Insert after `applyManualBaselineOverrides`:

First, add `heuristics` to the constructor:
```kotlin
class WorkoutRepository(
    private val db: AppDatabase,
    private val coefficientSource: CoefficientSource = ExerciseCoefficients,
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
    private val heuristics: List<CoefficientHeuristic> = listOf(),
)
```

Then add the method:
```kotlin
internal suspend fun buildCoefficientInput(): CoefficientComputationInput {
    val exercises = db.exerciseDao().getActive()
    val exerciseMuscle = exercises.associate { it.id to it.primaryMuscle }
    val sessionTimeById = db.workoutSessionDao().getAll().associate { it.id to it.startTime }
    val progressionLogs = db.baselineChangeLogDao().getAll()
        .filter { it.changeReason == BaselineChangeReason.PROGRESSION }
        .associateBy { it.sessionId to it.muscleGroup }
    val snapshots = db.workoutSetDao().getAll()
        .groupBy { it.sessionId to it.exerciseId }
        .mapNotNull { (key, sets) ->
            val (sessionId, exerciseId) = key
            val muscle = exerciseMuscle[exerciseId] ?: return@mapNotNull null
            val logEntry = progressionLogs[sessionId to muscle] ?: return@mapNotNull null
            val sessionTime = sessionTimeById[sessionId] ?: return@mapNotNull null
            val targetReps = sets.firstOrNull()?.targetReps ?: return@mapNotNull null
            ExerciseSessionSnapshot(
                exerciseId = exerciseId,
                sessionId = sessionId,
                sessionTime = sessionTime,
                targetReps = targetReps,
                muscleBaseline = logEntry.previousBaseline,
                sets = sets.sortedBy { it.setNumber }
                    .map { SetSnapshot(it.targetWeight, it.feedback) },
            )
        }
        .sortedBy { it.sessionTime }
    val latestUserCoefficients = db.coefficientChangeLogDao().getLatestPerExercise()
        .associate { it.exerciseId to it.coefficient }
    val currentCoefficients = exercises.associate { exercise ->
        exercise.id to (latestUserCoefficients[exercise.id]
            ?: coefficientSource.get(exercise)
            ?: 0f)
    }
    return CoefficientComputationInput(
        history = snapshots,
        currentCoefficients = currentCoefficients,
    )
}
```

Add this import at the top of `WorkoutRepository.kt`:
```kotlin
import io.github.fowles.stochastic_strength.data.model.CoefficientChangeLog
import io.github.fowles.stochastic_strength.domain.CoefficientComputationInput
import io.github.fowles.stochastic_strength.domain.CoefficientHeuristic
import io.github.fowles.stochastic_strength.domain.ExerciseSessionSnapshot
import io.github.fowles.stochastic_strength.domain.SetSnapshot
import io.github.fowles.stochastic_strength.domain.UserCoefficientSource
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest.buildCoefficientInput_assembles_snapshots_from_sets_and_baseline_log"
```
Expected: PASSED.

- [ ] **Step 6: Run the full unit test suite to check for regressions**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat: add buildCoefficientInput to WorkoutRepository"
```

---

## Task 5: `recomputeCoefficients()` (TDD)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

- [ ] **Step 1: Write the failing instrumented tests**

Add both tests to `WorkoutRepositoryTest`. These tests need a shared helper — add this private fun inside the test class:

```kotlin
private suspend fun seedChestSession(startTime: Long = 1000L): Pair<Long, Long> {
    db.exerciseDao().insertAll(listOf(
        Exercise(
            name = "Barbell Bench Press",
            primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL,
        )
    ))
    val exerciseId = db.exerciseDao().getActive().first().id
    val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = startTime))
    db.workoutSetDao().insert(WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = 1,
        targetWeight = 80f,
        targetReps = 5,
        feedback = SetFeedback.RIR_2_4,
    ))
    db.baselineChangeLogDao().insert(
        BaselineChangeLog(
            sessionId = sessionId,
            muscleGroup = MuscleGroup.CHEST,
            previousBaseline = 100f,
            newBaseline = 102f,
            changeReason = BaselineChangeReason.PROGRESSION,
            timestamp = startTime,
        )
    )
    return exerciseId to sessionId
}
```

Add the tests:

```kotlin
@Test
fun recomputeCoefficients_writes_log_row_with_null_previousCoefficient_on_first_run() = runBlocking {
    val (exerciseId, _) = seedChestSession()
    val testHeuristic = object : CoefficientHeuristic {
        override val name = "test-heuristic"
        override fun compute(input: CoefficientComputationInput) =
            input.history.map { CoefficientResult(it.exerciseId, 0.9f, "meta") }
    }
    val repo = WorkoutRepository(db, heuristics = listOf(testHeuristic))

    repo.recomputeCoefficients()

    val logs = db.coefficientChangeLogDao().getLatestPerExercise()
    assertEquals(1, logs.size)
    assertEquals(exerciseId, logs.first().exerciseId)
    assertEquals(0.9f, logs.first().coefficient, 0.001f)
    assertNull(logs.first().previousCoefficient)
    assertEquals("test-heuristic", logs.first().heuristicName)
    assertEquals("meta", logs.first().heuristicMetadata)
}

@Test
fun recomputeCoefficients_second_run_populates_previousCoefficient() = runBlocking {
    val (exerciseId, _) = seedChestSession()
    val heuristic1 = object : CoefficientHeuristic {
        override val name = "h1"
        override fun compute(input: CoefficientComputationInput) =
            input.history.map { CoefficientResult(it.exerciseId, 0.9f) }
    }
    WorkoutRepository(db, heuristics = listOf(heuristic1)).recomputeCoefficients()

    val heuristic2 = object : CoefficientHeuristic {
        override val name = "h2"
        override fun compute(input: CoefficientComputationInput) =
            input.history.map { CoefficientResult(it.exerciseId, 0.95f) }
    }
    WorkoutRepository(db, heuristics = listOf(heuristic2)).recomputeCoefficients()

    val latest = db.coefficientChangeLogDao().getLatestPerExercise()
    assertEquals(1, latest.size)
    assertEquals(0.95f, latest.first().coefficient, 0.001f)
    assertEquals(0.9f, latest.first().previousCoefficient!!, 0.001f)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest.recomputeCoefficients_writes_log_row_with_null_previousCoefficient_on_first_run"
```
Expected: FAILED — `recomputeCoefficients` does not exist.

- [ ] **Step 3: Implement `recomputeCoefficients()` and `mergeHeuristicResults()` in `WorkoutRepository`**

Add after `buildCoefficientInput()`:

```kotlin
suspend fun recomputeCoefficients() {
    if (heuristics.isEmpty()) return
    val input = buildCoefficientInput()
    val candidatesByExercise = mutableMapOf<Long, MutableList<Pair<String, CoefficientResult>>>()
    for (heuristic in heuristics) {
        for (result in heuristic.compute(input)) {
            candidatesByExercise.getOrPut(result.exerciseId) { mutableListOf() }
                .add(heuristic.name to result)
        }
    }
    val latestByExercise = db.coefficientChangeLogDao().getLatestPerExercise()
        .associateBy { it.exerciseId }
    val now = System.currentTimeMillis()
    for ((exerciseId, candidates) in candidatesByExercise) {
        val (winnerName, winner) = mergeHeuristicResults(candidates) ?: continue
        db.coefficientChangeLogDao().insert(
            CoefficientChangeLog(
                exerciseId = exerciseId,
                previousCoefficient = latestByExercise[exerciseId]?.coefficient,
                coefficient = winner.coefficient,
                heuristicName = winnerName,
                heuristicMetadata = winner.metadata,
                computedAt = now,
            )
        )
    }
}

private fun mergeHeuristicResults(
    candidates: List<Pair<String, CoefficientResult>>,
): Pair<String, CoefficientResult>? = candidates.firstOrNull()
```

Add this import:
```kotlin
import io.github.fowles.stochastic_strength.domain.CoefficientResult
```

- [ ] **Step 4: Run both new tests to verify they pass**

```bash
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest.recomputeCoefficients_writes_log_row_with_null_previousCoefficient_on_first_run"
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest.recomputeCoefficients_second_run_populates_previousCoefficient"
```
Expected: both PASSED.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat: add recomputeCoefficients to WorkoutRepository"
```

---

## Task 6: Wire into `buildPlanner` and `applySessionProgression` (TDD)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

- [ ] **Step 1: Write the failing instrumented test**

Add to `WorkoutRepositoryTest`:

```kotlin
@Test
fun applySessionProgression_triggers_coefficient_recompute() = runBlocking {
    db.userProfileDao().insert(
        UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.MEDIUM, weightUnit = WeightUnit.KG)
    )
    db.exerciseDao().insertAll(listOf(
        Exercise(
            name = "Barbell Bench Press",
            primaryMuscle = MuscleGroup.CHEST,
            equipment = Equipment.BARBELL,
        )
    ))
    val exerciseId = db.exerciseDao().getActive().first().id
    db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.CHEST, 100f))
    val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))
    db.workoutSetDao().insert(WorkoutSet(
        sessionId = sessionId,
        exerciseId = exerciseId,
        setNumber = 1,
        targetWeight = 80f,
        targetReps = 5,
        feedback = SetFeedback.RIR_2_4,
    ))
    val testHeuristic = object : CoefficientHeuristic {
        override val name = "test"
        override fun compute(input: CoefficientComputationInput) =
            input.history.map { CoefficientResult(it.exerciseId, 0.85f) }
    }
    val repo = WorkoutRepository(db, heuristics = listOf(testHeuristic))

    repo.applySessionProgression(sessionId)

    val logs = db.coefficientChangeLogDao().getLatestPerExercise()
    assertEquals(1, logs.size)
    assertEquals(0.85f, logs.first().coefficient, 0.001f)
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest.applySessionProgression_triggers_coefficient_recompute"
```
Expected: FAILED — coefficient log is empty because `recomputeCoefficients` is not yet called.

- [ ] **Step 3: Add `recomputeCoefficients()` call to the end of `applySessionProgression`**

At the very end of `applySessionProgression`, after the closing brace of the `for` loop:

```kotlin
recomputeCoefficients()
```

- [ ] **Step 4: Wire `UserCoefficientSource` into `buildPlanner`**

In `buildPlanner`, after the `history` val and before the `return WorkoutPlanner(...)`, add:

```kotlin
val latestCoefficients = db.coefficientChangeLogDao().getLatestPerExercise()
    .associate { it.exerciseId to it.coefficient }
val effectiveCoefficients = UserCoefficientSource(latestCoefficients, coefficientSource)
```

Then change `coefficientSource = coefficientSource` to `coefficientSource = effectiveCoefficients` in the `WorkoutPlanner(...)` constructor call.

- [ ] **Step 5: Run the new test to verify it passes**

```bash
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest.applySessionProgression_triggers_coefficient_recompute"
```
Expected: PASSED.

- [ ] **Step 6: Run the full instrumented test suite**

```bash
./gradlew :app:connectedAndroidTest
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 7: Run the full unit test suite**

```bash
./gradlew :app:testDebugUnitTest
```
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 8: Commit**

```bash
jj commit -m "feat: wire UserCoefficientSource and recomputeCoefficients into WorkoutRepository"
```
