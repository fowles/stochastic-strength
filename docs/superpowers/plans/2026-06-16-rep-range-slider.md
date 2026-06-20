# Rep Range Slider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add a `RangeSlider` to the workout plan preview so users can pick a range of session reps; every change re-rolls the rep target (preferring "round" numbers) and re-prices the existing exercise list, persisting the range on `UserProfile`.

**Architecture:** A pure-Kotlin `RepRangePicker` object exposes `candidates(min,max)` and `pick(min,max,Random)`. `WorkoutPlanner` gets a `repriceForReps(plan, repMin, repMax)` method that picks new reps and re-runs the existing `withWeight` per row. `WorkoutSessionController` exposes `setRepRange(min,max)`; `WorkoutViewModel` persists the range via `UserProfile` (schema v14 → v15). `PlanPreviewContent` renders a second `RangeSlider` below the existing exercise-count `Slider`.

**Tech Stack:** Kotlin, Jetpack Compose Material3 (`RangeSlider`), Room (Migration), AndroidX test (instrumented migration test), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-06-16-rep-range-slider-design.md`

---

## File Structure

**Created:**
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/RepRangePicker.kt` — pure picker.
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/RepRangePickerTest.kt` — JVM tests for picker.

**Modified:**
- `app/src/main/java/io/github/fowles/stochastic_strength/data/model/UserProfile.kt` — add two nullable Int fields.
- `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt` — bump version 14 → 15, add `MIGRATION_14_15`.
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt` — add range-aware `generateWorkout` overload + `repriceForReps`; remove the random default from the old signature.
- `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt` — add `repriceForReps_*` tests.
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt` — add `preferredRepMin/Max` fields, accept them in `initializeSession`, add `setRepRange`.
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt` — load/persist rep range; expose `setRepRange`.
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt` — pass `onSetRepRange` to `PlanPreviewContent`.
- `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/PlanPreviewContent.kt` — add `RangeSlider` row + callback parameter.
- `app/src/androidTest/java/io/github/fowles/stochastic_strength/data/MigrationTest.kt` — add `migrate14To15_addsRepRangeColumns` test.

---

## Task 1: `RepRangePicker` — failing test, then minimal impl

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/RepRangePicker.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/RepRangePickerTest.kt`

- [x] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/RepRangePickerTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RepRangePickerTest {

    @Test
    fun candidates_5to10_matchesLegacyDistribution() {
        assertEquals(listOf(5, 8, 10), RepRangePicker.candidates(5, 10))
    }

    @Test
    fun candidates_1to20_returnsAllRoundReps() {
        assertEquals(
            listOf(1, 2, 3, 5, 8, 10, 12, 15, 18, 20),
            RepRangePicker.candidates(1, 20),
        )
    }

    @Test
    fun candidates_singleValueRange_returnsSingleton() {
        assertEquals(listOf(1), RepRangePicker.candidates(1, 1))
        assertEquals(listOf(4), RepRangePicker.candidates(4, 4))
        assertEquals(listOf(20), RepRangePicker.candidates(20, 20))
    }

    @Test
    fun candidates_noRoundInRange_returnsEndpointsOnly() {
        assertEquals(listOf(6, 7), RepRangePicker.candidates(6, 7))
    }

    @Test
    fun candidates_endpointsRound_noOtherRoundsInside_returnsEndpoints() {
        assertEquals(listOf(3, 5), RepRangePicker.candidates(3, 5))
    }

    @Test
    fun candidates_nonRoundEndpoints_includesEndpointsAndInnerRounds() {
        assertEquals(listOf(4, 5, 8, 9), RepRangePicker.candidates(4, 9))
    }

    @Test
    fun candidates_2to18_returnsRoundEndpointsAndInnerRounds() {
        assertEquals(listOf(2, 3, 5, 8, 10, 12, 15, 18), RepRangePicker.candidates(2, 18))
    }

    @Test
    fun pick_onlyReturnsValuesFromCandidates_andCoversAllCandidates() {
        val random = Random(0)
        val expected = RepRangePicker.candidates(4, 9).toSet()
        val seen = mutableSetOf<Int>()
        repeat(2_000) {
            val v = RepRangePicker.pick(4, 9, random)
            assertTrue("pick($v) not in candidates $expected", v in expected)
            seen += v
        }
        assertEquals("pick should cover every candidate at least once", expected, seen)
    }

    @Test
    fun pick_singletonRange_alwaysReturnsThatValue() {
        val random = Random(0)
        repeat(50) { assertEquals(7, RepRangePicker.pick(7, 7, random)) }
    }
}
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.RepRangePickerTest"`

Expected: COMPILATION FAILURE — `RepRangePicker` does not exist.

- [x] **Step 3: Write minimal implementation**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/RepRangePicker.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import kotlin.random.Random

object RepRangePicker {
    val ROUND_REPS: List<Int> = listOf(1, 2, 3, 5, 8, 10, 12, 15, 18, 20)

    fun candidates(min: Int, max: Int): List<Int> {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        val rounds = ROUND_REPS.filter { it in lo..hi }
        return (rounds + lo + hi).distinct().sorted()
    }

    fun pick(min: Int, max: Int, random: Random): Int =
        candidates(min, max).random(random)
}
```

- [x] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.RepRangePickerTest"`

Expected: PASS (all 9 tests).

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/RepRangePicker.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/RepRangePickerTest.kt
git commit -m "feat(domain): RepRangePicker prefers round reps but always includes extrema"
```

---

## Task 2: `WorkoutPlanner.repriceForReps` — failing test

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt`

- [x] **Step 1: Add a failing test for `repriceForReps`**

Append these three tests to `WorkoutPlannerTest.kt` after the existing `generateWorkout_*` tests (around line 113, before the `pickReplacement / pickAdditional` section):

```kotlin
    // ──────────────────────────────────────────────────────────────────────
    // repriceForReps
    // ──────────────────────────────────────────────────────────────────────

    @Test
    fun repriceForReps_preservesExerciseListInOrder() {
        val ex1 = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val ex2 = exercise(2L, "Ex2", MuscleGroup.BACK)
        val strengths = strengthsFor(MuscleGroup.CHEST to 100f, MuscleGroup.BACK to 80f)
        val p = planner(listOf(ex1, ex2), strengths)

        val original = p.generateWorkout(sessionReps = 5)
        val repriced = p.repriceForReps(original, repMin = 8, repMax = 8)

        assertEquals(
            original.exercises.map { it.exercise.id },
            repriced.exercises.map { it.exercise.id },
        )
        assertEquals(2, repriced.exercises.size)
    }

    @Test
    fun repriceForReps_singletonRange_setsSessionRepsAndRecomputesWeight() {
        val baseline = 100f
        val ex = exercise(1L, "Barbell Bench Press", MuscleGroup.CHEST)
        val strengths = strengthsFor(MuscleGroup.CHEST to baseline)
        val p = planner(listOf(ex), strengths)

        val original = p.generateWorkout(sessionReps = 5)
        val repriced = p.repriceForReps(original, repMin = 10, repMax = 10)

        assertEquals(10, repriced.sessionReps)
        val pe = repriced.exercises.single()
        assertEquals(10, pe.sessionReps)
        val expected = WeightFormatter.round(
            DefaultProgressionEngine.fromOneRepMax(baseline * 1.0f, 10),
            WeightUnit.KG,
        )
        assertEquals(expected, pe.sessionWeight, 0.01f)
    }

    @Test
    fun repriceForReps_timedExerciseStaysAtSixtyRepsZeroWeight() {
        val timed = exercise(1L, isTimed = true)
        val p = planner(listOf(timed))

        val original = p.generateWorkout(sessionReps = 5)
        val repriced = p.repriceForReps(original, repMin = 8, repMax = 8)

        val pe = repriced.exercises.single()
        assertEquals(60, pe.sessionReps)
        assertEquals(0f, pe.sessionWeight, 0f)
        assertTrue(pe.warmupSets.isEmpty())
    }
```

- [x] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest.repriceForReps*"`

Expected: COMPILATION FAILURE — `repriceForReps` does not exist.

- [x] **Step 3: Commit the failing test**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt
git commit -m "test(planner): pin repriceForReps behavior (failing)"
```

---

## Task 3: `WorkoutPlanner.repriceForReps` and range-aware `generateWorkout` — implementation

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`

- [x] **Step 1: Replace `generateWorkout` and add `repriceForReps`**

Currently `WorkoutPlanner.kt:46` reads:

```kotlin
    fun generateWorkout(sessionReps: Int = progressionEngine.repOptions.random(random)): WorkoutPlan {
        val plannable = availableExercises.filter { muscleGroupRested(it) }
        val exercises = WorkoutGenerator.generate(WorkoutGenerator.Input(plannable, random))
            .map { withWeight(it, sessionReps) }
        return WorkoutPlan(exercises = exercises, locationId = locationId, sessionReps = sessionReps)
    }
```

Replace with these three methods (keeping the single-int form, adding the range form, and adding `repriceForReps`):

```kotlin
    fun generateWorkout(sessionReps: Int): WorkoutPlan {
        val plannable = availableExercises.filter { muscleGroupRested(it) }
        val exercises = WorkoutGenerator.generate(WorkoutGenerator.Input(plannable, random))
            .map { withWeight(it, sessionReps) }
        return WorkoutPlan(exercises = exercises, locationId = locationId, sessionReps = sessionReps)
    }

    fun generateWorkout(repMin: Int, repMax: Int): WorkoutPlan =
        generateWorkout(sessionReps = RepRangePicker.pick(repMin, repMax, random))

    fun repriceForReps(plan: WorkoutPlan, repMin: Int, repMax: Int): WorkoutPlan {
        val sessionReps = RepRangePicker.pick(repMin, repMax, random)
        val newExercises = plan.exercises.map { withWeight(it, sessionReps) }
        return plan.copy(exercises = newExercises, sessionReps = sessionReps)
    }
```

Note the default-argument form (`= progressionEngine.repOptions.random(random)`) is removed — all callers must pass either `sessionReps` or `(repMin, repMax)`.

- [x] **Step 2: Run repriceForReps tests to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest.repriceForReps*"`

Expected: PASS (3 tests).

- [x] **Step 3: Run full planner test class for regression**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`

Expected: PASS (all existing tests still green — the existing tests all call `generateWorkout(sessionReps = ...)` explicitly, so removing the default is fine).

If any test fails because it called `generateWorkout()` with no argument: update that test to pass an explicit `sessionReps = 5` (or whatever value makes the assertion still hold).

- [x] **Step 4: Run full unit test suite for cross-module regressions**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS. If any other test was relying on the random default of `generateWorkout()` (search the codebase for `generateWorkout()` with no args):

```bash
grep -rn "generateWorkout()" app/src
```

For each hit, pass an explicit `sessionReps` argument. Re-run.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt
git commit -m "feat(planner): repriceForReps + range-aware generateWorkout overload"
```

---

## Task 4: `UserProfile` data class — add fields

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/UserProfile.kt`

- [x] **Step 1: Add the two nullable Int fields**

Replace the file contents with:

```kotlin
package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Long = 1,
    val sex: Sex,
    val strengthLevel: StrengthLevel,
    val weightUnit: WeightUnit,
    val preferredExerciseCount: Int? = null,
    val preferredRepMin: Int? = null,
    val preferredRepMax: Int? = null,
)
```

- [x] **Step 2: Build to confirm it compiles in isolation**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD FAILURE — Room schema mismatch: `expected version 14 but found new entity columns`. (Or a similar Room schema-validation error caught at code-generation time.)

This is expected because we have not bumped the DB version yet. Continue to the next task.

- [x] **Step 3: Commit (with caveat: build is broken until Task 5 lands)**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/model/UserProfile.kt
git commit -m "feat(data): UserProfile gains preferredRepMin/Max fields"
```

(The build-broken state is brief — Task 5 lands immediately after. If executing in a single batch, prefer combining Tasks 4 and 5 into one commit instead.)

---

## Task 5: `AppDatabase` — version bump and migration

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt`

- [x] **Step 1: Bump `version` and add `MIGRATION_14_15`**

In `AppDatabase.kt`, find the `@Database(... version = 14, ...)` annotation (line 39) and change:

```kotlin
    version = 14,
```

to:

```kotlin
    version = 15,
```

Then, after `MIGRATION_13_14` (line 300-306), add:

```kotlin
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_profile ADD COLUMN preferredRepMin INTEGER")
                db.execSQL("ALTER TABLE user_profile ADD COLUMN preferredRepMax INTEGER")
            }
        }
```

Then, in the `.addMigrations(...)` call near the bottom (line 326-329), append `MIGRATION_14_15`:

```kotlin
                .addMigrations(
                    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                    MIGRATION_14_15,
                )
```

- [x] **Step 2: Build to confirm compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS. Room will regenerate the schema export to `app/schemas/.../15.json`.

- [x] **Step 3: Verify exported schema appeared**

Run: `ls -la app/schemas/io.github.fowles.stochastic_strength.data.AppDatabase/`

Expected output includes `14.json` and a new `15.json`.

If `15.json` is missing, ensure `exportSchema = true` is set on `@Database` (it already is on line 40) and rerun `./gradlew :app:assembleDebug` to force generation.

- [x] **Step 4: Run unit tests to verify nothing JVM-side broke**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS.

- [x] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt \
        app/schemas/io.github.fowles.stochastic_strength.data.AppDatabase/15.json
git commit -m "feat(data): schema v14->v15 adds user_profile.preferredRepMin/Max"
```

---

## Task 6: `MigrationTest` — instrumented test for v14 → v15

**Files:**
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/data/MigrationTest.kt`

- [x] **Step 1: Add the migration test**

Append to `MigrationTest.kt` (inside the existing `class MigrationTest`):

```kotlin
    @Test
    fun migrate14To15_addsRepRangeColumns_preservesExistingRow() {
        val dbName14 = "migration-test-db-14"
        context.deleteDatabase(dbName14)

        // Minimal v14 user_profile schema (matches v12+ shape, see MIGRATION_11_12 step 1).
        val v14UserProfile = """
            CREATE TABLE IF NOT EXISTS `user_profile` (
                `id` INTEGER PRIMARY KEY NOT NULL,
                `sex` TEXT NOT NULL,
                `strengthLevel` TEXT NOT NULL,
                `weightUnit` TEXT NOT NULL,
                `preferredExerciseCount` INTEGER
            )
        """.trimIndent()

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName14)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(14) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(v14UserProfile)
                        db.execSQL(
                            "INSERT INTO user_profile (id, sex, strengthLevel, weightUnit, preferredExerciseCount) " +
                                "VALUES (1, 'MALE', 'INTERMEDIATE', 'KG', 5)"
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()

        // Open through Room so MIGRATION_14_15 fires.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName14)
            .addMigrations(AppDatabase.MIGRATION_14_15)
            .build()
        try {
            runBlocking {
                val profile = db.userProfileDao().getProfile()
                assertNotNull(profile)
                assertEquals(WeightUnit.KG, profile!!.weightUnit)
                assertEquals(5, profile.preferredExerciseCount)
                assertNull(profile.preferredRepMin)
                assertNull(profile.preferredRepMax)
            }
        } finally {
            db.close()
            context.deleteDatabase(dbName14)
        }
    }
```

If the existing `MigrationTest` does not already expose `MIGRATION_14_15` (it should because `AppDatabase.Companion.MIGRATION_14_15` is `internal`), confirm visibility — `internal` is fine because the test lives in the same module.

- [x] **Step 2: Run the instrumented test**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.data.MigrationTest.migrate14To15_addsRepRangeColumns_preservesExistingRow"`

Expected: PASS. Emulator must be running.

If the emulator is not running, this is the one place where the user must run it manually (or start an emulator first). Continue but note the gap when reporting.

- [x] **Step 3: Commit**

```bash
git add app/src/androidTest/java/io/github/fowles/stochastic_strength/data/MigrationTest.kt
git commit -m "test(data): instrumented migration test for v14->v15"
```

---

## Task 7: `WorkoutSessionController` — accept range, expose `setRepRange`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`

- [x] **Step 1: Add cached `preferredRepMin/Max` fields and update `initializeSession` signature**

In `WorkoutSessionController.kt` around line 49-52, add two new fields right after the existing `private var sessionLocationId`:

```kotlin
    private var weightUnit: WeightUnit = WeightUnit.KG
    private var planner: WorkoutPlanner? = null
    private var sessionStartTime = 0L
    private var sessionLocationId: Long? = null
    private var preferredRepMin: Int = 5
    private var preferredRepMax: Int = 10
```

Then, change the `initializeSession` signature (currently lines 69-82) to accept the range, and use the range-aware `generateWorkout`:

```kotlin
    suspend fun initializeSession(
        locationId: Long?,
        locationName: String?,
        preferredExerciseCount: Int,
        preferredRepMin: Int,
        preferredRepMax: Int,
        weightUnit: WeightUnit,
    ) {
        this.weightUnit = weightUnit
        this.sessionLocationId = locationId
        this.preferredRepMin = preferredRepMin
        this.preferredRepMax = preferredRepMax
        val p = repository.buildPlanner(locationId, weightUnit)
        planner = p
        val plan = p.generateWorkout(repMin = preferredRepMin, repMax = preferredRepMax)
        setState(WorkoutState.PlanPreview(plan = plan, locationName = locationName))
        adjustExerciseCount(preferredExerciseCount)
    }
```

- [x] **Step 2: Add the `setRepRange` method**

Add this method right after `adjustExerciseCount` (currently lines 140-160):

```kotlin
    fun setRepRange(repMin: Int, repMax: Int) {
        addExerciseJob?.cancel()
        preferredRepMin = repMin
        preferredRepMax = repMax
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val p = planner ?: return
        val newPlan = p.repriceForReps(preview.plan, repMin, repMax)
        setState(preview.copy(plan = newPlan))
    }
```

- [x] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD FAILURE — `WorkoutViewModel.initializeSession(...)` call site (in `init` block) now has too few args.

That's expected. Task 8 fixes the call site.

- [x] **Step 4: (Defer commit until Task 8 to keep build green per commit)**

---

## Task 8: `WorkoutViewModel` — load/persist range, wire `setRepRange`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`

- [x] **Step 1: Add cached range fields, load from profile, pass to controller**

In `WorkoutViewModel.kt`, just after `private var preferredExerciseCount` (line 54), add:

```kotlin
    private var preferredExerciseCount: Int = WorkoutGenerator.DEFAULT_EXERCISE_COUNT
    private var preferredRepMin: Int = DEFAULT_REP_MIN
    private var preferredRepMax: Int = DEFAULT_REP_MAX
```

Then add a `companion object` at the bottom of the class with the defaults:

```kotlin
    companion object {
        const val DEFAULT_REP_MIN = 5
        const val DEFAULT_REP_MAX = 10
    }
```

In the `init` block, update the profile-loading section (around lines 57-64) to:

```kotlin
        viewModelScope.launch {
            val profile = app.database.userProfileDao().getProfile()
            _weightUnit.value = profile?.weightUnit ?: WeightUnit.KG
            preferredExerciseCount = profile?.preferredExerciseCount ?: WorkoutGenerator.DEFAULT_EXERCISE_COUNT
            preferredRepMin = profile?.preferredRepMin ?: DEFAULT_REP_MIN
            preferredRepMax = profile?.preferredRepMax ?: DEFAULT_REP_MAX
            val locationId = resolveLocation()
            val locationName = locationId?.let { app.database.knownLocationDao().getById(it)?.name }
            controller.initializeSession(
                locationId = locationId,
                locationName = locationName,
                preferredExerciseCount = preferredExerciseCount,
                preferredRepMin = preferredRepMin,
                preferredRepMax = preferredRepMax,
                weightUnit = _weightUnit.value,
            )
        }
```

- [x] **Step 2: Add `setRepRange` method on the ViewModel**

Add this method right after `setExerciseCount` (currently lines 96-105):

```kotlin
    fun setRepRange(repMin: Int, repMax: Int) {
        controller.setRepRange(repMin, repMax)
        if (repMin != preferredRepMin || repMax != preferredRepMax) {
            preferredRepMin = repMin
            preferredRepMax = repMax
            viewModelScope.launch {
                val profile = app.database.userProfileDao().getProfile() ?: return@launch
                app.database.userProfileDao().insert(
                    profile.copy(preferredRepMin = repMin, preferredRepMax = repMax)
                )
            }
        }
    }
```

- [x] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

- [x] **Step 4: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS.

- [x] **Step 5: Commit Tasks 7 and 8 together**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt
git commit -m "feat(workout): controller+VM thread rep range through initializeSession and setRepRange"
```

---

## Task 9: `PlanPreviewContent` — add the `RangeSlider` row

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/PlanPreviewContent.kt`

- [x] **Step 1: Add the new callback parameter and the RangeSlider**

In `PlanPreviewContent.kt`, update the function signature (lines 55-65) to add `onSetRepRange`:

```kotlin
@Composable
internal fun PlanPreviewContent(
    state: WorkoutState.PlanPreview,
    weightUnit: WeightUnit,
    onStart: () -> Unit,
    onReplace: (index: Int, reason: ExerciseRemovalReason) -> Unit,
    onSetExerciseCount: (Int) -> Unit,
    onSetRepRange: (repMin: Int, repMax: Int) -> Unit,
    onAdjustWeight: (index: Int, delta: Float) -> Unit,
    onEditLocation: (locationId: Long) -> Unit,
    onExerciseTap: (exerciseId: Long) -> Unit,
) {
```

Update the imports at the top (after line 31, the existing `Slider` import):

```kotlin
import androidx.compose.material3.RangeSlider
```

The slider's initial value must come from the *currently configured* range, not a hard-coded default. Since `WorkoutPlan` exposes only `sessionReps` (not the active range), we thread `repMin`/`repMax` through `WorkoutState.PlanPreview` first, then read them in the composable.

In `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutState.kt`, the current `PlanPreview` (lines 24-27) is:

```kotlin
    data class PlanPreview(
        val plan: WorkoutPlan,
        val locationName: String? = null,
    ) : WorkoutState
```

Replace with:

```kotlin
    data class PlanPreview(
        val plan: WorkoutPlan,
        val locationName: String? = null,
        val repMin: Int = 5,
        val repMax: Int = 10,
    ) : WorkoutState
```

Then in `WorkoutSessionController.kt`, update `initializeSession` to populate them:

```kotlin
        setState(WorkoutState.PlanPreview(
            plan = plan,
            locationName = locationName,
            repMin = preferredRepMin,
            repMax = preferredRepMax,
        ))
```

And in `setRepRange`:

```kotlin
    fun setRepRange(repMin: Int, repMax: Int) {
        addExerciseJob?.cancel()
        preferredRepMin = repMin
        preferredRepMax = repMax
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val p = planner ?: return
        val newPlan = p.repriceForReps(preview.plan, repMin, repMax)
        setState(preview.copy(plan = newPlan, repMin = repMin, repMax = repMax))
    }
```

Now back in `PlanPreviewContent.kt`, set the local state from `state.repMin` / `state.repMax`:

```kotlin
    var sliderValue by remember { mutableFloatStateOf(plan.exercises.size.toFloat()) }
    var repRangeValue by remember(state.repMin, state.repMax) {
        mutableStateOf(state.repMin.toFloat()..state.repMax.toFloat())
    }
```

Add the new `RangeSlider` row directly after the existing exercise-count Slider row (after line 121, the closing of the existing `Row { ... }`):

```kotlin
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Fewer reps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RangeSlider(
                value = repRangeValue,
                onValueChange = { repRangeValue = it },
                onValueChangeFinished = {
                    onSetRepRange(
                        repRangeValue.start.roundToInt(),
                        repRangeValue.endInclusive.roundToInt(),
                    )
                },
                valueRange = REP_RANGE_MIN.toFloat()..REP_RANGE_MAX.toFloat(),
                steps = REP_RANGE_MAX - REP_RANGE_MIN - 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(
                "More reps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
```

And add these constants at the bottom of the file (next to `MAX_EXERCISE_COUNT`):

```kotlin
private const val REP_RANGE_MIN = 1
private const val REP_RANGE_MAX = 20
```

(The `steps` value is the number of *discrete positions between* the endpoints, so for `[1, 20]` with every integer addressable we want `20 - 1 - 1 = 18` steps. This matches the existing exercise-count slider's pattern of `MAX_EXERCISE_COUNT - 2` for endpoints inclusive.)

- [x] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: BUILD FAILURE — `WorkoutScreen.kt` calls `PlanPreviewContent(...)` without `onSetRepRange`.

That's expected. Task 10 wires the screen.

- [x] **Step 3: (Defer commit until Task 10)**

---

## Task 10: `WorkoutScreen` — wire `onSetRepRange`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt`

- [x] **Step 1: Pass the callback into `PlanPreviewContent`**

In `WorkoutScreen.kt` around line 87-98, add `onSetRepRange = viewModel::setRepRange` to the `PlanPreviewContent` call:

```kotlin
                is WorkoutState.PlanPreview -> PlanPreviewContent(
                    state = s,
                    weightUnit = weightUnit,
                    onStart = viewModel::startFirstExercise,
                    onReplace = viewModel::replaceExercise,
                    onSetExerciseCount = viewModel::setExerciseCount,
                    onSetRepRange = viewModel::setRepRange,
                    onAdjustWeight = viewModel::adjustExerciseWeight,
                    onEditLocation = { locationId ->
                        onEditLocation(locationId)
                    },
                    onExerciseTap = onExerciseTap,
                )
```

- [x] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`

Expected: PASS.

- [x] **Step 3: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS.

- [x] **Step 4: Build full debug APK**

Run: `./gradlew :app:assembleDebug`

Expected: PASS.

- [x] **Step 5: Commit Tasks 9 and 10 together**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/PlanPreviewContent.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutState.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt
git commit -m "feat(ui): RangeSlider for rep target on workout plan preview"
```

---

## Task 11: End-to-end verification on a device or emulator

**Files:** None — manual verification.

- [x] **Step 1: Install the debug build**

Run: `./gradlew :app:installDebug`

Expected: install succeeds.

- [x] **Step 2: Launch and verify the plan preview shows two sliders**

1. Open the app, tap "Start Workout".
2. The plan preview should show:
   - The existing "Shorter / Longer" slider (single thumb).
   - A new "Fewer reps / More reps" range slider (two thumbs), defaulting to `[5, 10]`.
3. The per-exercise rows should each show `3 sets × N reps`, where `N ∈ {5, 8, 10}`.

- [x] **Step 3: Drag the range slider and confirm re-roll**

1. Drag the right thumb to roughly `15`, release.
2. The per-exercise rows should update — `N` should now be drawn from `{5, 8, 10, 12, 15}`.
3. Drag both thumbs together to `8`, release.
4. Every exercise row should now show `3 sets × 8 reps` and weights should be lower than at `5 reps`.

- [x] **Step 4: Kill and relaunch — confirm persistence**

1. Force-stop the app, then re-open it and start another workout.
2. The new range slider thumbs should be at the last values you set (not back to `[5, 10]`).

- [x] **Step 5: Report**

If any of the above fails, fix it. If all pass, the feature is verified. No commit needed for this task.

---

## Task 12: Final full-suite regression check

**Files:** None — verification.

- [x] **Step 1: Run full unit test suite**

Run: `./gradlew :app:testDebugUnitTest`

Expected: PASS.

- [x] **Step 2: Run lint**

Run: `./gradlew :app:lint`

Expected: PASS (or only pre-existing warnings).

- [x] **Step 3: Run instrumented migration tests**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.data.MigrationTest"`

Expected: PASS.

- [x] **Step 4: Update memory**

Add a one-line entry to `/Users/mfk/.claude/projects/-Users-mfk-dev-stochastic-strength/memory/MEMORY.md` under the existing list:

```markdown
- [Rep range slider](project_rep_range_slider.md) — SHIPPED 2026-06-16: user-chosen [min,max] reps range on plan preview; picker prefers round numbers, extrema always selectable; persisted on UserProfile (v14→v15)
```

Create `/Users/mfk/.claude/projects/-Users-mfk-dev-stochastic-strength/memory/project_rep_range_slider.md`:

```markdown
---
name: project-rep-range-slider
description: Rep range slider feature on workout plan preview, shipped 2026-06-16
metadata:
  type: project
---

Rep range slider on the workout plan preview lets the user pick `[min, max]`
reps in `[1, 20]`. Default `[5, 10]`. Every change re-rolls `sessionReps`
from `RepRangePicker.candidates(min,max) = (ROUND_REPS ∩ [min,max]) ∪
{min, max}`, uniformly. Range persists on `UserProfile.preferredRepMin/Max`
(schema v14 → v15).

**Why:** Gives the user control over training stimulus (low reps for
strength vs higher reps for hypertrophy) without per-exercise complexity.

**How to apply:** When changing rep-related logic on the plan preview,
remember that `WorkoutPlan.sessionReps` is now a derived roll from the
user's range, not a 3-value constant. `DefaultProgressionEngine.repOptions`
is no longer the source of truth at workout-generation time.
```

No commit step here — memory files are user-private and not in the repo.

---

## Spec Coverage Self-Check

| Spec section | Where implemented |
|---|---|
| UI: second slider on PlanPreview | Task 9 |
| UI: `RangeSlider`, `valueRange = 1f..20f`, integer thumbs | Task 9 |
| UI: fires on release only | Task 9 (`onValueChangeFinished`) |
| UI: per-row label updates after re-roll | Implicit (existing label reads `planned.sessionReps`) |
| Default range `[5, 10]` | Task 8 (`DEFAULT_REP_MIN/MAX`); also displayed as initial slider state |
| Picker: `ROUND_REPS = {1,2,3,5,8,10,12,15,18,20}` | Task 1 |
| Picker: candidates = `(rounds ∩ [a,b]) ∪ {a,b}`, deduped, uniform | Task 1 |
| Picker: worked examples (incl. degenerate) | Task 1 tests |
| `WorkoutPlanner.generateWorkout(repMin, repMax)` | Task 3 |
| `WorkoutPlanner.repriceForReps(plan, repMin, repMax)` | Task 3 |
| Removal of random-default `generateWorkout()` | Task 3 |
| `WorkoutSessionController.initializeSession(...)` accepts range | Task 7 |
| `WorkoutSessionController.setRepRange(min, max)` | Task 7 |
| Cancel `addExerciseJob` on range change | Task 7 |
| State guard (PlanPreview only) | Task 7 |
| `WorkoutViewModel` loads/persists range | Task 8 |
| `WorkoutScreen` wires `onSetRepRange` | Task 10 |
| `UserProfile` gains `preferredRepMin/Max` | Task 4 |
| Schema bump 14 → 15 + `MIGRATION_14_15` | Task 5 |
| Migration test | Task 6 |
| Unit tests for picker | Task 1 |
| Unit tests for `repriceForReps` | Task 2/3 |
| Edge case: min == max | Task 1 (`pick_singletonRange_*`) |
| Edge case: no rounds in range | Task 1 (`candidates_noRoundInRange_*`) |
| Interactions with `pickAdditional`/`pickReplacement` | No changes needed — they read `plan.sessionReps` |
| Out of scope: per-exercise rep targets | Not in plan |

---

## Final Notes

- Tasks 4 and 5 must land together (or as one commit) to keep the build green. Same for Tasks 7-8, and 9-10. If executing one task at a time with required green commits, batch those pairs.
- The `WorkoutState.PlanPreview` change in Task 9 is small but important — it lets the slider survive recomposition through plan re-rolls without losing thumb positions.
- The instrumented migration test (Task 6) needs an emulator. If unavailable, note the gap when reporting completion; the unit and JVM tests still establish the schema + picker correctness.
