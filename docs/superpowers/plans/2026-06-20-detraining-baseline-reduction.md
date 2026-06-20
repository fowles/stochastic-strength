# Detraining Baseline Reduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a user starts a workout after ≥1 week off, offer a dialog that lowers all muscle baselines by a slider-chosen fraction (default `5% × weeks`, capped 50%), with a live preview of the resulting baselines.

**Architecture:** A pure `DetrainingModel` computes the suggested reduction from the gap since the last completed session. The controller surfaces a prompt on the existing `PlanPreview` state; applying it recomputes the previewed weights and stores a per-muscle `detrainOverrides` map on the plan. At session start the map persists as `BaselineOverride` rows tagged with a new `BaselineChangeReason.DETRAIN`, which the existing replay path applies before that session's progression.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Room, JUnit4 (JVM unit tests in `src/test`, instrumented tests in `src/androidTest`).

## Global Constraints

- Package root: `io.github.fowles.stochastic_strength`.
- Reduction formula (verbatim): `suggestedFraction = min(0.50, 0.05 × floor(weeksOff))`; prompt only when `weeksOff ≥ 1`.
- Reduction is uniform across all muscle groups: `newBaseline = currentBaseline × (1 − fraction)`.
- Baselines are NOT grid-rounded by this feature (consistent with the unrounded-baseline controller); rounding stays at planner weight selection.
- Room: app has real users — write a proper `Migration`, never destructive fallback. Current DB version is 15; this feature moves it to 16.
- Detraining rows are tagged `BaselineChangeReason.DETRAIN`, distinct from manual `OVERRIDE`.
- Commit at each task. End every commit message with:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- Build: `./gradlew :app:assembleDebug`. JVM tests: `./gradlew :app:testDebugUnitTest`. Instrumented: `./gradlew :app:connectedAndroidTest` (emulator is typically running — attempt directly).

## File Structure

- Create `domain/DetrainingModel.kt` — pure reduction math.
- Create `app/src/test/.../domain/DetrainingModelTest.kt` — JVM tests for the math.
- Modify `data/model/BaselineChangeReason.kt` — add `DETRAIN`.
- Modify `data/model/BaselineOverride.kt` — add `reason` column.
- Modify `data/AppDatabase.kt` — version 16, `MIGRATION_15_16`, register it.
- Create `app/src/androidTest/.../data/Migration15To16Test.kt` — migration test.
- Modify `domain/WorkoutRepository.kt` — `applyDetrainingReduction`, replay reason tagging + ordering.
- Modify `app/src/androidTest/.../domain/WorkoutRepositoryTest.kt` — DETRAIN replay test.
- Modify `domain/model/WorkoutPlan.kt` — `detrainOverrides` + `effectiveOverrides`.
- Modify `ui/workout/WorkoutState.kt` — `PlanPreview.detraining` + `DetrainingPrompt`.
- Modify `ui/workout/WorkoutSessionController.kt` — trigger, `applyDetraining`, `skipDetraining`, persist at start, use `effectiveOverrides`.
- Modify `app/src/androidTest/.../ui/workout/WorkoutSessionControllerTest.kt` — controller tests.
- Modify `ui/workout/WorkoutViewModel.kt` — delegations.
- Create `ui/workout/DetrainingDialog.kt` — the dialog composable.
- Modify `ui/workout/WorkoutScreen.kt` — render the dialog over the preview.
- Modify `CLAUDE.md` — bump the stale "version 11" note to 16.

---

### Task 1: `DetrainingModel` (pure math)

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/DetrainingModel.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/DetrainingModelTest.kt`

**Interfaces:**
- Produces:
  - `object DetrainingModel`
  - `DetrainingModel.weeksOff(lastEndTime: Long, now: Long): Int`
  - `DetrainingModel.suggestedFraction(weeksOff: Int): Float`
  - `DetrainingModel.qualifies(weeksOff: Int): Boolean`
  - `DetrainingModel.reduce(baseline: Float, fraction: Float): Float`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/io/github/fowles/stochastic_strength/domain/DetrainingModelTest.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetrainingModelTest {
    private val week = 7L * 24 * 60 * 60 * 1000

    @Test fun weeksOff_floorsToWholeWeeks() {
        assertEquals(0, DetrainingModel.weeksOff(lastEndTime = 0, now = week + 6 * 24 * 60 * 60 * 1000))
        assertEquals(1, DetrainingModel.weeksOff(lastEndTime = 0, now = week))
        assertEquals(3, DetrainingModel.weeksOff(lastEndTime = 0, now = 3 * week + 100))
    }

    @Test fun weeksOff_neverNegative() {
        assertEquals(0, DetrainingModel.weeksOff(lastEndTime = week, now = 0))
    }

    @Test fun suggestedFraction_isFivePercentPerWeek() {
        assertEquals(0.05f, DetrainingModel.suggestedFraction(1), 1e-6f)
        assertEquals(0.15f, DetrainingModel.suggestedFraction(3), 1e-6f)
    }

    @Test fun suggestedFraction_cappedAtFiftyPercent() {
        assertEquals(0.50f, DetrainingModel.suggestedFraction(10), 1e-6f)
        assertEquals(0.50f, DetrainingModel.suggestedFraction(20), 1e-6f)
    }

    @Test fun suggestedFraction_zeroForNoWeeks() {
        assertEquals(0f, DetrainingModel.suggestedFraction(0), 1e-6f)
    }

    @Test fun qualifies_requiresAtLeastOneWeek() {
        assertFalse(DetrainingModel.qualifies(0))
        assertTrue(DetrainingModel.qualifies(1))
        assertTrue(DetrainingModel.qualifies(5))
    }

    @Test fun reduce_lowersBaselineByFraction() {
        assertEquals(90f, DetrainingModel.reduce(100f, 0.10f), 1e-4f)
        assertEquals(100f, DetrainingModel.reduce(100f, 0f), 1e-4f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.DetrainingModelTest"`
Expected: FAIL — `DetrainingModel` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/io/github/fowles/stochastic_strength/domain/DetrainingModel.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain

/**
 * Pure detraining math: how far to ease baselines down after a layoff.
 *
 * Suggested reduction is 5% per whole week off, capped at 50%. A prompt is only
 * offered once the gap reaches a full week.
 */
object DetrainingModel {
    const val WEEK_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
    const val PER_WEEK: Float = 0.05f
    const val MAX_FRACTION: Float = 0.50f

    fun weeksOff(lastEndTime: Long, now: Long): Int =
        ((now - lastEndTime) / WEEK_MILLIS).toInt().coerceAtLeast(0)

    fun suggestedFraction(weeksOff: Int): Float =
        (PER_WEEK * weeksOff).coerceIn(0f, MAX_FRACTION)

    fun qualifies(weeksOff: Int): Boolean = weeksOff >= 1

    fun reduce(baseline: Float, fraction: Float): Float = baseline * (1f - fraction)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.DetrainingModelTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/DetrainingModel.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/DetrainingModelTest.kt
git commit -m "feat: add DetrainingModel reduction math

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `DETRAIN` reason + `BaselineOverride.reason` column + migration

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeReason.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineOverride.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt`
- Modify: `CLAUDE.md`
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/data/Migration15To16Test.kt`

**Interfaces:**
- Produces:
  - `BaselineChangeReason.DETRAIN`
  - `BaselineOverride.reason: BaselineChangeReason` (default `OVERRIDE`)
  - `AppDatabase.MIGRATION_15_16`
- Consumes: `AppDatabase.Companion` migration registration pattern; `Converters` already handles `BaselineChangeReason`.

- [ ] **Step 1: Write the failing migration test**

Create `app/src/androidTest/java/io/github/fowles/stochastic_strength/data/Migration15To16Test.kt`:

```kotlin
package io.github.fowles.stochastic_strength.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration15To16Test {

    private val dbName = "migration-15-16-test-db"
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setup() { context.deleteDatabase(dbName) }
    @After fun teardown() { context.deleteDatabase(dbName) }

    private fun createV15DbAndMigrate(
        seed: (SupportSQLiteDatabase) -> Unit = {},
    ): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(15) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""
                            CREATE TABLE IF NOT EXISTS `baseline_override` (
                                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                `sessionId` INTEGER,
                                `muscleGroup` TEXT NOT NULL,
                                `baselineWeight` REAL NOT NULL,
                                `asOf` INTEGER NOT NULL)
                        """.trimIndent())
                        seed(db)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase
        AppDatabase.MIGRATION_15_16.migrate(db)
        return db
    }

    @Test fun migrate15to16_defaultsExistingRowsToOverride() {
        createV15DbAndMigrate { db ->
            db.execSQL(
                "INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf) " +
                    "VALUES (7, 'CHEST', 100.0, 1000)"
            )
        }.use { migrated ->
            migrated.query("SELECT reason FROM baseline_override WHERE sessionId = 7").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("OVERRIDE", c.getString(0))
            }
        }
    }

    @Test fun migrate15to16_allowsInsertingDetrainRows() {
        createV15DbAndMigrate().use { migrated ->
            migrated.execSQL(
                "INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf, reason) " +
                    "VALUES (8, 'BACK', 80.0, 2000, 'DETRAIN')"
            )
            migrated.query("SELECT reason FROM baseline_override WHERE sessionId = 8").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("DETRAIN", c.getString(0))
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.data.Migration15To16Test"`
Expected: FAIL — `MIGRATION_15_16` unresolved reference (compile error).

- [ ] **Step 3: Add the enum value**

In `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeReason.kt`, add `DETRAIN`:

```kotlin
enum class BaselineChangeReason {
    INITIAL,
    OVERRIDE,
    PROGRESSION,
    NORMALIZATION,
    DETRAIN,
}
```

- [ ] **Step 4: Add the `reason` column to the entity**

In `app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineOverride.kt`, add the field (keep the existing KDoc above the class):

```kotlin
@Entity(tableName = "baseline_override")
data class BaselineOverride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long? = null,
    val muscleGroup: MuscleGroup,
    val baselineWeight: Float,
    val asOf: Long,
    val reason: BaselineChangeReason = BaselineChangeReason.OVERRIDE,
)
```

- [ ] **Step 5: Add the migration, bump version, register it**

In `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt`:

Change `version = 15,` to `version = 16,`.

Add this migration object after `MIGRATION_14_15`:

```kotlin
        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE baseline_override ADD COLUMN reason TEXT NOT NULL DEFAULT 'OVERRIDE'"
                )
            }
        }
```

Add `MIGRATION_15_16` to the `addMigrations(...)` call:

```kotlin
                .addMigrations(
                    MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                    MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14,
                    MIGRATION_14_15, MIGRATION_15_16,
                )
```

- [ ] **Step 6: Update the stale version note in CLAUDE.md**

In `CLAUDE.md`, under `### Database`, change `Room database (`AppDatabase`, version 11).` to `Room database (`AppDatabase`, version 16).`

- [ ] **Step 7: Run the migration test to verify it passes**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.data.Migration15To16Test"`
Expected: PASS (2 tests). Room also validates the v16 schema matches the entity on app open — a mismatch would fail other instrumented DB tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineChangeReason.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/data/model/BaselineOverride.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/data/Migration15To16Test.kt \
        CLAUDE.md
git commit -m "feat: add DETRAIN reason and baseline_override.reason column (db v16)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Repository — persist DETRAIN overrides + tag replay by reason

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`

**Interfaces:**
- Consumes: `BaselineOverride.reason`, `BaselineChangeReason.DETRAIN`, `DetrainingModel`.
- Produces:
  - `WorkoutRepository.applyDetrainingReduction(sessionId: Long, overrides: Map<MuscleGroup, Float>)`
  - Replay now tags each session-scoped baseline override's `BaselineHistory.changeReason` from `override.reason`, applying `DETRAIN` rows before `OVERRIDE` rows within a session.

- [ ] **Step 1: Write the failing test**

Add to `app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt`. First inspect the file's existing helpers (it already builds a repository, seeds weights, inserts sessions/sets, and calls `finishSession`/`replayDerivedState`); reuse them. Add this test, adapting helper names to those already present in the file:

```kotlin
    @Test
    fun detrainingReduction_lowersBaselineAndTagsHistory() = runBlocking {
        // Seed initial baselines and a completed session so replay has a timeline.
        repository.seedInitialWeights(Sex.MALE, StrengthLevel.MEDIUM, WeightUnit.KG)
        val chestBefore = repository.getMuscleGroupStrengths()
            .first { it.muscleGroup == MuscleGroup.CHEST }.baselineWeight

        val sessionId = db.workoutSessionDao().insert(
            WorkoutSession(startTime = 1_000L, endTime = 2_000L)
        )
        // Detrain CHEST to 80% of its current baseline for this session.
        repository.applyDetrainingReduction(
            sessionId,
            mapOf(MuscleGroup.CHEST to chestBefore * 0.8f),
        )
        repository.replayDerivedState()

        val chestAfter = repository.getMuscleGroupStrengths()
            .first { it.muscleGroup == MuscleGroup.CHEST }.baselineWeight
        assertEquals(chestBefore * 0.8f, chestAfter, 0.01f)

        val events = repository.getBaselineEvents(MuscleGroup.CHEST)
        val detrainEvent = events.first { it.changeReason == BaselineChangeReason.DETRAIN }
        assertEquals(sessionId, detrainEvent.sessionId)
        assertEquals(chestBefore * 0.8f, detrainEvent.newBaseline, 0.01f)
    }

    @Test
    fun manualOverride_winsOverDetrain_inSameSession() = runBlocking {
        repository.seedInitialWeights(Sex.MALE, StrengthLevel.MEDIUM, WeightUnit.KG)
        val sessionId = db.workoutSessionDao().insert(
            WorkoutSession(startTime = 1_000L, endTime = 2_000L)
        )
        repository.applyDetrainingReduction(sessionId, mapOf(MuscleGroup.CHEST to 50f))
        repository.applyManualBaselineOverrides(sessionId, mapOf(MuscleGroup.CHEST to 70f))
        repository.replayDerivedState()

        val chest = repository.getMuscleGroupStrengths()
            .first { it.muscleGroup == MuscleGroup.CHEST }.baselineWeight
        assertEquals(70f, chest, 0.01f)
    }
```

Ensure these imports exist in the test file (add any missing):
`io.github.fowles.stochastic_strength.data.model.BaselineChangeReason`,
`io.github.fowles.stochastic_strength.data.model.MuscleGroup`,
`io.github.fowles.stochastic_strength.data.model.Sex`,
`io.github.fowles.stochastic_strength.data.model.StrengthLevel`,
`io.github.fowles.stochastic_strength.data.model.WeightUnit`,
`io.github.fowles.stochastic_strength.data.model.WorkoutSession`.

> Note: `applyManualBaselineOverrides` reads `session.startTime` for `asOf`; `applyDetrainingReduction` mirrors it. Both rows share the session, so replay ordering (DETRAIN before OVERRIDE) decides the final value.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest"`
Expected: FAIL — `applyDetrainingReduction` unresolved reference.

- [ ] **Step 3: Add `applyDetrainingReduction`**

In `WorkoutRepository.kt`, directly below `applyManualBaselineOverrides`, add a mirror that tags rows `DETRAIN`:

```kotlin
    suspend fun applyDetrainingReduction(sessionId: Long, overrides: Map<MuscleGroup, Float>) {
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
                    reason = BaselineChangeReason.DETRAIN,
                )
            )
        }
    }
```

Also set the reason explicitly in `applyManualBaselineOverrides` for symmetry (it already defaults to `OVERRIDE`, but be explicit): add `reason = BaselineChangeReason.OVERRIDE,` to the `BaselineOverride(...)` constructed there.

- [ ] **Step 4: Tag replay history by `reason` and order DETRAIN first**

In `replayDerivedState`, replace the per-session override block (the `overridesBySession[session.id]?.forEach { o -> ... }` loop) so it (a) applies `DETRAIN` rows before `OVERRIDE` rows and (b) tags the history row from `o.reason`:

```kotlin
                overridesBySession[session.id]
                    ?.sortedBy { if (it.reason == BaselineChangeReason.DETRAIN) 0 else 1 }
                    ?.forEach { o ->
                        val prev = snapshot.currentBaselines[o.muscleGroup] ?: 0f
                        snapshot.currentBaselines[o.muscleGroup] = o.baselineWeight
                        scratch.upsertMuscleGroupStrength(
                            MuscleGroupStrength(muscleGroup = o.muscleGroup, baselineWeight = o.baselineWeight)
                        )
                        val row = BaselineHistory(
                            sessionId = session.id,
                            muscleGroup = o.muscleGroup,
                            previousBaseline = prev,
                            newBaseline = o.baselineWeight,
                            changeReason = o.reason,
                            timestamp = o.asOf,
                        )
                        scratch.insertBaselineHistory(row)
                        snapshot.baselineHistoryByMuscle.getOrPut(o.muscleGroup) { mutableListOf() }.add(row)
                    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryTest"`
Expected: PASS (existing tests still green; 2 new tests pass).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryTest.kt
git commit -m "feat: persist and replay detraining baseline reductions

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: `WorkoutPlan.detrainOverrides` + effective-overrides plumbing

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlan.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlanTest.kt`

**Interfaces:**
- Produces:
  - `WorkoutPlan.detrainOverrides: Map<MuscleGroup, Float>` (default empty)
  - `WorkoutPlan.effectiveOverrides: Map<MuscleGroup, Float>` = `detrainOverrides + strengthOverrides` (manual wins)

- [ ] **Step 1: Write the failing test**

Add to `app/src/test/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlanTest.kt` (create the file if it does not exist, with the package `io.github.fowles.stochastic_strength.domain.model`):

```kotlin
    @Test
    fun effectiveOverrides_mergesWithManualWinning() {
        val plan = WorkoutPlan(
            exercises = emptyList(),
            locationId = null,
            strengthOverrides = mapOf(MuscleGroup.CHEST to 70f),
            detrainOverrides = mapOf(MuscleGroup.CHEST to 50f, MuscleGroup.BACK to 60f),
        )
        assertEquals(
            mapOf(MuscleGroup.CHEST to 70f, MuscleGroup.BACK to 60f),
            plan.effectiveOverrides,
        )
    }

    @Test
    fun effectiveOverrides_emptyByDefault() {
        val plan = WorkoutPlan(exercises = emptyList(), locationId = null)
        assertEquals(emptyMap<MuscleGroup, Float>(), plan.effectiveOverrides)
    }
```

Ensure imports: `io.github.fowles.stochastic_strength.data.model.MuscleGroup`, `org.junit.Assert.assertEquals`, `org.junit.Test`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.model.WorkoutPlanTest"`
Expected: FAIL — `detrainOverrides` / `effectiveOverrides` unresolved.

- [ ] **Step 3: Add the field and derived property**

In `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlan.kt`:

```kotlin
data class WorkoutPlan(
    val exercises: List<PlannedExercise>,
    val locationId: Long?,
    val sessionReps: Int = 10,
    val sessionRejectedIds: Set<Long> = emptySet(),
    val strengthOverrides: Map<MuscleGroup, Float> = emptyMap(),
    val detrainOverrides: Map<MuscleGroup, Float> = emptyMap(),
) {
    val estimatedDurationSeconds: Int
        get() = exercises.sumOf { it.estimatedSeconds }

    /** Baselines feeding the planner: detraining first, manual edits override it. */
    val effectiveOverrides: Map<MuscleGroup, Float>
        get() = detrainOverrides + strengthOverrides
}
```

- [ ] **Step 4: Route the planner-rebuild call sites through `effectiveOverrides`**

In `WorkoutSessionController.kt`, update the three `buildPlanner(...)` calls that currently pass `strengthOverrides` to pass the merged map instead, so a detrained session keeps its reduced baselines across rebuilds:
- In `replaceExercise`: `repository.buildPlanner(sessionLocationId, weightUnit, updatedPlan.effectiveOverrides)`
- In the add/rebuild path near the bottom: `repository.buildPlanner(locationId, weightUnit, preview.plan.effectiveOverrides)`
- In `persistSwap`'s caller: pass `current.plan.effectiveOverrides`

(The manual `adjustExerciseWeight` path at ~line 212 builds from `updatedOverrides` which is `strengthOverrides + (muscle to newBaseline)`; change it to `state.plan.detrainOverrides + updatedOverrides` so detraining is preserved.)

- [ ] **Step 5: Run test + build to verify**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.model.WorkoutPlanTest"`
Expected: PASS (2 tests).
Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlan.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlanTest.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt
git commit -m "feat: thread detrainOverrides through plan and planner rebuilds

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Controller — prompt trigger, apply/skip, persist at session start

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutState.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt`
- Test: `app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt`

**Interfaces:**
- Consumes: `DetrainingModel`, `repository.getMuscleGroupStrengths()`, `repository.applyDetrainingReduction`, `WorkoutPlan.detrainOverrides`/`effectiveOverrides`, `planner.recomputeExercise(PlannedExercise, Float)`.
- Produces:
  - `WorkoutState.PlanPreview.detraining: DetrainingPrompt?` (default null)
  - `data class DetrainingPrompt(val weeksOff: Int, val suggestedFraction: Float, val currentStrengths: List<MuscleGroupStrength>)`
  - `WorkoutSessionController.applyDetraining(fraction: Float)`
  - `WorkoutSessionController.skipDetraining()`
  - VM delegations `applyDetraining(fraction)`, `skipDetraining()`

- [ ] **Step 1: Add the state shape**

In `WorkoutState.kt`, add the data class and the field on `PlanPreview`:

```kotlin
    data class PlanPreview(
        val plan: WorkoutPlan,
        val locationName: String? = null,
        val repMin: Int = 5,
        val repMax: Int = 10,
        val detraining: DetrainingPrompt? = null,
    ) : WorkoutState
```

Add near the bottom of the file (outside the sealed interface):

```kotlin
data class DetrainingPrompt(
    val weeksOff: Int,
    val suggestedFraction: Float,
    val currentStrengths: List<io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength>,
)
```

- [ ] **Step 2: Write the failing controller tests**

Open `app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt` and reuse its existing setup (it constructs a `WorkoutSessionController` with a test DB/repository and drives `initializeSession`). Add tests, adapting helper names to those already present:

```kotlin
    @Test
    fun initialize_afterLayoff_surfacesDetrainingPromptWithSuggestedDefault() = runBlocking {
        repository.seedInitialWeights(Sex.MALE, StrengthLevel.MEDIUM, WeightUnit.KG)
        // A completed session three weeks ago.
        val threeWeeksAgo = System.currentTimeMillis() - 3L * DetrainingModel.WEEK_MILLIS - 60_000
        db.workoutSessionDao().insert(
            WorkoutSession(startTime = threeWeeksAgo, endTime = threeWeeksAgo + 1000)
        )
        controller.initializeSession(
            locationId = null, locationName = null, preferredExerciseCount = 5,
            preferredRepMin = 5, preferredRepMax = 10, weightUnit = WeightUnit.KG,
        )
        val preview = controller.state.value as WorkoutState.PlanPreview
        val prompt = preview.detraining!!
        assertEquals(3, prompt.weeksOff)
        assertEquals(0.15f, prompt.suggestedFraction, 1e-4f)
        assertTrue(prompt.currentStrengths.isNotEmpty())
    }

    @Test
    fun initialize_recentSession_noPrompt() = runBlocking {
        repository.seedInitialWeights(Sex.MALE, StrengthLevel.MEDIUM, WeightUnit.KG)
        db.workoutSessionDao().insert(
            WorkoutSession(startTime = System.currentTimeMillis() - 1000, endTime = System.currentTimeMillis())
        )
        controller.initializeSession(
            locationId = null, locationName = null, preferredExerciseCount = 5,
            preferredRepMin = 5, preferredRepMax = 10, weightUnit = WeightUnit.KG,
        )
        val preview = controller.state.value as WorkoutState.PlanPreview
        assertNull(preview.detraining)
    }

    @Test
    fun applyDetraining_reducesWeightsAndStoresOverrides() = runBlocking {
        repository.seedInitialWeights(Sex.MALE, StrengthLevel.MEDIUM, WeightUnit.KG)
        val threeWeeksAgo = System.currentTimeMillis() - 3L * DetrainingModel.WEEK_MILLIS - 60_000
        db.workoutSessionDao().insert(
            WorkoutSession(startTime = threeWeeksAgo, endTime = threeWeeksAgo + 1000)
        )
        controller.initializeSession(
            locationId = null, locationName = null, preferredExerciseCount = 5,
            preferredRepMin = 5, preferredRepMax = 10, weightUnit = WeightUnit.KG,
        )
        val before = (controller.state.value as WorkoutState.PlanPreview)
            .plan.exercises.first { it.sessionWeight > 0f }

        controller.applyDetraining(0.20f)

        val after = (controller.state.value as WorkoutState.PlanPreview)
        assertNull(after.detraining)
        assertTrue(after.plan.detrainOverrides.isNotEmpty())
        val sameExercise = after.plan.exercises.first { it.exercise.id == before.exercise.id }
        assertTrue("expected reduced weight", sameExercise.sessionWeight < before.sessionWeight)
    }

    @Test
    fun skipDetraining_leavesWeightsAndOverridesUntouched() = runBlocking {
        repository.seedInitialWeights(Sex.MALE, StrengthLevel.MEDIUM, WeightUnit.KG)
        val threeWeeksAgo = System.currentTimeMillis() - 3L * DetrainingModel.WEEK_MILLIS - 60_000
        db.workoutSessionDao().insert(
            WorkoutSession(startTime = threeWeeksAgo, endTime = threeWeeksAgo + 1000)
        )
        controller.initializeSession(
            locationId = null, locationName = null, preferredExerciseCount = 5,
            preferredRepMin = 5, preferredRepMax = 10, weightUnit = WeightUnit.KG,
        )
        val before = (controller.state.value as WorkoutState.PlanPreview).plan.exercises

        controller.skipDetraining()

        val after = (controller.state.value as WorkoutState.PlanPreview)
        assertNull(after.detraining)
        assertTrue(after.plan.detrainOverrides.isEmpty())
        assertEquals(before.map { it.sessionWeight }, after.plan.exercises.map { it.sessionWeight })
    }
```

Add imports as needed: `DetrainingModel`, `WorkoutSession`, `Sex`, `StrengthLevel`, `WeightUnit`, `assertNull`, `assertTrue`.

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: FAIL — `applyDetraining` / `skipDetraining` unresolved.

- [ ] **Step 4: Compute the prompt during initialize**

In `WorkoutSessionController.initializeSession`, after the `setState(WorkoutState.PlanPreview(...))` and `adjustExerciseCount(preferredExerciseCount)` lines, compute and attach the prompt (kept separate so it survives the count adjustment):

```kotlin
        maybeOfferDetraining()
```

Add the private helper:

```kotlin
    private suspend fun maybeOfferDetraining() {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val lastCompleted = database.workoutSessionDao().getRecentCompletedSessions(limit = 1)
            .firstOrNull()?.endTime ?: return
        val weeks = DetrainingModel.weeksOff(lastCompleted, System.currentTimeMillis())
        if (!DetrainingModel.qualifies(weeks)) return
        val strengths = repository.getMuscleGroupStrengths()
        if (strengths.isEmpty()) return
        setState(
            preview.copy(
                detraining = DetrainingPrompt(
                    weeksOff = weeks,
                    suggestedFraction = DetrainingModel.suggestedFraction(weeks),
                    currentStrengths = strengths,
                ),
            ),
        )
    }
```

Add the import `io.github.fowles.stochastic_strength.domain.DetrainingModel` (and `MuscleGroupStrength` is referenced only via the state class).

- [ ] **Step 5: Implement `applyDetraining` and `skipDetraining`**

Add to the controller:

```kotlin
    fun applyDetraining(fraction: Float) {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        val prompt = preview.detraining ?: return
        if (fraction <= 0f) { skipDetraining(); return }
        val detrainOverrides = prompt.currentStrengths.associate { strength ->
            strength.muscleGroup to DetrainingModel.reduce(strength.baselineWeight, fraction)
        }
        scope.launch {
            val p = repository.buildPlanner(
                sessionLocationId,
                weightUnit,
                detrainOverrides + preview.plan.strengthOverrides,
            )
            planner = p
            val current = _state.value as? WorkoutState.PlanPreview ?: return@launch
            val recomputed = current.plan.exercises.map { ex ->
                val newBaseline = detrainOverrides[ex.exercise.primaryMuscle]
                if (newBaseline != null) p.recomputeExercise(ex, newBaseline) else ex
            }
            setState(
                current.copy(
                    plan = current.plan.copy(
                        exercises = recomputed,
                        detrainOverrides = detrainOverrides,
                    ),
                    detraining = null,
                ),
            )
        }
    }

    fun skipDetraining() {
        val preview = _state.value as? WorkoutState.PlanPreview ?: return
        setState(preview.copy(detraining = null))
    }
```

- [ ] **Step 6: Persist detraining at session start**

In `startFirstExercise`, alongside the existing `applyManualBaselineOverrides` call, add the detraining persistence (right after the session insert):

```kotlin
            repository.applyDetrainingReduction(sessionId, plan.detrainOverrides)
            repository.applyManualBaselineOverrides(sessionId, plan.strengthOverrides)
```

- [ ] **Step 7: Add VM delegations**

In `WorkoutViewModel.kt`, next to the other delegations:

```kotlin
    fun applyDetraining(fraction: Float) = controller.applyDetraining(fraction)
    fun skipDetraining() = controller.skipDetraining()
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.ui.workout.WorkoutSessionControllerTest"`
Expected: PASS (4 new tests; existing tests green).

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutState.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutViewModel.kt \
        app/src/androidTest/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionControllerTest.kt
git commit -m "feat: detraining prompt trigger, apply/skip, and session-start persistence

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Dialog UI with live `StrengthGrid` preview

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/DetrainingDialog.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt`

**Interfaces:**
- Consumes: `DetrainingPrompt`, `WeightUnit`, `StrengthGrid`, `DetrainingModel.reduce`, `WeightFormatter`.
- Produces: `DetrainingDialog(prompt, weightUnit, onApply: (Float) -> Unit, onSkip: () -> Unit)`.

> The dialog has no automated test (Compose UI dialog; the project has no Compose UI test harness). Its only logic is the pure per-muscle reduction already covered by `DetrainingModelTest`. Verify manually via the build + the run check below.

- [ ] **Step 1: Create the dialog composable**

Create `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/DetrainingDialog.kt`:

```kotlin
package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.DetrainingModel
import io.github.fowles.stochastic_strength.ui.components.StrengthGrid
import kotlin.math.roundToInt

@Composable
internal fun DetrainingDialog(
    prompt: DetrainingPrompt,
    weightUnit: WeightUnit,
    onApply: (Float) -> Unit,
    onSkip: () -> Unit,
) {
    var fraction by remember { mutableFloatStateOf(prompt.suggestedFraction) }
    val percent = (fraction * 100f).roundToInt()
    val reduced = prompt.currentStrengths.map {
        it.copy(baselineWeight = DetrainingModel.reduce(it.baselineWeight, fraction))
    }

    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("Welcome back") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val weeks = prompt.weeksOff
                Text(
                    "It's been $weeks ${if (weeks == 1) "week" else "weeks"} since your last " +
                        "workout. We can ease your baselines down to match.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Reduce by $percent%",
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = fraction,
                    onValueChange = { fraction = it },
                    valueRange = 0f..DetrainingModel.MAX_FRACTION,
                    steps = 9, // 0,5,...,50 in 5% steps
                )
                Spacer(Modifier.height(4.dp))
                StrengthGrid(
                    strengths = reduced,
                    tapTargets = emptyMap<io.github.fowles.stochastic_strength.data.model.MuscleGroup, Unit>(),
                    weightUnit = weightUnit,
                    onTap = {},
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApply(fraction) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Skip") } },
    )
}
```

> `steps = 9` yields 11 stops over `0f..0.50f` (0%, 5%, … 50%). `StrengthGrid` is `internal` in the same module, so it is directly callable.

- [ ] **Step 2: Render the dialog over the preview**

In `WorkoutScreen.kt`, within the `is WorkoutState.PlanPreview ->` branch, keep `PlanPreviewContent(...)` and add the dialog after it (still inside the branch, e.g. wrapping both in the existing block):

```kotlin
                is WorkoutState.PlanPreview -> {
                    PlanPreviewContent(
                        state = s,
                        weightUnit = weightUnit,
                        onStart = viewModel::startFirstExercise,
                        onReplace = viewModel::replaceExercise,
                        onSetExerciseCount = viewModel::setExerciseCount,
                        onSetRepRange = viewModel::setRepRange,
                        onAdjustWeight = viewModel::adjustExerciseWeight,
                        onEditLocation = { locationId -> onEditLocation(locationId) },
                        onExerciseTap = onExerciseTap,
                    )
                    s.detraining?.let { prompt ->
                        DetrainingDialog(
                            prompt = prompt,
                            weightUnit = weightUnit,
                            onApply = viewModel::applyDetraining,
                            onSkip = viewModel::skipDetraining,
                        )
                    }
                }
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification (run the app)**

Use the DebugSeeder path or set a completed session ≥1 week in the past, then tap Start Workout:
- Dialog appears with "It's been N weeks", slider defaulted to `5%×weeks` (capped 50%).
- Moving the slider live-updates every `StrengthGrid` card's weight.
- **Apply** dismisses the dialog and the preview's exercise weights are lower; starting the workout writes a `DETRAIN` baseline event (visible in the muscle baseline debug detail screen).
- **Skip** dismisses with no weight change and no `DETRAIN` event.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/DetrainingDialog.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutScreen.kt
git commit -m "feat: detraining dialog with live strength-grid preview

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Full regression pass

**Files:** none (verification only).

- [ ] **Step 1: Run the full JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Run the full instrumented suite**

Run: `./gradlew :app:connectedAndroidTest`
Expected: BUILD SUCCESSFUL, all tests pass (notably migration + repository + controller suites).

- [ ] **Step 3: Lint**

Run: `./gradlew :app:lint`
Expected: no new errors introduced by this change.

---

## Self-Review

**Spec coverage:**
- Decay model `min(0.50, 0.05×weeks)` → Task 1 (`DetrainingModel`). ✓
- Trigger `weeks ≥ 1` → Task 1 (`qualifies`) + Task 5 (`maybeOfferDetraining`). ✓
- Uniform scope → Task 5 (`applyDetraining` maps over all `currentStrengths`). ✓
- Slider defaulted to suggested value → Task 6 (`fraction = prompt.suggestedFraction`). ✓
- Live `StrengthGrid` preview → Task 6. ✓
- New `DETRAIN` tag → Task 2. ✓
- `reason` column + migration v15→v16, proper migration → Task 2. ✓
- `WorkoutPlan.detrainOverrides` + planner feed → Task 4. ✓
- `applyDetrainingReduction` writing DETRAIN rows; replay tags from `reason`, detrain before manual → Task 3. ✓
- Two persistence calls at session start → Task 5 Step 6. ✓
- 0% on Apply == Skip → Task 5 (`applyDetraining` guard). ✓
- Testing: model/controller/replay/migration → Tasks 1,2,3,5; dialog manual-verified → Task 6. ✓

**Placeholder scan:** none — all steps carry concrete code/commands.

**Type consistency:** `applyDetrainingReduction(sessionId, overrides)`, `DetrainingPrompt(weeksOff, suggestedFraction, currentStrengths)`, `applyDetraining(fraction)`, `skipDetraining()`, `WorkoutPlan.effectiveOverrides`/`detrainOverrides`, `DetrainingModel.{weeksOff,suggestedFraction,qualifies,reduce,WEEK_MILLIS,MAX_FRACTION}` are referenced consistently across tasks.
