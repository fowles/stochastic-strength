# Baseline Change Log Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add an append-only `baseline_change_log` table that records every write to `muscle_group_strength`, capturing before/after baseline, the triggering session, and the reason — enabling per-user coefficient analysis and workout replay.

**Architecture:** New `BaselineChangeLog` entity + `BaselineChangeLogDao` wired into `AppDatabase` (version 7→8). `WorkoutRepository` logs a `PROGRESSION` row inside `applySessionProgression` and gains a new `applyManualBaselineOverrides` method that logs `MANUAL_OVERRIDE` rows. `WorkoutSessionController.startFirstExercise` calls the new repository method instead of writing to the DAO directly, centralising all `muscle_group_strength` writes through `WorkoutRepository`.

**Tech Stack:** Kotlin, Room 2.8.4, AndroidX, JUnit4, instrumented tests (`androidTest`)

---

## File Map

| File | Action |
|------|--------|
| `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeReason.kt` | Create — enum with two cases |
| `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeLog.kt` | Create — Room entity |
| `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/BaselineChangeLogDao.kt` | Create — DAO |
| `app/src/main/java/io/github/fowles/stochastic_strength/data/Converters.kt` | Modify — add `BaselineChangeReason` converters |
| `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt` | Modify — add entity, DAO accessor, MIGRATION_7_8, bump version to 8 |
| `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` | Modify — log in `applySessionProgression`; add `applyManualBaselineOverrides` |
| `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt` | Modify — replace raw DAO loop with `repository.applyManualBaselineOverrides` |
| `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt` | Create — two instrumented tests |

---

### Task 1: Add `BaselineChangeReason` enum and converter

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeReason.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/Converters.kt`

- [x] **Step 1: Create the enum**

```kotlin
// data/model/BaselineChangeReason.kt
package io.github.fowles.stochastic_strength.data.model

enum class BaselineChangeReason {
    MANUAL_OVERRIDE,
    PROGRESSION,
}
```

- [x] **Step 2: Add converters to `Converters.kt`**

Add these two methods inside the `Converters` class, after the existing `fromStrengthLevel`/`toStrengthLevel` pair:

```kotlin
@TypeConverter fun fromBaselineChangeReason(v: BaselineChangeReason): String = v.name
@TypeConverter fun toBaselineChangeReason(v: String): BaselineChangeReason = BaselineChangeReason.valueOf(v)
```

Also add the import at the top of the file:
```kotlin
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
```

- [x] **Step 3: Verify it compiles**

```
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeReason.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/data/Converters.kt
git commit -m "feat: add BaselineChangeReason enum and converter"
```

---

### Task 2: Add `BaselineChangeLog` Room entity

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeLog.kt`

- [x] **Step 1: Create the entity**

```kotlin
// data/model/BaselineChangeLog.kt
package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "baseline_change_log")
data class BaselineChangeLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
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

- [x] **Step 2: Verify it compiles**

```
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeLog.kt
git commit -m "feat: add BaselineChangeLog Room entity"
```

---

### Task 3: Add `BaselineChangeLogDao`

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/BaselineChangeLogDao.kt`

- [x] **Step 1: Create the DAO**

```kotlin
// data/dao/BaselineChangeLogDao.kt
package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.BaselineChangeLog

@Dao
interface BaselineChangeLogDao {
    @Insert
    suspend fun insert(entry: BaselineChangeLog)

    @Insert
    suspend fun insertAll(entries: List<BaselineChangeLog>)

    @Query("SELECT * FROM baseline_change_log ORDER BY timestamp ASC")
    suspend fun getAll(): List<BaselineChangeLog>

    @Query("SELECT * FROM baseline_change_log WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: Long): List<BaselineChangeLog>
}
```

- [x] **Step 2: Verify it compiles**

```
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/dao/BaselineChangeLogDao.kt
git commit -m "feat: add BaselineChangeLogDao"
```

---

### Task 4: Wire `AppDatabase` — entity, DAO accessor, migration, version bump

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt`

- [x] **Step 1: Add imports**

Add these two imports near the top of `AppDatabase.kt`:

```kotlin
import io.github.fowles.stochastic_strength.data.dao.BaselineChangeLogDao
import io.github.fowles.stochastic_strength.data.model.BaselineChangeLog
```

- [x] **Step 2: Add `BaselineChangeLog::class` to the `@Database` annotation**

The `entities` list currently ends with `MuscleGroupStrength::class`. Add `BaselineChangeLog::class` after it:

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
    ],
    version = 8,
    exportSchema = false,
)
```

- [x] **Step 3: Add the abstract DAO accessor**

After the existing `abstract fun muscleGroupStrengthDao(): MuscleGroupStrengthDao` line, add:

```kotlin
abstract fun baselineChangeLogDao(): BaselineChangeLogDao
```

- [x] **Step 4: Add MIGRATION_7_8**

Add this inside the `companion object`, after the `MIGRATION_6_7` block:

```kotlin
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `baseline_change_log` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `sessionId` INTEGER NOT NULL,
                `muscleGroup` TEXT NOT NULL,
                `previousBaseline` REAL NOT NULL,
                `newBaseline` REAL NOT NULL,
                `changeReason` TEXT NOT NULL,
                `feedbacks` TEXT,
                `sessionReps` INTEGER,
                `minReductionFraction` REAL,
                `timestamp` INTEGER NOT NULL
            )
        """.trimIndent())
    }
}
```

- [x] **Step 5: Register MIGRATION_7_8 in `addMigrations`**

The `buildDatabase` method has `.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)`. Add `MIGRATION_7_8` to the end:

```kotlin
.addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
```

- [x] **Step 6: Verify it compiles**

```
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`

- [x] **Step 7: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt
git commit -m "feat: wire BaselineChangeLog into AppDatabase, version 7→8"
```

---

### Task 5: Write failing test for `applySessionProgression` logging

**Files:**
- Create: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

- [x] **Step 1: Create the test file**

```kotlin
package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.data.model.UserProfile
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: WorkoutRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WorkoutRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun applySessionProgression_logs_PROGRESSION_row() = runBlocking {
        db.userProfileDao().insert(
            UserProfile(sex = Sex.MALE, strengthLevel = StrengthLevel.INTERMEDIATE, weightUnit = WeightUnit.KG)
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
        db.workoutSetDao().insert(
            WorkoutSet(
                sessionId = sessionId,
                exerciseId = exerciseId,
                setNumber = 1,
                targetWeight = 80f,
                targetReps = 5,
                feedback = SetFeedback.RIR_2_4,
            )
        )

        repository.applySessionProgression(sessionId)

        val logs = db.baselineChangeLogDao().getForSession(sessionId)
        assertEquals(1, logs.size)
        with(logs[0]) {
            assertEquals(MuscleGroup.CHEST, muscleGroup)
            assertEquals(100f, previousBaseline)
            assertTrue(newBaseline > 100f)
            assertEquals(BaselineChangeReason.PROGRESSION, changeReason)
            assertEquals("RIR_2_4", feedbacks)
            assertEquals(5, sessionReps)
            assertNull(minReductionFraction)
        }
    }
}
```

- [x] **Step 2: Run the test and confirm it fails**

Requires a connected device or emulator.

```
./gradlew :app:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest#applySessionProgression_logs_PROGRESSION_row
```

Expected: test runs but `assertEquals(1, logs.size)` fails with `expected:<1> but was:<0>` — no log rows exist yet.

---

### Task 6: Add logging to `applySessionProgression`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [x] **Step 1: Add imports to `WorkoutRepository.kt`**

```kotlin
import io.github.fowles.stochastic_strength.data.model.BaselineChangeLog
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
```

- [x] **Step 2: Replace the upsert call in `applySessionProgression` with an upsert + log insert**

Find this block inside the `for ((muscleGroup, muscleExercises) in exercisesByMuscle)` loop (currently the last ~3 lines of the loop):

```kotlin
            val newBaseline = ProgressionEngine.computeNextBaseline(current.baselineWeight, allFeedbacks, minReduction, sessionReps)
            db.muscleGroupStrengthDao().upsert(
                current.copy(baselineWeight = WeightFormatter.round(newBaseline, weightUnit))
            )
```

Replace with:

```kotlin
            val newBaseline = ProgressionEngine.computeNextBaseline(current.baselineWeight, allFeedbacks, minReduction, sessionReps)
            val roundedNewBaseline = WeightFormatter.round(newBaseline, weightUnit)
            db.muscleGroupStrengthDao().upsert(current.copy(baselineWeight = roundedNewBaseline))
            db.baselineChangeLogDao().insert(
                BaselineChangeLog(
                    sessionId = sessionId,
                    muscleGroup = muscleGroup,
                    previousBaseline = current.baselineWeight,
                    newBaseline = roundedNewBaseline,
                    changeReason = BaselineChangeReason.PROGRESSION,
                    feedbacks = allFeedbacks.joinToString(",") { it.name },
                    sessionReps = sessionReps,
                    minReductionFraction = if (minReduction > 0f) minReduction else null,
                    timestamp = System.currentTimeMillis(),
                )
            )
```

- [x] **Step 3: Run the test and confirm it passes**

```
./gradlew :app:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest#applySessionProgression_logs_PROGRESSION_row
```

Expected: `BUILD SUCCESSFUL`, test result: PASSED

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt
git commit -m "feat: log PROGRESSION rows in applySessionProgression"
```

---

### Task 7: Write failing test for `applyManualBaselineOverrides`

**Files:**
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

- [x] **Step 1: Add the second test to `WorkoutRepositoryTest`**

Add this test method inside the `WorkoutRepositoryTest` class, after the first test:

```kotlin
    @Test
    fun applyManualBaselineOverrides_logs_MANUAL_OVERRIDE_row() = runBlocking {
        db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(MuscleGroup.BACK, 80f))
        val sessionId = db.workoutSessionDao().insert(WorkoutSession(startTime = 1000L))

        repository.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.BACK to 90f))

        val logs = db.baselineChangeLogDao().getForSession(sessionId)
        assertEquals(1, logs.size)
        with(logs[0]) {
            assertEquals(MuscleGroup.BACK, muscleGroup)
            assertEquals(80f, previousBaseline)
            assertEquals(90f, newBaseline)
            assertEquals(BaselineChangeReason.MANUAL_OVERRIDE, changeReason)
            assertEquals(sessionId, this.sessionId)
            assertNull(feedbacks)
        }
    }
```

- [x] **Step 2: Run the test and confirm it fails to compile or fails at runtime**

```
./gradlew :app:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest#applyManualBaselineOverrides_logs_MANUAL_OVERRIDE_row
```

Expected: compile error — `applyManualBaselineOverrides` does not exist yet on `WorkoutRepository`.

---

### Task 8: Implement `applyManualBaselineOverrides`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`

- [x] **Step 1: Add the new method to `WorkoutRepository`**

Add this method after `applySessionProgression`, before `seedInitialWeights`:

```kotlin
    suspend fun applyManualBaselineOverrides(sessionId: Long, overrides: Map<MuscleGroup, Float>) {
        for ((muscleGroup, newBaseline) in overrides) {
            val previous = db.muscleGroupStrengthDao().get(muscleGroup)?.baselineWeight ?: 0f
            db.muscleGroupStrengthDao().upsert(MuscleGroupStrength(muscleGroup = muscleGroup, baselineWeight = newBaseline))
            db.baselineChangeLogDao().insert(
                BaselineChangeLog(
                    sessionId = sessionId,
                    muscleGroup = muscleGroup,
                    previousBaseline = previous,
                    newBaseline = newBaseline,
                    changeReason = BaselineChangeReason.MANUAL_OVERRIDE,
                    timestamp = System.currentTimeMillis(),
                )
            )
        }
    }
```

- [x] **Step 2: Run both tests and confirm they pass**

```
./gradlew :app:connectedAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest
```

Expected: `BUILD SUCCESSFUL`, both tests PASSED

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt
git commit -m "feat: add applyManualBaselineOverrides with MANUAL_OVERRIDE logging"
```

---

### Task 9: Wire `WorkoutSessionController` to use `applyManualBaselineOverrides`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`

- [x] **Step 1: Remove the `MuscleGroupStrength` import**

Delete this line from the imports:

```kotlin
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
```

- [x] **Step 2: Replace the raw DAO loop in `startFirstExercise`**

Find this block inside the `scope.launch { }` in `startFirstExercise`:

```kotlin
            for ((muscle, baseline) in plan.strengthOverrides) {
                database.muscleGroupStrengthDao().upsert(MuscleGroupStrength(muscle, baseline))
            }
            val now = System.currentTimeMillis()
            sessionStartTime = now
            val sessionId = database.workoutSessionDao().insert(
                WorkoutSession(startTime = now, locationId = sessionLocationId)
            )
```

Replace with (note: session is created first so its ID is available for the log):

```kotlin
            val now = System.currentTimeMillis()
            sessionStartTime = now
            val sessionId = database.workoutSessionDao().insert(
                WorkoutSession(startTime = now, locationId = sessionLocationId)
            )
            repository.applyManualBaselineOverrides(sessionId, plan.strengthOverrides)
```

- [x] **Step 3: Build and verify**

```
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` — no references to `MuscleGroupStrength` or the removed DAO call remain.

- [x] **Step 4: Run the full unit test suite**

```
./gradlew :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`, all tests pass.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt
git commit -m "refactor: route manual baseline overrides through WorkoutRepository"
```
