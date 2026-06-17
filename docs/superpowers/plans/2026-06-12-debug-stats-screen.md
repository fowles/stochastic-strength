# Debug & Advanced Stats Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add an About screen reachable from Home with a "Debug and Advanced Stats" button leading to a read-only inspection screen for per-muscle baselines and per-exercise coefficients (chart + change-event list per detail screen).

**Architecture:** Four new screens (`AboutScreen`, `DebugStatsScreen`, `MuscleBaselineDetailScreen`, `ExerciseCoefficientDetailScreen`) live under `ui/about/` and `ui/debug/`. Data comes from new read-only methods on `WorkoutRepository` that query existing `coefficient_change_log` and `baseline_change_log` tables — no schema migration. The single-series subset of the existing Vico chart is factored into a reusable `DebugLineChart`. The `StrengthGrid` from `HistoryScreen` is extracted into a generic shared component used by both `HistoryScreen` and `DebugStatsScreen`.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Room, Vico (charts), AndroidX Navigation Compose. Tests for repository methods use Room's `inMemoryDatabaseBuilder` via `androidTest` (instrumented). ViewModels and composables follow the codebase's existing no-unit-test convention; verification is via the `run` skill at the end.

**Spec:** `docs/superpowers/specs/2026-06-12-debug-stats-screen-design.md`

---

## File Structure

**New files (production):**

- `app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientRow.kt` — domain DTO returned by repository
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/components/StrengthGrid.kt` — extracted from `HistoryScreen`, generalised
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/DebugLineChart.kt` — single-series Vico chart
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/DebugStatsScreen.kt`
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/DebugStatsViewModel.kt`
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailScreen.kt`
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt`
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailScreen.kt`
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt`
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/about/AboutScreen.kt`

**New files (test):**

- `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt`

**Modified files:**

- `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/CoefficientChangeLogDao.kt` — add two queries
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` — add 5 new read methods
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryScreen.kt` — remove the now-extracted `StrengthGrid`/`StrengthCard` and import the shared version
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/home/HomeScreen.kt` — add "About" button
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/AppNavigation.kt` — wire 4 new routes

---

### Task 1: Add `getMostRecent` and `getForExercise` DAO queries

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/CoefficientChangeLogDao.kt`

These are tested through repository methods in later tasks. The DAO change is small and shipped together with the repo method that consumes each query.

- [x] **Step 1: Add the two queries to `CoefficientChangeLogDao.kt`**

Replace the file contents with:

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

    @Query("SELECT * FROM coefficient_change_log ORDER BY id ASC")
    suspend fun getAll(): List<CoefficientChangeLog>

    @Query("SELECT * FROM coefficient_change_log WHERE id IN (SELECT MAX(id) FROM coefficient_change_log GROUP BY exerciseId)")
    suspend fun getLatestPerExercise(): List<CoefficientChangeLog>

    @Query("SELECT * FROM coefficient_change_log ORDER BY computedAt DESC LIMIT :limit")
    suspend fun getMostRecent(limit: Int): List<CoefficientChangeLog>

    @Query("SELECT * FROM coefficient_change_log WHERE exerciseId = :exerciseId ORDER BY computedAt ASC")
    suspend fun getForExercise(exerciseId: Long): List<CoefficientChangeLog>
}
```

- [x] **Step 2: Confirm the project still compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/dao/CoefficientChangeLogDao.kt
git commit -m "feat(data): add CoefficientChangeLog DAO queries for debug screen"
```

---

### Task 2: Add `CoefficientRow` domain DTO

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientRow.kt`

This is a plain data class returned by repository methods in Tasks 3–4.

- [x] **Step 1: Create `CoefficientRow.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain

data class CoefficientRow(
    val exerciseId: Long,
    val exerciseName: String,
    val currentCoefficient: Float,
    /** Populated only for "recently changed" rows; null otherwise. */
    val previousCoefficient: Float?,
    /** Null when the exercise has no log entry yet (only seed coefficient). */
    val computedAt: Long?,
    /** Null when the exercise has no log entry yet. */
    val heuristicName: String?,
    /** Populated only for "recently changed" rows; first 80 chars, newlines flattened. */
    val heuristicMetadataPreview: String?,
)
```

- [x] **Step 2: Confirm compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/CoefficientRow.kt
git commit -m "feat(domain): add CoefficientRow DTO for debug screen"
```

---

### Task 3: Add `getAllCoefficientRows` repository method

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt`

Returns one `CoefficientRow` per exercise (all exercises, including disliked). Sorted alphabetically by name. Exercises with no log row receive seed values and `computedAt = null`, `heuristicName = null`, `previousCoefficient = null`, `heuristicMetadataPreview = null`.

- [x] **Step 1: Write the failing test**

Create `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.BaselineChangeLog
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.CoefficientChangeLog
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutRepositoryDebugTest {

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
    fun getAllCoefficientRows_returns_seed_for_exercises_with_no_log() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))

        val rows = repository.getAllCoefficientRows()

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("Barbell Bench Press", row.exerciseName)
        // ExerciseCoefficients seeds Barbell Bench Press at 1.0
        assertEquals(1.0f, row.currentCoefficient, 0.001f)
        assertNull(row.computedAt)
        assertNull(row.heuristicName)
        assertNull(row.previousCoefficient)
        assertNull(row.heuristicMetadataPreview)
    }

    @Test
    fun getAllCoefficientRows_uses_log_value_when_present() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))
        val exerciseId = db.exerciseDao().getAll().single().id
        db.coefficientChangeLogDao().insert(
            CoefficientChangeLog(
                exerciseId = exerciseId,
                previousCoefficient = 1.0f,
                coefficient = 0.85f,
                heuristicName = "test-heuristic",
                heuristicMetadata = "metadata-string",
                computedAt = 5000L,
            )
        )

        val row = repository.getAllCoefficientRows().single()

        assertEquals(0.85f, row.currentCoefficient, 0.001f)
        assertEquals(5000L, row.computedAt)
        assertEquals("test-heuristic", row.heuristicName)
        // previous and metadata preview are reserved for the "recent changes" variant
        assertNull(row.previousCoefficient)
        assertNull(row.heuristicMetadataPreview)
    }

    @Test
    fun getAllCoefficientRows_sorts_alphabetically_and_includes_disliked() = runBlocking {
        db.exerciseDao().insertAll(listOf(
            Exercise(name = "Pull-Up", primaryMuscle = MuscleGroup.BACK, equipment = Equipment.BODYWEIGHT, isDisliked = true),
            Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        ))

        val rows = repository.getAllCoefficientRows()

        assertEquals(listOf("Barbell Bench Press", "Pull-Up"), rows.map { it.exerciseName })
    }
}
```

- [x] **Step 2: Run the test to verify it fails to compile**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: Compile error — `getAllCoefficientRows` unresolved.

- [x] **Step 3: Implement `getAllCoefficientRows` in `WorkoutRepository.kt`**

Add the following just before the closing `}` of the class (alongside other read methods near `getMuscleGroupStrengths`):

```kotlin
suspend fun getAllCoefficientRows(): List<CoefficientRow> {
    val allExercises = db.exerciseDao().getAll()
    val latestByExercise = db.coefficientChangeLogDao().getLatestPerExercise()
        .associateBy { it.exerciseId }
    return allExercises
        .map { exercise ->
            val log = latestByExercise[exercise.id]
            val seed = coefficientSource.get(exercise) ?: 0f
            CoefficientRow(
                exerciseId = exercise.id,
                exerciseName = exercise.name,
                currentCoefficient = log?.coefficient ?: seed,
                previousCoefficient = null,
                computedAt = log?.computedAt,
                heuristicName = log?.heuristicName,
                heuristicMetadataPreview = null,
            )
        }
        .sortedBy { it.exerciseName }
}
```

Add the import at the top of `WorkoutRepository.kt`:

```kotlin
import io.github.fowles.stochastic_strength.domain.CoefficientRow
```

(Same package; this import isn't strictly required but adding it explicitly keeps grep-ability.)

- [x] **Step 4: Run the new tests on a connected device or emulator**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryDebugTest"`
Expected: 3 tests passing.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt
git commit -m "feat(domain): WorkoutRepository.getAllCoefficientRows"
```

---

### Task 4: Add `getRecentCoefficientChanges` repository method

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt`

Returns the N most-recent rows from the coefficient log, joined to exercise names. Populates `previousCoefficient`, `heuristicMetadataPreview`, and `heuristicName`.

- [x] **Step 1: Write the failing tests**

Append to `WorkoutRepositoryDebugTest.kt` (inside the class, before the closing `}`):

```kotlin
@Test
fun getRecentCoefficientChanges_returns_newest_first_limited() = runBlocking {
    db.exerciseDao().insertAll(listOf(
        Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        Exercise(name = "Squat",                primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
        Exercise(name = "Deadlift",             primaryMuscle = MuscleGroup.HAMSTRINGS, equipment = Equipment.BARBELL),
    ))
    val exercises = db.exerciseDao().getAll()
    val bench = exercises.first { it.name == "Barbell Bench Press" }
    val squat = exercises.first { it.name == "Squat" }
    val dead = exercises.first { it.name == "Deadlift" }
    db.coefficientChangeLogDao().insert(CoefficientChangeLog(
        exerciseId = bench.id, previousCoefficient = 1.0f, coefficient = 0.95f,
        heuristicName = "h", heuristicMetadata = null, computedAt = 1000L,
    ))
    db.coefficientChangeLogDao().insert(CoefficientChangeLog(
        exerciseId = squat.id, previousCoefficient = 1.0f, coefficient = 0.90f,
        heuristicName = "h", heuristicMetadata = null, computedAt = 3000L,
    ))
    db.coefficientChangeLogDao().insert(CoefficientChangeLog(
        exerciseId = dead.id, previousCoefficient = 1.0f, coefficient = 0.92f,
        heuristicName = "h", heuristicMetadata = null, computedAt = 2000L,
    ))

    val recent = repository.getRecentCoefficientChanges(limit = 2)

    assertEquals(2, recent.size)
    assertEquals(listOf("Squat", "Deadlift"), recent.map { it.exerciseName })
    assertEquals(1.0f, recent[0].previousCoefficient!!, 0.001f)
}

@Test
fun getRecentCoefficientChanges_populates_metadata_preview_with_truncation() = runBlocking {
    db.exerciseDao().insertAll(listOf(
        Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
    ))
    val bench = db.exerciseDao().getAll().single()
    val longMeta = "x".repeat(200) + "\n" + "y".repeat(50)
    db.coefficientChangeLogDao().insert(CoefficientChangeLog(
        exerciseId = bench.id, previousCoefficient = 1.0f, coefficient = 0.9f,
        heuristicName = "h", heuristicMetadata = longMeta, computedAt = 1000L,
    ))

    val row = repository.getRecentCoefficientChanges(limit = 2).single()

    // First 80 chars of the flattened metadata
    assertEquals("x".repeat(80), row.heuristicMetadataPreview)
}
```

- [x] **Step 2: Run to verify it fails**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: Compile error — `getRecentCoefficientChanges` unresolved.

- [x] **Step 3: Implement `getRecentCoefficientChanges`**

Add to `WorkoutRepository.kt`:

```kotlin
suspend fun getRecentCoefficientChanges(limit: Int = 2): List<CoefficientRow> {
    val rows = db.coefficientChangeLogDao().getMostRecent(limit)
    if (rows.isEmpty()) return emptyList()
    val exerciseIds = rows.map { it.exerciseId }.distinct()
    val exercisesById = exerciseIds
        .mapNotNull { id -> db.exerciseDao().getById(id)?.let { id to it } }
        .toMap()
    return rows.mapNotNull { log ->
        val exercise = exercisesById[log.exerciseId] ?: return@mapNotNull null
        CoefficientRow(
            exerciseId = exercise.id,
            exerciseName = exercise.name,
            currentCoefficient = log.coefficient,
            previousCoefficient = log.previousCoefficient,
            computedAt = log.computedAt,
            heuristicName = log.heuristicName,
            heuristicMetadataPreview = log.heuristicMetadata
                ?.replace('\n', ' ')
                ?.take(80),
        )
    }
}
```

- [x] **Step 4: Run the tests**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryDebugTest"`
Expected: 5 tests passing (3 from Task 3 + 2 new).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt
git commit -m "feat(domain): WorkoutRepository.getRecentCoefficientChanges"
```

---

### Task 5: Add `getBaselineEvents` repository method

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt`

Returns all `BaselineChangeLog` rows for one muscle group, ordered ascending by timestamp.

- [x] **Step 1: Write the failing test**

Append to `WorkoutRepositoryDebugTest.kt`:

```kotlin
@Test
fun getBaselineEvents_filters_by_muscle_group_and_orders_ascending() = runBlocking {
    db.baselineChangeLogDao().insert(BaselineChangeLog(
        sessionId = 1L, muscleGroup = MuscleGroup.CHEST,
        previousBaseline = 100f, newBaseline = 102f,
        changeReason = BaselineChangeReason.PROGRESSION,
        timestamp = 3000L,
    ))
    db.baselineChangeLogDao().insert(BaselineChangeLog(
        sessionId = 2L, muscleGroup = MuscleGroup.BACK,
        previousBaseline = 80f, newBaseline = 82f,
        changeReason = BaselineChangeReason.PROGRESSION,
        timestamp = 4000L,
    ))
    db.baselineChangeLogDao().insert(BaselineChangeLog(
        sessionId = 3L, muscleGroup = MuscleGroup.CHEST,
        previousBaseline = 102f, newBaseline = 104f,
        changeReason = BaselineChangeReason.PROGRESSION,
        timestamp = 5000L,
    ))

    val events = repository.getBaselineEvents(MuscleGroup.CHEST)

    assertEquals(2, events.size)
    assertEquals(listOf(3000L, 5000L), events.map { it.timestamp })
}
```

- [x] **Step 2: Run to verify it fails**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: Compile error — `getBaselineEvents` unresolved.

- [x] **Step 3: Implement `getBaselineEvents`**

Add to `WorkoutRepository.kt`:

```kotlin
suspend fun getBaselineEvents(muscleGroup: MuscleGroup): List<BaselineChangeLog> =
    db.baselineChangeLogDao().getAll()
        .filter { it.muscleGroup == muscleGroup }
        .sortedBy { it.timestamp }
```

- [x] **Step 4: Run the test**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryDebugTest"`
Expected: 6 tests passing.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt
git commit -m "feat(domain): WorkoutRepository.getBaselineEvents"
```

---

### Task 6: Add `getCoefficientEvents` repository method

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt`

Returns all `CoefficientChangeLog` rows for one exercise via the new DAO query, ordered ascending by `computedAt`.

- [x] **Step 1: Write the failing test**

Append to `WorkoutRepositoryDebugTest.kt`:

```kotlin
@Test
fun getCoefficientEvents_returns_events_for_exercise_ascending() = runBlocking {
    db.exerciseDao().insertAll(listOf(
        Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
        Exercise(name = "Squat",                primaryMuscle = MuscleGroup.QUADS, equipment = Equipment.BARBELL),
    ))
    val exercises = db.exerciseDao().getAll()
    val bench = exercises.first { it.name == "Barbell Bench Press" }
    val squat = exercises.first { it.name == "Squat" }
    db.coefficientChangeLogDao().insert(CoefficientChangeLog(
        exerciseId = bench.id, previousCoefficient = null, coefficient = 0.95f,
        heuristicName = "h", heuristicMetadata = null, computedAt = 3000L,
    ))
    db.coefficientChangeLogDao().insert(CoefficientChangeLog(
        exerciseId = bench.id, previousCoefficient = 0.95f, coefficient = 0.92f,
        heuristicName = "h", heuristicMetadata = null, computedAt = 1000L,
    ))
    db.coefficientChangeLogDao().insert(CoefficientChangeLog(
        exerciseId = squat.id, previousCoefficient = null, coefficient = 0.9f,
        heuristicName = "h", heuristicMetadata = null, computedAt = 2000L,
    ))

    val events = repository.getCoefficientEvents(bench.id)

    assertEquals(2, events.size)
    assertEquals(listOf(1000L, 3000L), events.map { it.computedAt })
}
```

- [x] **Step 2: Run to verify it fails**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: Compile error — `getCoefficientEvents` unresolved.

- [x] **Step 3: Implement `getCoefficientEvents`**

Add to `WorkoutRepository.kt`:

```kotlin
suspend fun getCoefficientEvents(exerciseId: Long): List<CoefficientChangeLog> =
    db.coefficientChangeLogDao().getForExercise(exerciseId)
```

- [x] **Step 4: Run the test**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryDebugTest"`
Expected: 7 tests passing.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt
git commit -m "feat(domain): WorkoutRepository.getCoefficientEvents"
```

---

### Task 7: Add `getSeedCoefficient` repository method

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt`

Exposes the static seed coefficient so the per-exercise detail screen can show "Seed: X" without leaking `CoefficientSource` to the ViewModel.

- [x] **Step 1: Write the failing test**

Append to `WorkoutRepositoryDebugTest.kt`:

```kotlin
@Test
fun getSeedCoefficient_returns_default_from_coefficient_source() = runBlocking {
    db.exerciseDao().insertAll(listOf(
        Exercise(name = "Barbell Bench Press", primaryMuscle = MuscleGroup.CHEST, equipment = Equipment.BARBELL),
    ))
    val bench = db.exerciseDao().getAll().single()

    val seed = repository.getSeedCoefficient(bench)

    // ExerciseCoefficients seeds Barbell Bench Press at 1.0
    assertEquals(1.0f, seed!!, 0.001f)
}
```

- [x] **Step 2: Run to verify it fails**

Run: `./gradlew :app:compileDebugAndroidTestKotlin`
Expected: Compile error — `getSeedCoefficient` unresolved.

- [x] **Step 3: Implement `getSeedCoefficient`**

Add to `WorkoutRepository.kt`:

```kotlin
fun getSeedCoefficient(exercise: Exercise): Float? =
    coefficientSource.get(exercise)
```

- [x] **Step 4: Run the test**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryDebugTest"`
Expected: 8 tests passing.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryDebugTest.kt
git commit -m "feat(domain): WorkoutRepository.getSeedCoefficient"
```

---

### Task 8: Create `DebugLineChart` reusable composable

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/DebugLineChart.kt`

Single-series Vico chart based on the `ExerciseChart` in `ExerciseDetailScreen.kt`. Caller provides points and a Y-axis label formatter. The X-axis interprets timestamps as `ms / 86_400_000L` so the day label formatter matches the existing chart's behaviour.

- [x] **Step 1: Create the file with the chart composable and `DebugChartPoint` data class**

```kotlin
package io.github.fowles.stochastic_strength.ui.debug.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.core.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import androidx.compose.material3.MaterialTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugChartPoint(val timestampMs: Long, val value: Float)

@Composable
internal fun DebugLineChart(
    points: List<DebugChartPoint>,
    yFormatter: (Float) -> String,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineSeries {
                if (points.isNotEmpty()) {
                    series(
                        x = points.map { it.timestampMs / 86_400_000L },
                        y = points.map { it.value },
                    )
                }
            }
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val transparentFill = remember { LineCartesianLayer.LineFill.single(Fill.Transparent) }
    val primaryLine = LineCartesianLayer.rememberLine(
        fill = transparentFill,
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.point(
                rememberShapeComponent(fill(primaryColor), CorneredShape.Pill),
                size = 8.dp,
            )
        ),
    )
    val lineProvider = remember(primaryLine) {
        LineCartesianLayer.LineProvider.series(listOf(primaryLine))
    }

    val rangeProvider = remember {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                val padding = if (minY == maxY) maxOf(minY * 0.10, 0.05) else (maxY - minY) * 0.15
                return minY - padding
            }
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                val padding = if (minY == maxY) maxOf(maxY * 0.10, 0.05) else (maxY - minY) * 0.15
                return maxY + padding
            }
        }
    }

    val yValueFormatter = remember(yFormatter) {
        CartesianValueFormatter { _, value, _ -> yFormatter(value.toFloat()) }
    }
    val dateFormatter = remember {
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
        CartesianValueFormatter { _, value, _ ->
            sdf.format(Date(value.toLong() * 86_400_000L))
        }
    }

    val scrollState = rememberVicoScrollState(
        initialScroll = Scroll.Absolute.End,
        autoScroll = Scroll.Absolute.End,
        autoScrollCondition = AutoScrollCondition.OnModelGrowth,
    )

    val marker = rememberMarker()

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = lineProvider,
                pointSpacing = 0.dp,
                rangeProvider = rangeProvider,
            ),
            startAxis = VerticalAxis.rememberStart(valueFormatter = yValueFormatter),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = dateFormatter,
                labelRotationDegrees = 45f,
            ),
            marker = marker,
        ),
        modelProducer = modelProducer,
        scrollState = scrollState,
        modifier = modifier,
    )
}

@Composable
private fun rememberMarker(): DefaultCartesianMarker {
    val labelBackground = rememberShapeComponent(
        fill = fill(MaterialTheme.colorScheme.surface),
        shape = CorneredShape.Pill,
        strokeFill = fill(MaterialTheme.colorScheme.outline),
        strokeThickness = 1.dp,
    )
    val label = rememberTextComponent(
        color = MaterialTheme.colorScheme.onSurface,
        padding = insets(8.dp, 4.dp),
        background = labelBackground,
    )
    val guideline = rememberAxisGuidelineComponent()
    val sdf = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val dateFormatter = remember(sdf) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            sdf.format(Date((targets.firstOrNull()?.x?.toLong() ?: 0L) * 86_400_000L))
        }
    }
    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = dateFormatter,
        guideline = guideline,
        indicatorSize = 0.dp,
    )
}
```

- [x] **Step 2: Confirm compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/components/DebugLineChart.kt
git commit -m "feat(ui): add DebugLineChart reusable single-series Vico chart"
```

---

### Task 9: Extract `StrengthGrid` into shared component

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/components/StrengthGrid.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryScreen.kt`

Replaces the `private` `StrengthGrid`/`StrengthCard` composables in `HistoryScreen.kt` with a generic version in a shared component file.

- [x] **Step 1: Create the shared `StrengthGrid.kt`**

```kotlin
package io.github.fowles.stochastic_strength.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter

@Composable
internal fun <T : Any> StrengthGrid(
    strengths: List<MuscleGroupStrength>,
    tapTargets: Map<MuscleGroup, T>,
    weightUnit: WeightUnit,
    onTap: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        strengths.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pair.forEach { strength ->
                    StrengthCard(
                        strength = strength,
                        tapTarget = tapTargets[strength.muscleGroup],
                        weightUnit = weightUnit,
                        onTap = onTap,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (pair.size == 1) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun <T : Any> StrengthCard(
    strength: MuscleGroupStrength,
    tapTarget: T?,
    weightUnit: WeightUnit,
    onTap: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    val cardContent: @Composable ColumnScope.() -> Unit = {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = strength.muscleGroup.displayName(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = WeightFormatter.format(strength.baselineWeight, weightUnit),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (tapTarget != null) {
        Card(onClick = { onTap(tapTarget) }, modifier = modifier, colors = cardColors, content = cardContent)
    } else {
        Card(modifier = modifier, colors = cardColors, content = cardContent)
    }
}
```

- [x] **Step 2: Update `HistoryScreen.kt` to use the shared component**

Open `app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryScreen.kt` and make these changes:

(a) Add the import (alphabetically):

```kotlin
import io.github.fowles.stochastic_strength.ui.components.StrengthGrid
```

(b) Remove `data.model.MuscleGroupStrength` if it is no longer otherwise referenced — it still is (it appears in `HistoryViewModel`'s state shape, but not directly here after the refactor), so check the existing imports. Leave `MuscleGroup` and `MuscleGroupStrength` imports alone if any local code still uses them. The simplest path: leave all imports as they are. The lint rule will warn about unused imports if any exist; address only if you see one.

(c) Replace the `StrengthGrid` call site inside the `LazyColumn` (the existing `item { StrengthGrid(...) }` block that currently passes `referenceExerciseIds`) with:

```kotlin
            item {
                StrengthGrid(
                    strengths = state.muscleStrengths,
                    tapTargets = state.referenceExerciseIds,
                    weightUnit = state.weightUnit,
                    onTap = onExerciseTap,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
```

(d) Delete the private `StrengthGrid` composable (the `@Composable private fun StrengthGrid(...)` block) and the private `StrengthCard` composable (the `@Composable private fun StrengthCard(...)` block) from `HistoryScreen.kt`. After deleting them, remove these imports — they were only used by the deleted composables:

```kotlin
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.WeightFormatter
```

If the compiler then complains about a still-unused import (e.g. `Arrangement`), delete that too.

- [x] **Step 3: Confirm compile and test**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

Verify by running the app's existing unit tests in case any touch History:

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [x] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/components/StrengthGrid.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/history/HistoryScreen.kt
git commit -m "refactor(ui): extract StrengthGrid into shared component with generic tap target"
```

---

### Task 10: Create `MuscleBaselineDetailViewModel`

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt`

Loads the muscle's current baseline, all baseline events (newest-first for the UI list), and the chart points (oldest-first with a synthetic anchor at `firstEvent.previousBaseline`).

- [x] **Step 1: Create the ViewModel**

```kotlin
package io.github.fowles.stochastic_strength.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.ui.debug.components.DebugChartPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BaselineEvent(
    val sessionId: Long,
    val timestamp: Long,
    val previousBaseline: Float,
    val newBaseline: Float,
    val reason: BaselineChangeReason,
    val feedbacks: List<SetFeedback>,
    val sessionReps: Int?,
    val minReductionFraction: Float?,
)

data class MuscleBaselineDetailState(
    val loading: Boolean = true,
    val muscleGroup: MuscleGroup,
    val currentBaseline: Float = 0f,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val events: List<BaselineEvent> = emptyList(),
    val chartPoints: List<DebugChartPoint> = emptyList(),
)

class MuscleBaselineDetailViewModel(
    application: Application,
    private val muscleGroup: MuscleGroup,
) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = app.workoutRepository

    private val _state = MutableStateFlow(MuscleBaselineDetailState(muscleGroup = muscleGroup))
    val state: StateFlow<MuscleBaselineDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            val weightUnit = profile?.weightUnit ?: WeightUnit.KG
            val currentBaseline = repository.getMuscleGroupStrengths()
                .firstOrNull { it.muscleGroup == muscleGroup }
                ?.baselineWeight ?: 0f
            val logs = repository.getBaselineEvents(muscleGroup)

            val events = logs.asReversed().map { log ->
                BaselineEvent(
                    sessionId = log.sessionId,
                    timestamp = log.timestamp,
                    previousBaseline = log.previousBaseline,
                    newBaseline = log.newBaseline,
                    reason = log.changeReason,
                    feedbacks = parseFeedbacks(log.feedbacks),
                    sessionReps = log.sessionReps,
                    minReductionFraction = log.minReductionFraction,
                )
            }

            val chartPoints: List<DebugChartPoint> = if (logs.isEmpty()) emptyList() else buildList {
                val first = logs.first()
                add(DebugChartPoint(first.timestamp - 86_400_000L, first.previousBaseline))
                logs.forEach { add(DebugChartPoint(it.timestamp, it.newBaseline)) }
            }

            _state.value = MuscleBaselineDetailState(
                loading = false,
                muscleGroup = muscleGroup,
                currentBaseline = currentBaseline,
                weightUnit = weightUnit,
                events = events,
                chartPoints = chartPoints,
            )
        }
    }

    private fun parseFeedbacks(csv: String?): List<SetFeedback> =
        csv?.split(',')
            ?.mapNotNull { token -> runCatching { SetFeedback.valueOf(token.trim()) }.getOrNull() }
            ?: emptyList()

    companion object {
        fun factory(muscleGroup: MuscleGroup): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[APPLICATION_KEY] ?: error("No application")
                    return MuscleBaselineDetailViewModel(app, muscleGroup) as T
                }
            }
    }
}
```

- [x] **Step 2: Confirm compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailViewModel.kt
git commit -m "feat(ui): MuscleBaselineDetailViewModel"
```

---

### Task 11: Create `MuscleBaselineDetailScreen`

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailScreen.kt`

Top-bar with muscle name and a Back arrow. Header card with the current baseline. Chart section. Change-events list (newest first).

- [x] **Step 1: Create the screen**

```kotlin
package io.github.fowles.stochastic_strength.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.ui.debug.components.DebugLineChart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuscleBaselineDetailScreen(muscleGroup: MuscleGroup, onBack: () -> Unit) {
    val viewModel: MuscleBaselineDetailViewModel =
        viewModel(factory = MuscleBaselineDetailViewModel.factory(muscleGroup))
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.muscleGroup.displayName()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Current baseline",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = WeightFormatter.format(state.currentBaseline, state.weightUnit),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            }

            item { SectionHeader("Baseline over time") }

            item {
                if (state.chartPoints.isEmpty()) {
                    EmptyHistoryPlaceholder()
                } else {
                    DebugLineChart(
                        points = state.chartPoints,
                        yFormatter = { value -> WeightFormatter.format(value, state.weightUnit) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            item { SectionHeader("Change events") }

            if (state.events.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No change events yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.events, key = { it.sessionId.toString() + ":" + it.timestamp }) { event ->
                    BaselineEventRow(event, state.weightUnit)
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun EmptyHistoryPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("No history yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val DATETIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")

@Composable
private fun BaselineEventRow(event: BaselineEvent, weightUnit: WeightUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = Instant.ofEpochMilli(event.timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(DATETIME_FORMATTER),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = event.reason.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${WeightFormatter.format(event.previousBaseline, weightUnit)} → " +
                WeightFormatter.format(event.newBaseline, weightUnit),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (event.feedbacks.isNotEmpty()) {
            val repsSuffix = event.sessionReps?.let { " · reps: $it" } ?: ""
            Text(
                text = "Feedbacks: " + event.feedbacks.joinToString(", ") { it.name } + repsSuffix,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (event.minReductionFraction != null) {
            Text(
                text = "Reduction floor: %.0f%%".format(event.minReductionFraction * 100f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

- [x] **Step 2: Confirm compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/MuscleBaselineDetailScreen.kt
git commit -m "feat(ui): MuscleBaselineDetailScreen"
```

---

### Task 12: Create `ExerciseCoefficientDetailViewModel`

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt`

- [x] **Step 1: Create the ViewModel**

```kotlin
package io.github.fowles.stochastic_strength.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.ui.debug.components.DebugChartPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CoefficientEvent(
    val computedAt: Long,
    val previousCoefficient: Float?,
    val coefficient: Float,
    val heuristicName: String,
    val heuristicMetadata: String?,
)

data class ExerciseCoefficientDetailState(
    val loading: Boolean = true,
    val exercise: Exercise? = null,
    val currentCoefficient: Float = 0f,
    val seedCoefficient: Float? = null,
    val events: List<CoefficientEvent> = emptyList(),
    val chartPoints: List<DebugChartPoint> = emptyList(),
)

class ExerciseCoefficientDetailViewModel(
    application: Application,
    private val exerciseId: Long,
) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = app.workoutRepository

    private val _state = MutableStateFlow(ExerciseCoefficientDetailState())
    val state: StateFlow<ExerciseCoefficientDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val exercise = repository.getExerciseById(exerciseId) ?: run {
                _state.value = ExerciseCoefficientDetailState(loading = false)
                return@launch
            }
            val seed = repository.getSeedCoefficient(exercise)
            val logs = repository.getCoefficientEvents(exerciseId)
            val currentCoefficient = logs.lastOrNull()?.coefficient ?: seed ?: 0f

            val events = logs.asReversed().map { log ->
                CoefficientEvent(
                    computedAt = log.computedAt,
                    previousCoefficient = log.previousCoefficient,
                    coefficient = log.coefficient,
                    heuristicName = log.heuristicName,
                    heuristicMetadata = log.heuristicMetadata,
                )
            }

            val chartPoints: List<DebugChartPoint> = if (logs.isEmpty()) emptyList() else buildList {
                val first = logs.first()
                if (first.previousCoefficient != null) {
                    add(DebugChartPoint(first.computedAt - 86_400_000L, first.previousCoefficient))
                }
                logs.forEach { add(DebugChartPoint(it.computedAt, it.coefficient)) }
            }

            _state.value = ExerciseCoefficientDetailState(
                loading = false,
                exercise = exercise,
                currentCoefficient = currentCoefficient,
                seedCoefficient = seed,
                events = events,
                chartPoints = chartPoints,
            )
        }
    }

    companion object {
        fun factory(exerciseId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                    val app = extras[APPLICATION_KEY] ?: error("No application")
                    return ExerciseCoefficientDetailViewModel(app, exerciseId) as T
                }
            }
    }
}
```

- [x] **Step 2: Confirm compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt
git commit -m "feat(ui): ExerciseCoefficientDetailViewModel"
```

---

### Task 13: Create `ExerciseCoefficientDetailScreen`

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailScreen.kt`

Header card with current coefficient + (optional) seed. Chart. Event list (newest first). Each event row shows timestamp, heuristic name, prev→new, and a monospace metadata block (omitted if metadata is null).

- [x] **Step 1: Create the screen**

```kotlin
package io.github.fowles.stochastic_strength.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.ui.debug.components.DebugLineChart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseCoefficientDetailScreen(exerciseId: Long, onBack: () -> Unit) {
    val viewModel: ExerciseCoefficientDetailViewModel =
        viewModel(factory = ExerciseCoefficientDetailViewModel.factory(exerciseId))
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.exercise?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Current coefficient",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "%.3f".format(state.currentCoefficient),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        if (state.seedCoefficient != null) {
                            Text(
                                text = "Seed: %.3f".format(state.seedCoefficient),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { SectionHeader("Coefficient over time") }

            item {
                if (state.chartPoints.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("No coefficient changes yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    DebugLineChart(
                        points = state.chartPoints,
                        yFormatter = { value -> "%.3f".format(value) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            item { SectionHeader("Change events") }

            if (state.events.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No change events yet",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.events, key = { it.computedAt }) { event ->
                    CoefficientEventRow(event)
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

private val DATETIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")

@Composable
private fun CoefficientEventRow(event: CoefficientEvent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = Instant.ofEpochMilli(event.computedAt)
                    .atZone(ZoneId.systemDefault())
                    .format(DATETIME_FORMATTER),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = event.heuristicName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val transition = if (event.previousCoefficient != null) {
            "%.3f → %.3f".format(event.previousCoefficient, event.coefficient)
        } else {
            "%.3f".format(event.coefficient)
        }
        Text(text = transition, style = MaterialTheme.typography.bodyLarge)
        if (event.heuristicMetadata != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = event.heuristicMetadata,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
```

- [x] **Step 2: Confirm compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailScreen.kt
git commit -m "feat(ui): ExerciseCoefficientDetailScreen"
```

---

### Task 14: Create `DebugStatsViewModel`

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/DebugStatsViewModel.kt`

Loads current weight unit, muscle strengths (sorted by ordinal), and the two coefficient lists.

- [x] **Step 1: Create the ViewModel**

```kotlin
package io.github.fowles.stochastic_strength.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.fowles.stochastic_strength.StochasticStrengthApp
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.CoefficientRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DebugStatsState(
    val loading: Boolean = true,
    val weightUnit: WeightUnit = WeightUnit.KG,
    val muscleStrengths: List<MuscleGroupStrength> = emptyList(),
    val recentCoefficientChanges: List<CoefficientRow> = emptyList(),
    val allCoefficients: List<CoefficientRow> = emptyList(),
)

class DebugStatsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StochasticStrengthApp
    private val repository = app.workoutRepository

    private val _state = MutableStateFlow(DebugStatsState())
    val state: StateFlow<DebugStatsState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            val weightUnit = profile?.weightUnit ?: WeightUnit.KG
            val muscleStrengths = repository.getMuscleGroupStrengths()
                .sortedBy { it.muscleGroup.ordinal }
            val recent = repository.getRecentCoefficientChanges(limit = 2)
            val all = repository.getAllCoefficientRows()
            _state.value = DebugStatsState(
                loading = false,
                weightUnit = weightUnit,
                muscleStrengths = muscleStrengths,
                recentCoefficientChanges = recent,
                allCoefficients = all,
            )
        }
    }
}
```

- [x] **Step 2: Confirm compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/DebugStatsViewModel.kt
git commit -m "feat(ui): DebugStatsViewModel"
```

---

### Task 15: Create `DebugStatsScreen`

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/DebugStatsScreen.kt`

Top-bar "Debug and Advanced Stats". `StrengthGrid` with `MuscleGroup`-keyed tap targets. "Recently Changed Coefficients" section (omitted if empty). "All Exercises" section.

- [x] **Step 1: Create the screen**

```kotlin
package io.github.fowles.stochastic_strength.ui.debug

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.CoefficientRow
import io.github.fowles.stochastic_strength.ui.components.StrengthGrid

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugStatsScreen(
    onMuscleTap: (MuscleGroup) -> Unit,
    onExerciseTap: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: DebugStatsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug and Advanced Stats") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("Muscle Baselines") }
            item {
                StrengthGrid(
                    strengths = state.muscleStrengths,
                    tapTargets = MuscleGroup.entries.associateWith { it },
                    weightUnit = state.weightUnit,
                    onTap = onMuscleTap,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (state.recentCoefficientChanges.isNotEmpty()) {
                item { SectionHeader("Recently Changed Coefficients") }
                items(state.recentCoefficientChanges, key = { "recent-" + it.exerciseId }) { row ->
                    RecentCoefficientRow(row, onClick = { onExerciseTap(row.exerciseId) })
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }

            item { SectionHeader("All Exercises") }
            items(state.allCoefficients, key = { "all-" + it.exerciseId }) { row ->
                AlphabeticalCoefficientRow(row, onClick = { onExerciseTap(row.exerciseId) })
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun AlphabeticalCoefficientRow(row: CoefficientRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row {
            Text(row.exerciseName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("%.3f".format(row.currentCoefficient), style = MaterialTheme.typography.bodyLarge)
        }
        Text(
            text = row.heuristicName ?: "not yet computed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RecentCoefficientRow(row: CoefficientRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row {
            Text(row.exerciseName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text("%.3f".format(row.currentCoefficient), style = MaterialTheme.typography.bodyLarge)
        }
        val timestamp = row.computedAt?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        } ?: ""
        val prev = row.previousCoefficient?.let { "%.3f → %.3f".format(it, row.currentCoefficient) }
            ?: "%.3f".format(row.currentCoefficient)
        Text(
            text = if (timestamp.isNotEmpty()) "$prev · $timestamp" else prev,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val heuristicLine = listOfNotNull(row.heuristicName, row.heuristicMetadataPreview).joinToString(" · ")
        if (heuristicLine.isNotEmpty()) {
            Text(
                text = heuristicLine,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
```

- [x] **Step 2: Confirm compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/DebugStatsScreen.kt
git commit -m "feat(ui): DebugStatsScreen landing"
```

---

### Task 16: Create `AboutScreen`

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/about/AboutScreen.kt`

Static content: header, "How it works" blurb, GitHub link button, spacer, "Debug and Advanced Stats" button at the bottom.

- [x] **Step 1: Create the screen**

```kotlin
package io.github.fowles.stochastic_strength.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.BuildConfig

private const val GITHUB_URL = "https://github.com/fowles/stochastic-strength"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onDebug: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Stochastic Strength", style = MaterialTheme.typography.headlineLarge)
            Text(
                text = "Random workouts. Real progress.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))
            Text("How it works", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Every muscle group has a baseline — the app's estimate of your " +
                    "1-rep max for that group. All your working weights are derived from it.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Every exercise has a coefficient — how hard that lift is for you " +
                    "relative to the baseline. After each session, your feedback nudges " +
                    "the baseline up or down, and over time the app learns your " +
                    "individual coefficients from your performance.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("View on GitHub")
            }

            Spacer(Modifier.height(48.dp))
            OutlinedButton(
                onClick = onDebug,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Debug and Advanced Stats")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
```

- [x] **Step 2: Confirm compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/about/AboutScreen.kt
git commit -m "feat(ui): AboutScreen"
```

---

### Task 17: Wire all 4 new routes and add the Home "About" button

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/AppNavigation.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/home/HomeScreen.kt`

- [x] **Step 1: Add `onAbout` parameter and About button to `HomeScreen`**

Edit `HomeScreen.kt`:

(a) Add `onAbout: () -> Unit` to the `HomeScreen` signature:

```kotlin
fun HomeScreen(
    onStartWorkout: () -> Unit,
    onHistory: () -> Unit,
    onExercises: () -> Unit,
    onLocations: () -> Unit,
    onAbout: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
)
```

(b) Pass it through to `ReadyContent`:

```kotlin
                HomeState.Ready -> ReadyContent(
                    onStart = { ... },
                    onHistory = onHistory,
                    onExercises = onExercises,
                    onLocations = onLocations,
                    onAbout = onAbout,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                )
```

(c) Add `onAbout` parameter to `ReadyContent`:

```kotlin
private fun ReadyContent(
    onStart: () -> Unit,
    onHistory: () -> Unit,
    onExercises: () -> Unit,
    onLocations: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
)
```

(d) Add the button below "Locations" in `ReadyContent` (just before the closing `}` of the `Column`):

```kotlin
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onAbout, modifier = Modifier.fillMaxWidth()) {
            Text("About")
        }
```

- [x] **Step 2: Add the routes in `AppNavigation.kt`**

Add the imports (alphabetically with the rest):

```kotlin
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.ui.about.AboutScreen
import io.github.fowles.stochastic_strength.ui.debug.DebugStatsScreen
import io.github.fowles.stochastic_strength.ui.debug.ExerciseCoefficientDetailScreen
import io.github.fowles.stochastic_strength.ui.debug.MuscleBaselineDetailScreen
```

Update the `home` composable call to wire `onAbout`:

```kotlin
        composable("home") {
            HomeScreen(
                onStartWorkout = { navController.navigate("workout") },
                onHistory = { navController.navigate("history") },
                onExercises = { navController.navigate("exercises") },
                onLocations = { navController.navigate("locations") },
                onAbout = { navController.navigate("about") },
            )
        }
```

Add the four new `composable(...)` blocks anywhere inside the `NavHost { }` block (e.g. after the `home` block):

```kotlin
        composable("about") {
            AboutScreen(
                onDebug = { navController.navigate("debug") },
                onBack = { navController.popBackStack() },
            )
        }
        composable("debug") {
            DebugStatsScreen(
                onMuscleTap = { muscle -> navController.navigate("debug/muscle/${muscle.name}") },
                onExerciseTap = { exerciseId -> navController.navigate("debug/coefficient/$exerciseId") },
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "debug/muscle/{muscleGroup}",
            arguments = listOf(navArgument("muscleGroup") { type = NavType.StringType }),
        ) { backStackEntry ->
            val name = backStackEntry.arguments!!.getString("muscleGroup")!!
            MuscleBaselineDetailScreen(
                muscleGroup = MuscleGroup.valueOf(name),
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "debug/coefficient/{exerciseId}",
            arguments = listOf(navArgument("exerciseId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val exerciseId = backStackEntry.arguments!!.getLong("exerciseId")
            ExerciseCoefficientDetailScreen(
                exerciseId = exerciseId,
                onBack = { navController.popBackStack() },
            )
        }
```

- [x] **Step 3: Confirm compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 4: Confirm the full test suite still passes**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/AppNavigation.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/home/HomeScreen.kt
git commit -m "feat(ui): wire About and Debug routes; add Home About button"
```

---

### Task 18: Manual verification on device

**Files:** none

- [x] **Step 1: Build and install the debug APK**

Run: `./gradlew :app:installDebug`
Expected: APK installed on connected device/emulator.

- [x] **Step 2: Walk the user flow manually**

Use the `run` skill (or open the app manually) and verify:

1. Home → "About" button is visible.
2. About screen shows app name, tagline, version line, "How it works" blurb, "View on GitHub" button, and "Debug and Advanced Stats" button at the bottom.
3. "View on GitHub" opens the GitHub URL in the default browser.
4. Tapping "Debug and Advanced Stats" navigates to the Debug screen.
5. Debug screen shows the muscle baseline grid (10 muscle groups) and the coefficient list. If there are recent changes, the "Recently Changed Coefficients" section appears at the top of the coefficient area with up to 2 rows; otherwise it is hidden.
6. Tapping a muscle card navigates to the muscle baseline detail screen with the current baseline, chart (if events exist), and event list.
7. Tapping a coefficient row navigates to the exercise coefficient detail screen with the current coefficient, optional seed, chart, and event list. Heuristic metadata renders in a monospace block when non-null.
8. Back button returns to the previous screen at each step.
9. Existing History screen still renders correctly (the extracted `StrengthGrid` did not regress its tap behaviour into exercise detail).

If any of these fail, fix the underlying code (don't paper over with workarounds) and re-run the relevant step.

- [x] **Step 3: Run the full instrumented test suite once**

Run: `./gradlew :app:connectedAndroidTest`
Expected: BUILD SUCCESSFUL — all existing + new tests pass.

- [x] **Step 4: Final commit (if any fixes were needed)**

```bash
git add -p
git commit -m "fix: <whatever was fixed during manual verification>"
```

If no fixes were needed, skip this step. No commit-empty.
