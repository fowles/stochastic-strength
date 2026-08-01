# Asymmetric Barbell Exercises + Landmine Press Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Treat single-end-loaded barbell lifts (T-Bar Row, new Landmine Press) as "asymmetric": no plate-per-side breakdown, ordinary percentage-ramp warmups — while keeping them `Equipment.BARBELL` for pooling.

**Architecture:** Add a persisted `isAsymmetric` flag to `Exercise` plus a single derived helper `Exercise.usesBarPlates` (`BARBELL && !isAsymmetric`). Warmup generation and the three plate-breakdown UI call sites read that helper. A Room v17→v18 migration adds the column and flips T-Bar Row; the startup library-sync delivers the new Landmine Press row with the flag preset.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room, JUnit4 (JVM unit tests), AndroidJUnit4 (instrumented migration tests), Gradle.

## Global Constraints

- Package root: `io.github.fowles.stochastic_strength`.
- Real users in production: every DB version bump needs a proper `Migration`; destructive fallback is not configured.
- Equipment MUST stay `Equipment.BARBELL` for T-Bar Row and Landmine Press (per-equipment crossLiftIndependenceEstimate pooling depends on it). Do NOT add an Equipment enum value.
- Version control is jj; Claude commits at each checkpoint, user owns reshape + push. Commit commands below use `git` (works against the colocated repo).
- Build: `./gradlew :app:assembleDebug`. JVM tests: `./gradlew :app:testDebugUnitTest`. Instrumented: `./gradlew :app:connectedAndroidTest` (emulator is typically already running — attempt directly).

---

### Task 1: `isAsymmetric` field + `usesBarPlates` helper

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/Exercise.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/data/model/ExerciseTest.kt` (create)

**Interfaces:**
- Produces:
  - `Exercise` gains `val isAsymmetric: Boolean = false` (declared immediately after `isUnilateral`, before `isTimed`).
  - `val Exercise.usesBarPlates: Boolean` — top-level extension in `Exercise.kt`, returns `equipment == Equipment.BARBELL && !isAsymmetric`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/fowles/stochastic_strength/data/model/ExerciseTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseTest {
    private fun ex(equipment: Equipment, isAsymmetric: Boolean) = Exercise(
        name = "X",
        primaryMuscle = MuscleGroup.BACK,
        equipment = equipment,
        isAsymmetric = isAsymmetric,
    )

    @Test
    fun `symmetric barbell uses bar plates`() {
        assertTrue(ex(Equipment.BARBELL, isAsymmetric = false).usesBarPlates)
    }

    @Test
    fun `asymmetric barbell does not use bar plates`() {
        assertFalse(ex(Equipment.BARBELL, isAsymmetric = true).usesBarPlates)
    }

    @Test
    fun `non-barbell never uses bar plates`() {
        assertFalse(ex(Equipment.DUMBBELL, isAsymmetric = false).usesBarPlates)
    }

    @Test
    fun `isAsymmetric defaults to false`() {
        assertFalse(
            Exercise(name = "X", primaryMuscle = MuscleGroup.BACK, equipment = Equipment.BARBELL)
                .isAsymmetric
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.data.model.ExerciseTest"`
Expected: FAIL — compile error, `isAsymmetric` / `usesBarPlates` unresolved.

- [ ] **Step 3: Add the field and helper**

In `Exercise.kt`, add the field to the data class (after `isUnilateral`):

```kotlin
    val isUnilateral: Boolean = false,
    val isAsymmetric: Boolean = false,
    val isTimed: Boolean = false,
```

Then add a top-level extension below the data class (same file):

```kotlin
/** True when this lift loads a symmetric bar with plates per side (standard warmup + plate breakdown). */
val Exercise.usesBarPlates: Boolean
    get() = equipment == Equipment.BARBELL && !isAsymmetric
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.data.model.ExerciseTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/model/Exercise.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/data/model/ExerciseTest.kt
git commit -m "feat(model): add Exercise.isAsymmetric + usesBarPlates helper"
```

---

### Task 2: Room migration v17 → v18

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt` (bump `version`, add `MIGRATION_17_18`, register it)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/WorkoutBackup.kt:31` (bump `DB_VERSION` 17 → 18 — backup import is dbVersion-pinned and rejects mismatches, so it must track the schema)
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/data/MigrationTest.kt` (add `MIGRATION_17_18` to every forward `addMigrations(...)` list; add a 17→18 test)

**Interfaces:**
- Consumes: `Exercise.isAsymmetric` column (Task 1) — Room validates the post-migration `exercises` schema against the v18 entity.
- Produces: `AppDatabase.MIGRATION_17_18` (public `internal val`, referenced by tests).

- [ ] **Step 1: Write the failing instrumented test**

Add to `MigrationTest.kt` (inside the class). It builds a v17 `exercises` table (no `isAsymmetric`), seeds a T-Bar Row and a plain barbell row, migrates to 18, and asserts the column + the T-Bar flip. Mirrors the `migrate16To17` test's minimal-schema style (only tables Room validates need to exist; the identity_hash is a placeholder because Room validates structurally after migrating):

```kotlin
    @Test
    fun migrate17To18_addsIsAsymmetricAndFlipsTBarRow() {
        val dbName17 = "migration-test-db-17"
        context.deleteDatabase(dbName17)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName17)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(17) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `primaryMuscle` TEXT NOT NULL, `secondaryMuscles` TEXT NOT NULL, `equipment` TEXT NOT NULL, `isDisliked` INTEGER NOT NULL, `isUnilateral` INTEGER NOT NULL, `isTimed` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `known_locations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `location_excluded_exercises` (`locationId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, PRIMARY KEY(`locationId`, `exerciseId`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `workout_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `locationId` INTEGER, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `stravaActivityId` INTEGER)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `workout_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, `setNumber` INTEGER NOT NULL, `targetWeight` REAL NOT NULL, `targetReps` INTEGER NOT NULL, `actualReps` INTEGER, `feedback` TEXT, `completedAt` INTEGER, `durationSeconds` INTEGER)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `user_profile` (`id` INTEGER NOT NULL, `sex` TEXT NOT NULL, `strengthLevel` TEXT NOT NULL, `weightUnit` TEXT NOT NULL, `preferredExerciseCount` INTEGER, `preferredRepMin` INTEGER, `preferredRepMax` INTEGER, `perExerciseSeedsBackfilled` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `baseline_override` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER, `muscleGroup` TEXT NOT NULL, `baselineWeight` REAL NOT NULL, `asOf` INTEGER NOT NULL, `reason` TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_hurt_state` (`exerciseId` INTEGER NOT NULL, `isHurt` INTEGER NOT NULL, `asOf` INTEGER NOT NULL, PRIMARY KEY(`exerciseId`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_strength_override` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER, `exerciseId` INTEGER NOT NULL, `e1rm` REAL NOT NULL, `asOf` INTEGER NOT NULL, `reason` TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '00000000000000000000000000000000')")
                        db.execSQL("INSERT INTO exercises (id, name, primaryMuscle, secondaryMuscles, equipment, isDisliked, isUnilateral, isTimed) VALUES (1, 'T-Bar Row', 'BACK', '', 'BARBELL', 0, 0, 0), (2, 'Barbell Row', 'BACK', '', 'BARBELL', 0, 0, 0)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName17)
            .addMigrations(AppDatabase.MIGRATION_17_18)
            .allowMainThreadQueries()
            .build()
        try {
            db.openHelper.readableDatabase.query("PRAGMA table_info(exercises)").use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
                assertTrue(names.contains("isAsymmetric"))
            }
            db.openHelper.readableDatabase.query(
                "SELECT name, isAsymmetric FROM exercises ORDER BY id"
            ).use { c ->
                val rows = mutableListOf<Pair<String, Int>>()
                while (c.moveToNext()) rows += c.getString(0) to c.getInt(1)
                assertEquals(listOf("T-Bar Row" to 1, "Barbell Row" to 0), rows)
            }
        } finally {
            db.close(); context.deleteDatabase(dbName17)
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.data.MigrationTest.migrate17To18_addsIsAsymmetricAndFlipsTBarRow"`
Expected: FAIL — `MIGRATION_17_18` unresolved (compile) / no migration to v18.

- [ ] **Step 3: Add the migration + bump the version**

In `AppDatabase.kt`, change `version = 17` to `version = 18`. Add the migration to `Companion` (next to `MIGRATION_16_17`):

```kotlin
        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `exercises` ADD COLUMN `isAsymmetric` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `exercises` SET `isAsymmetric` = 1 WHERE `name` = 'T-Bar Row'")
            }
        }
```

Register it in the `addMigrations(...)` list in `buildDatabase`:

```kotlin
                    MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18,
```

Then, in `WorkoutBackup.kt`, bump the backup schema pin so v18 exports import cleanly:

```kotlin
        const val DB_VERSION = 18
```

- [ ] **Step 4: Update the other MigrationTest forward lists**

Every existing test that opens through `Room.databaseBuilder(...)` walks to the current entity version (now 18), so each must register `MIGRATION_17_18` or Room throws "missing migration". Append `AppDatabase.MIGRATION_17_18` to the `addMigrations(...)` call in each of these tests:
- `migrate9To10_addsActualRepsColumnAndPreservesRows` (the multi-line list ending `MIGRATION_16_17,`)
- `migrate10To11Plus_preservesUserProfileRow`
- `migrate14To15_addsRepRangeColumns_preservesExistingRow`
- `migrate11To12_dropsActualRepsBackfilledFromUserProfile`

(The `migrate16To17` test registers only `MIGRATION_16_17`; it now also needs `MIGRATION_17_18` appended so Room can reach v18.) Do NOT modify `createV11DbAndMigrate`, which calls `MIGRATION_11_12.migrate(db)` directly and does not open through Room.

- [ ] **Step 5: Run the full migration suite to verify it passes**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.data.MigrationTest"`
Expected: PASS (all migration tests, including the new 17→18).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/WorkoutBackup.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/data/MigrationTest.kt
git commit -m "feat(db): migrate v17->v18 adding isAsymmetric, flip T-Bar Row"
```

---

### Task 3: Seed data — flag T-Bar Row, add Landmine Press, add coefficient

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/seed/ExerciseLibrary.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseCoefficients.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/data/seed/ExerciseLibraryTest.kt` (create)

**Interfaces:**
- Consumes: `Exercise.isAsymmetric` (Task 1), `ExerciseCoefficients.byName` map.
- Produces: seeded `Landmine Press` (SHOULDERS, asymmetric barbell) with coefficient `0.5`; `T-Bar Row` seed carries `isAsymmetric = true`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/fowles/stochastic_strength/data/seed/ExerciseLibraryTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.data.seed

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.ExerciseCoefficients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseLibraryTest {
    private fun byName(name: String) =
        ExerciseLibrary.exercises.firstOrNull { it.name == name }

    @Test
    fun `T-Bar Row is asymmetric barbell`() {
        val e = byName("T-Bar Row")
        assertNotNull(e)
        assertEquals(Equipment.BARBELL, e!!.equipment)
        assertTrue(e.isAsymmetric)
    }

    @Test
    fun `Landmine Press seeded as asymmetric barbell shoulders`() {
        val e = byName("Landmine Press")
        assertNotNull(e)
        assertEquals(Equipment.BARBELL, e!!.equipment)
        assertEquals(MuscleGroup.SHOULDERS, e.primaryMuscle)
        assertTrue(e.isAsymmetric)
        assertTrue(!e.isUnilateral)
    }

    @Test
    fun `Landmine Press has a coefficient`() {
        val e = byName("Landmine Press")!!
        assertEquals(0.5f, ExerciseCoefficients.get(e))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.data.seed.ExerciseLibraryTest"`
Expected: FAIL — T-Bar Row not asymmetric; Landmine Press absent (null); coefficient null.

- [ ] **Step 3: Update the seed data**

In `ExerciseLibrary.kt`, replace the T-Bar Row line and add Landmine Press directly after it:

```kotlin
        Exercise(name = "T-Bar Row", primaryMuscle = MuscleGroup.BACK, secondaryMuscles = listOf(MuscleGroup.BICEPS), equipment = Equipment.BARBELL, isAsymmetric = true),
        Exercise(name = "Landmine Press", primaryMuscle = MuscleGroup.SHOULDERS, secondaryMuscles = listOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.CORE), equipment = Equipment.BARBELL, isAsymmetric = true),
```

In `ExerciseCoefficients.kt`, add under the SHOULDERS block (e.g. after `"Push Press" to 1.20f,`):

```kotlin
        "Landmine Press"               to 0.5f,
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.data.seed.ExerciseLibraryTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/seed/ExerciseLibrary.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseCoefficients.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/data/seed/ExerciseLibraryTest.kt
git commit -m "feat(seed): flag T-Bar Row asymmetric, add Landmine Press + coefficient"
```

---

### Task 4: Warmup uses the percentage ramp for asymmetric barbells

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt:132-137`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt`

**Interfaces:**
- Consumes: `Exercise.usesBarPlates` (Task 1), existing `WorkoutPlanner.computeWarmupSets(weightKg, exercise)` and private `percentageRampWarmups`.
- Produces: no new symbols — behavior change only.

- [ ] **Step 1: Write the failing test**

Add to `WorkoutPlannerTest.kt` (in the `computeWarmupSets` section). At 100 kg a *symmetric* barbell yields `[20,40,60,80,90]`; an *asymmetric* barbell must instead yield the percentage ramp `[40,60,80]` with reps `[5,3,2]`:

```kotlin
    @Test
    fun `computeWarmupSets asymmetric barbell uses percentage ramp not bar math`() {
        val asymmetric = Exercise(
            id = 1L,
            name = "T-Bar Row",
            primaryMuscle = MuscleGroup.BACK,
            equipment = Equipment.BARBELL,
            isAsymmetric = true,
        )
        val warmups = planner().computeWarmupSets(100f, asymmetric)
        assertEquals(listOf(40, 60, 80), warmups.map { it.weight.roundToInt() })
        assertEquals(listOf(5, 3, 2), warmups.map { it.reps })
    }

    @Test
    fun `computeWarmupSets symmetric barbell still uses bar math`() {
        val symmetric = Exercise(
            id = 2L,
            name = "Barbell Row",
            primaryMuscle = MuscleGroup.BACK,
            equipment = Equipment.BARBELL,
        )
        val warmups = planner().computeWarmupSets(100f, symmetric)
        assertEquals(listOf(20, 40, 60, 80, 90), warmups.map { it.weight.roundToInt() })
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`
Expected: FAIL — asymmetric case currently returns `[20,40,60,80,90]` (bar math), not `[40,60,80]`.

- [ ] **Step 3: Change the warmup branch**

In `WorkoutPlanner.computeWarmupSets`, change the guard so any lift that is not a symmetric-bar lift takes the percentage ramp. Add the import `import io.github.fowles.stochastic_strength.data.model.usesBarPlates` at the top of the file, then:

```kotlin
    fun computeWarmupSets(weightKg: Float, exercise: Exercise? = null): List<WarmupSet> {
        // Non-bar lifts (bodyweight/dumbbell/etc.) and asymmetric barbells (T-Bar, Landmine)
        // have no symmetric plate-loaded bar — ramp as a percentage of the working weight
        // instead of the plates-and-quarters bar model.
        if (exercise != null && !exercise.usesBarPlates) {
            return percentageRampWarmups(weightKg)
        }
```

(Leave the rest of the function unchanged. Note: `computeWarmupSets(weightKg)` with no exercise still takes the bar path — existing tests rely on that.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerTest"`
Expected: PASS (whole class, including the two new tests and all pre-existing warmup tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerTest.kt
git commit -m "feat(warmup): asymmetric barbells use percentage ramp"
```

---

### Task 5: Suppress plate breakdown for asymmetric barbells (3 UI call sites)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/ActiveSetContent.kt:196`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/RestingContent.kt` (`NextExerciseCard` param at :286, gate at :298, and its 4 call sites at ~:165-206)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WeightAdjustDialog.kt:33,62`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt:135` (WeightAdjustDialog call site)

**Interfaces:**
- Consumes: `Exercise.usesBarPlates` (Task 1).
- Produces: `NextExerciseCard` and `WeightAdjustDialog` swap their `equipment: Equipment` parameter for `usesBarPlates: Boolean`.

This task is compile-and-manual-verified (Compose UI; no unit test). Add `import io.github.fowles.stochastic_strength.data.model.usesBarPlates` where needed and remove any now-unused `Equipment` import.

- [ ] **Step 1: ActiveSetContent — gate on the exercise helper**

In `ActiveSetContent.kt`, the block at line 196 already has `exercise` in scope. Change:

```kotlin
                    if (exercise.equipment == Equipment.BARBELL) {
```
to:
```kotlin
                    if (exercise.usesBarPlates) {
```

- [ ] **Step 2: WeightAdjustDialog — take a boolean, gate on it**

In `WeightAdjustDialog.kt`, change the parameter (line ~33) from `equipment: Equipment,` to `usesBarPlates: Boolean,`. Change the gate (line ~62) from `if (equipment == Equipment.BARBELL) {` to `if (usesBarPlates) {`.

In `WorkoutScreen.kt` at the call site (line ~135), change `equipment = planned.exercise.equipment,` to `usesBarPlates = planned.exercise.usesBarPlates,`.

- [ ] **Step 3: RestingContent NextExerciseCard — take a boolean, gate on it**

In `RestingContent.kt`, change the private `NextExerciseCard` parameter (line ~286) from `equipment: Equipment,` to `usesBarPlates: Boolean,`. Change the gate (line ~298) from:

```kotlin
            val plates = if (equipment == Equipment.BARBELL)
                WeightFormatter.platesPerSide(weight, weightUnit) else null
```
to:
```kotlin
            val plates = if (usesBarPlates)
                WeightFormatter.platesPerSide(weight, weightUnit) else null
```

Update all four `NextExerciseCard(...)` call sites in this file (the ones passing `equipment = up.exercise.equipment`, `equipment = plannedExercise.exercise.equipment`, `equipment = nextExercise.exercise.equipment`) to pass `usesBarPlates = <sameExercise>.usesBarPlates` instead. (The four `<exercise>` receivers are: `up.exercise`, `plannedExercise.exercise`, and `nextExercise.exercise` — replace each `equipment = X.equipment` with `usesBarPlates = X.usesBarPlates`.)

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (no unresolved `equipment`/`Equipment` references at the changed sites).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/ActiveSetContent.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/RestingContent.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WeightAdjustDialog.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt
git commit -m "feat(workout): hide plate breakdown for asymmetric barbells"
```

---

### Task 6: Backup round-trips `isAsymmetric`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/BackupJson.kt` (`BackupJsonBuilder.exerciseObj` ~:64-68, `BackupJsonParser.exercise` ~:153-162)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backup/BackupJsonTest.kt` (create)

**Interfaces:**
- Consumes: `Exercise.isAsymmetric` (Task 1); `WorkoutBackup.DB_VERSION` (now 18, Task 2); `BackupJsonBuilder.build(WorkoutBackup): String`; `BackupJsonParser.parse(String): WorkoutBackup`. Note the objects are `BackupJsonBuilder` / `BackupJsonParser` (both in `BackupJson.kt`), NOT a single `BackupJson`.
- Produces: no new symbols — `isAsymmetric` added to the exercise JSON object and read back. `exerciseObj` hand-lists fields via `"key" to value` pairs; `exercise(o)` reads them via `o.getBoolean(...)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/backup/BackupJsonTest.kt`. `WorkoutBackup` has no default fields, so pass empty lists for everything unused; `dbVersion` must equal `WorkoutBackup.DB_VERSION` or the parser's version gate rejects it:

```kotlin
package io.github.fowles.stochastic_strength.domain.backup

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupJsonTest {
    @Test
    fun `isAsymmetric survives export-import round trip`() {
        val exercise = Exercise(
            id = 7L,
            name = "Landmine Press",
            primaryMuscle = MuscleGroup.SHOULDERS,
            equipment = Equipment.BARBELL,
            isAsymmetric = true,
        )
        val backup = WorkoutBackup(
            formatVersion = WorkoutBackup.FORMAT_VERSION,
            dbVersion = WorkoutBackup.DB_VERSION,
            exportedAt = 0L,
            exercises = listOf(exercise),
            knownLocations = emptyList(),
            locationExcludedExercises = emptyList(),
            workoutSessions = emptyList(),
            workoutSets = emptyList(),
            userProfile = emptyList(),
            baselineOverrides = emptyList(),
            exerciseHurtState = emptyList(),
            exerciseStrengthOverrides = emptyList(),
        )
        val json = BackupJsonBuilder.build(backup)
        val restored = BackupJsonParser.parse(json)
        assertTrue(restored.exercises.single { it.name == "Landmine Press" }.isAsymmetric)
    }
}
```

(If the `WorkoutBackup` field list differs from the above when read, match the actual constructor — the point is one asymmetric exercise, empty everything else, `dbVersion = WorkoutBackup.DB_VERSION`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backup.BackupJsonTest"`
Expected: FAIL — restored exercise has `isAsymmetric == false` (field not serialized).

- [ ] **Step 3: Add the field to builder and parser**

In `BackupJson.kt`, in `BackupJsonBuilder.exerciseObj`, alongside `"isUnilateral" to e.isUnilateral,` add:

```kotlin
        "isAsymmetric" to e.isAsymmetric,
```

In `BackupJsonParser.exercise`, alongside `isUnilateral = o.getBoolean("isUnilateral"),` add:

```kotlin
        isAsymmetric = o.optBoolean("isAsymmetric", false),
```

(Use `optBoolean(..., false)` — older backups lack the key and must import as non-asymmetric.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backup.BackupJsonTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/BackupJson.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/backup/BackupJsonTest.kt
git commit -m "feat(backup): round-trip Exercise.isAsymmetric"
```

---

### Task 7: Full regression pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full JVM unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, no failures. (Belief backtest gate `BeliefScoreTest` unaffected — no fold/pooling/config change.)

- [ ] **Step 2: Run the instrumented suite**

Run: `./gradlew :app:connectedAndroidTest`
Expected: BUILD SUCCESSFUL (migration tests green, including 17→18).

- [ ] **Step 3: Commit (only if any fixups were needed)**

```bash
git add -A
git commit -m "test: regression pass for asymmetric barbell feature"
```

(If no fixups were needed, skip this commit.)

---

## Self-Review Notes

- **Spec coverage:** flag+helper (Task 1) ✓; behavior — warmup (Task 4) + plate breakdown (Task 5) ✓; migration v17→v18 + version bump + MigrationTest forward lists (Task 2) ✓; seed T-Bar flag + Landmine Press + coefficient (Task 3) ✓; backup round-trip (Task 6) ✓; testing gates (Task 7) ✓; equipment stays BARBELL / no enum value (Global Constraints) ✓; "made" choices (bilateral, 0.5) encoded in Task 3 ✓.
- **Landmine Press delivery to existing users:** relies on `StochasticStrengthApp.onCreate` library-sync (inserts library exercises missing by name), verified during design — no migration insert. T-Bar flip is the only existing-row change, handled by the migration UPDATE.
- **Type consistency:** `usesBarPlates` (extension val) and `isAsymmetric` (field) names used identically across Tasks 1/3/4/5/6; `MIGRATION_17_18` referenced consistently in Task 2.
