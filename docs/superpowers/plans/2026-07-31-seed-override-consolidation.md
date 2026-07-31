# Seed/Override Consolidation (Part A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make cold-start seed beliefs a *live* projection of durable per-muscle state (`baseline_override` + `UserProfile`) instead of materialized `exercise_strength_override` rows, drop the redundant manual-override write, and delete the `exercise_strength_override` table — so a future coefficient change needs zero migration.

**Architecture:** During replay we synthesize per-exercise seed beliefs on the fly from the per-muscle baseline (an override row if present, else the `StartingWeights(sex, level)` default) times the current `ExerciseCoefficients`. The coefficient-derived half is never stored. Manual weight edits stay ephemeral (they drive the performed weight this session; the set log records the outcome). After no writers remain, one migration drops the table.

**Tech Stack:** Kotlin, Room, Jetpack Compose, JUnit4 (JVM unit + `androidTest` instrumented). No new dependencies.

## Global Constraints

- **DB has real users** — the version bump ships a proper `Migration`; destructive fallback is not configured. Any version bump updates every migration forward-list (`AppDatabase.buildDatabase`, `MigrationTest`).
- **Belief gate is a pinned CI gate.** `BeliefScoreTest`/`BeliefPolicyBacktestTest` replay real history (`app/src/test/resources/backtest/history.json`, gitignored, present locally). Re-baselining a pinned value is a **human decision** — surface the measured number at the review checkpoint; never silently re-pin.
- **This work touches nothing coefficient-derived that is stored.** Durable state after this plan: `workoutSessions` + `workoutSets` + per-muscle `baseline_override` + `UserProfile`. Everything per-exercise is derived by `replayDerivedState`.
- **λ / `coefExponent` is NOT on main** (the `usv` compression branch was abandoned). The spec's "remove runtime λ" is already true here — no runtime-λ code to remove.
- Version control is jj; commit at each task's final step; the user owns reshape + push.

---

## File Structure

**New:**
- `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SeedBelief.kt` — plain in-memory seed carrier (replaces the `ExerciseStrengthOverride` entity as replay's seed input type).

**Modified:**
- `domain/progression/ExerciseSeedExpansion.kt` — map-based `expand` + new `buildSeeds` (StartingWeights default fill, `e1rm > 0` guard).
- `domain/progression/ReplayEngine.kt` — `run` sources seeds via `buildSeeds`; `runCore` takes `List<SeedBelief>` / `Map<Long, List<SeedBelief>>`.
- `domain/progression/ExerciseProgressionSeriesBuilder.kt` — same seed sourcing for the chart replay.
- `app/src/test/.../backtest/BacktestData.kt` (+ its consumers) — mirror live expansion.
- `domain/WorkoutRepository.kt` — drop `applyManualExerciseOverrides`; simplify `seedInitialWeights` to `UserProfile`-only.
- `ui/workout/WorkoutSessionController.kt` — drop the `applyManualExerciseOverrides` call.
- `data/AppDatabase.kt` — remove entity + DAO accessor; add `MIGRATION_18_19`; `version = 19`.
- `data/model/UserProfile.kt` — remove `perExerciseSeedsBackfilled`.
- `domain/backup/{WorkoutBackup,BackupJson,BackupManager}.kt` — drop the table + flag; `DB_VERSION = 19`.
- `MigrationTest.kt` — register `MIGRATION_18_19`, add its forward test.

**Deleted:**
- `data/model/ExerciseStrengthOverride.kt`, `data/dao/ExerciseStrengthOverrideDao.kt`.
- `domain/ExerciseStrengthOverrideBackfill.kt` (+ `ExerciseStrengthOverrideBackfillTest.kt`); its call in `domain/DerivedStateBackfill.kt`.

---

## Task 1: `SeedBelief` + live expansion core (pure)

Introduce the plain seed type and the map-based expansion/default-fill. No wiring yet — old paths keep compiling.

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SeedBelief.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseSeedExpansion.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseSeedExpansionTest.kt` (rewrite)

**Interfaces:**
- Produces:
  - `data class SeedBelief(val sessionId: Long?, val exerciseId: Long, val e1rm: Float, val asOf: Long)`
  - `ExerciseSeedExpansion.MuscleBaseline(sessionId: Long?, muscleGroup: MuscleGroup, baselineWeight: Float, asOf: Long)`
  - `ExerciseSeedExpansion.expand(muscleBaselines: List<MuscleBaseline>, exerciseMuscle: Map<Long, MuscleGroup>, coefById: Map<Long, Float>): List<SeedBelief>`
  - `ExerciseSeedExpansion.Seeds(initial: List<SeedBelief>, bySession: Map<Long, List<SeedBelief>>)`
  - `ExerciseSeedExpansion.buildSeeds(initialOverrides: List<BaselineOverride>, sessionOverrides: List<BaselineOverride>, sex: Sex, level: StrengthLevel, exerciseMuscle: Map<Long, MuscleGroup>, coefById: Map<Long, Float>): Seeds`
- Consumes: `StartingWeights.baseline`, `ExerciseCoefficients` (indirectly via the coef map passed by callers).

- [ ] **Step 1: Write the failing tests**

Rewrite `ExerciseSeedExpansionTest.kt` to the new API:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseSeedExpansionTest {

    // Two CHEST exercises (coef 1.0 and 0.5), one BACK (coef 1.0), one bodyweight CHEST (coef 0.0).
    private val exerciseMuscle = mapOf(
        1L to MuscleGroup.CHEST, 2L to MuscleGroup.CHEST, 3L to MuscleGroup.BACK, 4L to MuscleGroup.CHEST,
    )
    private val coefById = mapOf(1L to 1.0f, 2L to 0.5f, 3L to 1.0f, 4L to 0.0f)

    @Test
    fun expand_scalesEachLoadedExerciseByCoef_andSkipsZeroCoef() {
        val rows = ExerciseSeedExpansion.expand(
            muscleBaselines = listOf(
                ExerciseSeedExpansion.MuscleBaseline(null, MuscleGroup.CHEST, 80f, 0L),
            ),
            exerciseMuscle = exerciseMuscle,
            coefById = coefById,
        )
        // exercise 4 (coef 0) is skipped; 1 -> 80, 2 -> 40. No BACK row (no BACK baseline given).
        assertEquals(setOf(1L to 80f, 2L to 40f), rows.map { it.exerciseId to it.e1rm }.toSet())
        assertTrue(rows.all { it.sessionId == null && it.asOf == 0L })
    }

    @Test
    fun expand_skipsRowsWhoseProductIsNotPositive() {
        val rows = ExerciseSeedExpansion.expand(
            muscleBaselines = listOf(ExerciseSeedExpansion.MuscleBaseline(null, MuscleGroup.CHEST, 0f, 0L)),
            exerciseMuscle = exerciseMuscle,
            coefById = coefById,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun buildSeeds_defaultsMusclesWithoutAnInitialOverrideToStartingWeights() {
        // No overrides at all -> every muscle uses StartingWeights.baseline(sex, level, muscle).
        val seeds = ExerciseSeedExpansion.buildSeeds(
            initialOverrides = emptyList(),
            sessionOverrides = emptyList(),
            sex = Sex.MALE, level = StrengthLevel.MEDIUM,
            exerciseMuscle = exerciseMuscle,
            coefById = coefById,
        )
        // CHEST default = 80 (StartingWeights), BACK default = 80. Exercise 1 -> 80, 2 -> 40, 3 -> 80.
        assertEquals(setOf(1L to 80f, 2L to 40f, 3L to 80f), seeds.initial.map { it.exerciseId to it.e1rm }.toSet())
        assertTrue(seeds.bySession.isEmpty())
    }

    @Test
    fun buildSeeds_prefersInitialOverrideOverDefault_perMuscle() {
        val seeds = ExerciseSeedExpansion.buildSeeds(
            initialOverrides = listOf(
                BaselineOverride(sessionId = null, muscleGroup = MuscleGroup.CHEST, baselineWeight = 100f, asOf = 5L, reason = BaselineChangeReason.INITIAL),
            ),
            sessionOverrides = emptyList(),
            sex = Sex.MALE, level = StrengthLevel.MEDIUM,
            exerciseMuscle = exerciseMuscle,
            coefById = coefById,
        )
        // CHEST overridden to 100 (asOf 5) -> 1:100, 2:50; BACK still default 80 -> 3:80.
        assertEquals(mapOf(1L to 100f, 2L to 50f, 3L to 80f), seeds.initial.associate { it.exerciseId to it.e1rm })
        assertEquals(5L, seeds.initial.first { it.exerciseId == 1L }.asOf)
    }

    @Test
    fun buildSeeds_routesSessionScopedOverridesIntoBySession() {
        val seeds = ExerciseSeedExpansion.buildSeeds(
            initialOverrides = emptyList(),
            sessionOverrides = listOf(
                BaselineOverride(sessionId = 7L, muscleGroup = MuscleGroup.CHEST, baselineWeight = 90f, asOf = 123L, reason = BaselineChangeReason.OVERRIDE),
            ),
            sex = Sex.MALE, level = StrengthLevel.MEDIUM,
            exerciseMuscle = exerciseMuscle,
            coefById = coefById,
        )
        assertEquals(setOf(1L to 90f, 2L to 45f), seeds.bySession.getValue(7L).map { it.exerciseId to it.e1rm }.toSet())
        assertTrue(seeds.bySession.getValue(7L).all { it.asOf == 123L })
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseSeedExpansionTest"`
Expected: FAIL — unresolved references (`SeedBelief`, `MuscleBaseline`, `buildSeeds`).

- [ ] **Step 3: Create `SeedBelief.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

/**
 * A cold-start seed belief for one exercise, synthesized live during replay from the per-muscle
 * baseline times the current coefficient. Not persisted — the coefficient half is never stored.
 *
 * - `sessionId == null` seeds the belief at replay start (sigmaSeed).
 * - `sessionId == N` reseeds it at session N's boundary (sigmaOverride, a deliberate per-muscle edit).
 */
data class SeedBelief(
    val sessionId: Long?,
    val exerciseId: Long,
    val e1rm: Float,
    val asOf: Long,
)
```

- [ ] **Step 4: Rewrite `ExerciseSeedExpansion.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.BaselineOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import io.github.fowles.stochastic_strength.domain.StartingWeights

/**
 * Live cold-start seeding: expands per-muscle baselines into per-exercise [SeedBelief]s via the
 * current coefficients. A muscle with no [BaselineOverride] initial row defaults to the
 * [StartingWeights] reference for the user's (sex, level).
 */
object ExerciseSeedExpansion {

    data class MuscleBaseline(
        val sessionId: Long?,
        val muscleGroup: MuscleGroup,
        val baselineWeight: Float,
        val asOf: Long,
    )

    data class Seeds(
        val initial: List<SeedBelief>,
        val bySession: Map<Long, List<SeedBelief>>,
    )

    /** One seed per loaded (coef > 0) exercise in each baseline's muscle; drops non-positive e1rm. */
    fun expand(
        muscleBaselines: List<MuscleBaseline>,
        exerciseMuscle: Map<Long, MuscleGroup>,
        coefById: Map<Long, Float>,
    ): List<SeedBelief> {
        val loadedByMuscle: Map<MuscleGroup, List<Pair<Long, Float>>> =
            coefById.filterValues { it > 0f }
                .mapNotNull { (id, coef) -> exerciseMuscle[id]?.let { it to (id to coef) } }
                .groupBy({ it.first }, { it.second })
        return muscleBaselines.flatMap { row ->
            loadedByMuscle[row.muscleGroup].orEmpty().mapNotNull { (exerciseId, coef) ->
                (row.baselineWeight * coef).takeIf { it > 0f }
                    ?.let { SeedBelief(row.sessionId, exerciseId, it, row.asOf) }
            }
        }
    }

    /**
     * Build the replay's initial + session seed sets from durable [BaselineOverride] rows. Every
     * muscle without an initial override falls back to the [StartingWeights] default for (sex, level)
     * — identical to what `seedInitialWeights` used to materialize, so existing behavior is preserved.
     */
    fun buildSeeds(
        initialOverrides: List<BaselineOverride>,
        sessionOverrides: List<BaselineOverride>,
        sex: Sex,
        level: StrengthLevel,
        exerciseMuscle: Map<Long, MuscleGroup>,
        coefById: Map<Long, Float>,
    ): Seeds {
        val initialByMuscle = initialOverrides.associateBy { it.muscleGroup }
        val initialBaselines = MuscleGroup.entries.map { muscle ->
            val override = initialByMuscle[muscle]
            MuscleBaseline(
                sessionId = null,
                muscleGroup = muscle,
                baselineWeight = override?.baselineWeight ?: StartingWeights.baseline(sex, level, muscle),
                asOf = override?.asOf ?: 0L,
            )
        }
        val sessionBaselines = sessionOverrides.map {
            MuscleBaseline(it.sessionId, it.muscleGroup, it.baselineWeight, it.asOf)
        }
        val all = expand(initialBaselines + sessionBaselines, exerciseMuscle, coefById)
        return Seeds(
            initial = all.filter { it.sessionId == null },
            bySession = all.filter { it.sessionId != null }.groupBy { it.sessionId!! },
        )
    }
}
```

Note: `ExerciseStrengthOverrideBackfill.kt` used the *old* `expand(muscleOverrides, exercises, coefSource)` + `MuscleOverrideRow`. Both are gone now, so that file will not compile. It is deleted in Task 4 and is dead in production today (gated by a flag that is set on all current installs). To keep this task's build green, in this step **also delete** `ExerciseStrengthOverrideBackfill.kt`, its test `ExerciseStrengthOverrideBackfillTest.kt`, and the `ExerciseStrengthOverrideBackfill(database).run()` line in `domain/DerivedStateBackfill.kt`. (The rest of the table deletion stays in Task 4; this early removal is only what's needed to compile.)

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseSeedExpansionTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Confirm module still compiles**

Run: `./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL (backfill + its test are deleted; nothing else references the removed symbols).

- [ ] **Step 7: Commit**

```bash
jj describe -m "feat(seed): live cold-start seed expansion core (SeedBelief + buildSeeds)"
```

---

## Task 2: Wire live expansion into replay + backtest; re-baseline the gate

Switch the three replay entry points off the materialized `exercise_strength_override` rows and onto `buildSeeds`. Reconcile the pre-existing `dbVersion` fixture mismatch and re-pin the gate to the measured value.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ReplayEngine.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseProgressionSeriesBuilder.kt:283-289,304-337`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestData.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/WorkoutBackup.kt:31` (`DB_VERSION = 19`)
- Modify (re-pin only): `BeliefScoreTest.kt`, `BeliefPolicyBacktestTest.kt`
- Possibly modify: backtest consumers of `BacktestData.initialOverrides`/`sessionOverrides` (grep in Step 1).

**Interfaces:**
- Consumes: `ExerciseSeedExpansion.buildSeeds`, `SeedBelief` (Task 1).
- Produces (changed signatures):
  - `ReplayEngine.runCore(snapshot, initialSeeds: List<SeedBelief>, sessionSeeds: Map<Long, List<SeedBelief>>, sessions, setsForSession, observer, beforeSession)`
  - `BacktestData.initialSeeds: List<SeedBelief>`, `BacktestData.sessionSeeds: Map<Long, List<SeedBelief>>`

- [ ] **Step 1: Survey the callers that will change**

Run:
```bash
grep -rn "initialOverrides\|sessionOverrides\|exerciseStrengthOverride\|\.runCore(" app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/ app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/
```
Record every file that reads `BacktestData.initialOverrides`/`sessionOverrides` or calls `runCore` with the old arg names (e.g. `BeliefStackReplay.kt`, `BeliefHeldOutScorer.kt`, `ReplayEngineTest.kt`, `ExerciseProgressionSeriesBuilderTest.kt`). These update in Steps 4–6.

- [ ] **Step 2: Change `ReplayEngine.runCore` to `SeedBelief`**

In `runCore`, replace the two params and the two seeding loops:

```kotlin
suspend fun runCore(
    snapshot: ReplaySnapshot,
    initialSeeds: List<SeedBelief>,
    sessionSeeds: Map<Long, List<SeedBelief>>,
    sessions: List<WorkoutSession>,
    setsForSession: suspend (Long) -> List<WorkoutSet>,
    observer: SessionObserver,
    beforeSession: ((beliefs: Map<Long, Belief>, asOf: Long) -> Unit)? = null,
) {
    val sigmaSeed2 = beliefConfig.sigmaSeed * beliefConfig.sigmaSeed
    val sigmaOverride2 = beliefConfig.sigmaOverride * beliefConfig.sigmaOverride

    for (seed in initialSeeds) {
        snapshot.currentBeliefs[seed.exerciseId] = Belief(ln(seed.e1rm), sigmaSeed2, seed.asOf)
    }

    val ordered = sessions
        .filter { it.endTime != null }
        .sortedWith(compareBy({ it.endTime!! }, { it.id }))

    for (session in ordered) {
        sessionSeeds[session.id]?.forEach { seed ->
            snapshot.currentBeliefs[seed.exerciseId] = Belief(ln(seed.e1rm), sigmaOverride2, seed.asOf)
        }
        val sets = setsForSession(session.id)
        if (sets.isEmpty()) continue
        beforeSession?.invoke(snapshot.currentBeliefs, session.endTime!!)
        val beliefResult = beliefStep.step(
            beliefs = snapshot.currentBeliefs,
            sets = sets,
            seedCoef = snapshot.seedCoefficients,
            exerciseMuscle = snapshot.exerciseMuscle,
            muscleExerciseIds = snapshot.muscleExerciseIds,
            asOf = session.endTime!!,
        )
        observer.onSession(session.id, session.endTime!!, sets, snapshot, beliefResult)
    }
}
```

Remove the now-unused `import ...ExerciseStrengthOverride`.

- [ ] **Step 3: Change `ReplayEngine.run` to source seeds live**

```kotlin
suspend fun run(
    db: AppDatabase,
    snapshot: ReplaySnapshot,
    beforeSession: ((beliefs: Map<Long, Belief>, asOf: Long) -> Unit)? = null,
    observer: SessionObserver,
) {
    val profile = db.userProfileDao().getProfile()
    val seeds = if (profile == null) {
        ExerciseSeedExpansion.Seeds(emptyList(), emptyMap())
    } else {
        ExerciseSeedExpansion.buildSeeds(
            initialOverrides = db.baselineOverrideDao().getInitials(),
            sessionOverrides = db.baselineOverrideDao().getNonInitials(),
            sex = profile.sex,
            level = profile.strengthLevel,
            exerciseMuscle = snapshot.exerciseMuscle,
            coefById = snapshot.seedCoefficients,
        )
    }
    runCore(
        snapshot = snapshot,
        initialSeeds = seeds.initial,
        sessionSeeds = seeds.bySession,
        sessions = db.workoutSessionDao().getAll(),
        setsForSession = { db.workoutSetDao().getSetsForSession(it) },
        observer = observer,
        beforeSession = beforeSession,
    )
}
```

- [ ] **Step 4: Change `ExerciseProgressionSeriesBuilder` to match**

In `build` (around lines 283–289), replace the `exerciseStrengthOverrideDao()` reads with `buildSeeds` (the profile is already fetched there for `weightUnit`; reuse it). In `buildCore` rename the two params to `initialSeeds: List<SeedBelief>` / `sessionSeeds: Map<Long, List<SeedBelief>>` and pass them straight through to `engine.runCore`. Remove the `import ...ExerciseStrengthOverride`.

```kotlin
val profile = db.userProfileDao().getProfile()
val weightUnit = profile?.weightUnit ?: WeightUnit.KG
val seeds = if (profile == null) {
    ExerciseSeedExpansion.Seeds(emptyList(), emptyMap())
} else {
    ExerciseSeedExpansion.buildSeeds(
        initialOverrides = db.baselineOverrideDao().getInitials(),
        sessionOverrides = db.baselineOverrideDao().getNonInitials(),
        sex = profile.sex, level = profile.strengthLevel,
        exerciseMuscle = snapshot.exerciseMuscle, coefById = snapshot.seedCoefficients,
    )
}
return buildCore(
    exerciseId = exerciseId, snapshot = snapshot, muscle = muscle, muscleIds = muscleIds,
    namesById = namesById, weightUnit = weightUnit,
    initialSeeds = seeds.initial, sessionSeeds = seeds.bySession,
    sessions = db.workoutSessionDao().getAll(),
    setsForSession = { db.workoutSetDao().getSetsForSession(it) },
    now = System.currentTimeMillis(),
)
```

- [ ] **Step 5: Bump `WorkoutBackup.DB_VERSION` to 19 so the local fixture loads**

`WorkoutBackup.kt:31`: `const val DB_VERSION = 19`. The gitignored fixtures (`history.json`, `history-2.json`) already declare `dbVersion: 19`; on main they fail `BackupJsonParser`'s version gate (it expects 18), which is why `BeliefScoreTest`/`BeliefFitTest`/`BeliefPolicyBacktestTest` are RED locally today. Bumping aligns them. (`AppDatabase.version` stays 18 until Task 4 — the two constants diverge transiently within the branch; final state reconciles both at 19.)

Check for a coupling assertion first:
```bash
grep -rn "DB_VERSION" app/src/test app/src/androidTest
```
If a test asserts `WorkoutBackup.DB_VERSION == <AppDatabase version>`, note it and let Task 4 re-green it; do not weaken the assertion here.

- [ ] **Step 6: Switch `BacktestData` to live expansion**

Replace the `initialOverrides`/`sessionOverrides` vals (they read `backup.exerciseStrengthOverrides`) with:

```kotlin
private val seeds = ExerciseSeedExpansion.buildSeeds(
    initialOverrides = backup.baselineOverrides.filter { it.sessionId == null },
    sessionOverrides = backup.baselineOverrides.filter { it.sessionId != null },
    sex = backup.userProfile.firstOrNull()?.sex ?: Sex.MALE,
    level = backup.userProfile.firstOrNull()?.strengthLevel ?: StrengthLevel.MEDIUM,
    exerciseMuscle = backup.exercises.associate { it.id to it.primaryMuscle },
    coefById = backup.exercises.filterNot { it.isDisliked }.associate { it.id to (ExerciseCoefficients.get(it) ?: 0f) },
)
val initialSeeds: List<SeedBelief> = seeds.initial
val sessionSeeds: Map<Long, List<SeedBelief>> = seeds.bySession
```

Confirm the `WorkoutBackup` field name for per-muscle rows (`baselineOverrides`) and profile accessor via:
```bash
grep -n "baselineOverride\|userProfile" app/src/main/java/io/github/fowles/stochastic_strength/domain/backup/WorkoutBackup.kt
```
Update every consumer found in Step 1 to use `initialSeeds`/`sessionSeeds`.

- [ ] **Step 7: Run the replay/backtest unit tests (pre-repin)**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "*ReplayEngine*" --tests "*ExerciseProgressionSeriesBuilder*" --tests "*BeliefStackReplay*" --tests "*BacktestData*"
```
Expected: these compile and pass (they assert relative/structural properties, not the pinned score). Fix any that hard-coded seed values from the old materialized rows.

- [ ] **Step 8: Run the pinned gate and read the new number**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefScoreTest" --tests "io.github.fowles.stochastic_strength.domain.backtest.BeliefPolicyBacktestTest"
```
Expected: they now LOAD (version gate passes) but likely FAIL the pin — the seed source changed (materialized rows → live `baseline_override` + `StartingWeights`). Record the printed total/per-set score.

- [ ] **Step 9: Re-pin to the measured value (HUMAN CHECKPOINT)**

Update the pinned expected constant in `BeliefScoreTest` (and re-assert the invariant in `BeliefPolicyBacktestTest`) to the measured number. **Surface the before/after score to the human at the review checkpoint** with a one-line rationale ("seed source changed from materialized `exercise_strength_override` to live `baseline_override × current coefficients`; expected"). Do not accept the task until the human signs off on the new pin.

- [ ] **Step 10: Run to verify pass**

Run:
```bash
./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.*"
```
Expected: PASS.

- [ ] **Step 11: Commit**

```bash
jj describe -m "feat(seed): replay sources cold-start seeds live from baseline_override; re-baseline gate"
```

---

## Task 3: Manual override → ephemeral (A2)

Drop the durable `applyManualExerciseOverrides` belief-reset write. The plan-time weight edit still drives the performed weight; the set log records the outcome.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt:196-212` (delete `applyManualExerciseOverrides`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt:126` (delete the call)
- Test: find + update existing tests (grep below); the behavioral contract is exercised via the backtest/replay already, so no new heavyweight repository test is required if none exists.

- [ ] **Step 1: Find existing coverage + call sites**

Run:
```bash
grep -rn "applyManualExerciseOverrides" app/src
```
Expected call sites: `WorkoutRepository.kt` (definition), `WorkoutSessionController.kt:126`, possibly a `WorkoutSessionController`/`WorkoutRepository` test. Read the surrounding controller code (lines ~118–130) to confirm `plan.exerciseOverrides` (the plan-time weight edits) is *separately* used to drive the performed weight and is NOT removed.

- [ ] **Step 2: Delete the durable write**

Remove the entire `applyManualExerciseOverrides` function from `WorkoutRepository.kt` and the `repository.applyManualExerciseOverrides(sessionId, plan.exerciseOverrides)` call in `WorkoutSessionController.kt`. If `plan.exerciseOverrides` is now unused in the controller, confirm it is still consumed where the session's weights are built (grep `exerciseOverrides` / `exerciseE1rmOverrides`); leave that plumbing intact.

- [ ] **Step 3: Update/remove now-invalid tests**

For any test found in Step 1 that asserted an override row was written or a belief was reset by the manual override, either delete it or convert it to assert the new contract: a manual override does not write durable state; a *downward* override contradicted by an RIR-high set does not stick (the set is a one-sided lower bound and cannot pull `mu` down). Keep it lightweight — reuse existing repository test scaffolding if present; otherwise a `ReplayEngine`/`BeliefFold`-level assertion suffices.

- [ ] **Step 4: Run the affected tests + compile**

Run:
```bash
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "*WorkoutRepository*" --tests "*WorkoutSessionController*"
```
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj describe -m "feat(seed): drop durable manual-override write; edits stay ephemeral via the set log"
```

---

## Task 4: Delete `exercise_strength_override` + migration to v19 (A4)

No readers or writers of the table's data feed replay anymore. Delete it end-to-end and drop the defunct `perExerciseSeedsBackfilled` flag.

**Files:**
- Delete: `data/model/ExerciseStrengthOverride.kt`, `data/dao/ExerciseStrengthOverrideDao.kt`
- Modify: `data/AppDatabase.kt` (entities list, DAO accessor, `MIGRATION_18_19`, forward-list, `version = 19`)
- Modify: `data/model/UserProfile.kt` (remove `perExerciseSeedsBackfilled`)
- Modify: `domain/WorkoutRepository.kt:265-283` (`seedInitialWeights` → `UserProfile`-only)
- Modify: `domain/backup/{WorkoutBackup,BackupJson,BackupManager}.kt` (drop table + flag)
- Modify: `app/src/androidTest/.../data/MigrationTest.kt` (register + test `MIGRATION_18_19`)

- [ ] **Step 1: Write the failing instrumented migration test**

Add to `MigrationTest.kt`, mirroring the existing `migrate17To18_...` test (open a v18 DB via a raw `SupportSQLiteOpenHelper.Callback(18)` that creates `exercise_strength_override` + a `user_profile` row with `perExerciseSeedsBackfilled`, then migrate with `MIGRATION_18_19` and assert):

```kotlin
@Test
fun migrate18To19_dropsExerciseStrengthOverrideAndSeedsBackfilledColumn() {
    val dbName = "migration-test-db-18"
    context.deleteDatabase(dbName)
    // ... open at v18 with the raw helper, INSERT a user_profile row + an exercise_strength_override row ...
    val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
        .addMigrations(AppDatabase.MIGRATION_18_19)
        .build()
    db.openHelper.writableDatabase.use { raw ->
        // table is gone
        raw.query("SELECT name FROM sqlite_master WHERE type='table' AND name='exercise_strength_override'").use {
            assertEquals(0, it.count)
        }
        // column is gone but the row survived
        raw.query("SELECT * FROM user_profile").use { c ->
            assertEquals(1, c.count)
            assertEquals(-1, c.columnNames.indexOf("perExerciseSeedsBackfilled"))
        }
    }
    db.close(); context.deleteDatabase(dbName)
}
```

Fill the `...` following the exact pattern of `migrate17To18_addsIsAsymmetricAndFlipsTBarRow` already in the file. Also register `AppDatabase.MIGRATION_18_19` in every forward-migrate `.addMigrations(...)` list in `MigrationTest.kt` (there are several — grep `MIGRATION_17_18` in the test).

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.data.MigrationTest.migrate18To19_dropsExerciseStrengthOverrideAndSeedsBackfilledColumn"`
Expected: FAIL — `MIGRATION_18_19` unresolved.

- [ ] **Step 3: Add `MIGRATION_18_19`**

In `AppDatabase.Companion`, after `MIGRATION_17_18`. Drop the table; rebuild `user_profile` without the column (12-step rebuild — SQLite `DROP COLUMN` needs 3.35+, not guaranteed at minSdk 33):

```kotlin
internal val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS `exercise_strength_override`")
        db.execSQL(
            "CREATE TABLE `user_profile_new` (" +
                "`id` INTEGER NOT NULL, `sex` TEXT NOT NULL, `strengthLevel` TEXT NOT NULL, " +
                "`weightUnit` TEXT NOT NULL, `preferredExerciseCount` INTEGER, " +
                "`preferredRepMin` INTEGER, `preferredRepMax` INTEGER, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO `user_profile_new` " +
                "(`id`, `sex`, `strengthLevel`, `weightUnit`, `preferredExerciseCount`, `preferredRepMin`, `preferredRepMax`) " +
                "SELECT `id`, `sex`, `strengthLevel`, `weightUnit`, `preferredExerciseCount`, `preferredRepMin`, `preferredRepMax` FROM `user_profile`"
        )
        db.execSQL("DROP TABLE `user_profile`")
        db.execSQL("ALTER TABLE `user_profile_new` RENAME TO `user_profile`")
    }
}
```

Register it in `buildDatabase`'s `.addMigrations(...)` after `MIGRATION_17_18`, and set `version = 19` in the `@Database` annotation.

- [ ] **Step 4: Delete the entity, DAO, and their wiring**

- Delete `data/model/ExerciseStrengthOverride.kt` and `data/dao/ExerciseStrengthOverrideDao.kt`.
- In `AppDatabase.kt`: remove `ExerciseStrengthOverride::class` from `entities`, the `import`, and `abstract fun exerciseStrengthOverrideDao()`.
- In `UserProfile.kt`: remove the `perExerciseSeedsBackfilled` field.

- [ ] **Step 5: Simplify `seedInitialWeights` and clean remaining refs**

`WorkoutRepository.seedInitialWeights` becomes profile-only (seeds are now synthesized live from `StartingWeights` defaults for a user with no `baseline_override` rows):

```kotlin
suspend fun seedInitialWeights(sex: Sex, strengthLevel: StrengthLevel, weightUnit: WeightUnit) {
    db.userProfileDao().insert(UserProfile(sex = sex, strengthLevel = strengthLevel, weightUnit = weightUnit))
    replayDerivedState()
}
```

Remove the now-unused `StartingWeights.seedInitialE1rm` / `exerciseSeedE1rm` only if nothing else references them (grep first; likely now dead — if so delete them, per the "remove dead code" habit). Grep for any lingering `exerciseStrengthOverride` / `ExerciseStrengthOverride` / `perExerciseSeedsBackfilled` in `app/src/main` and clean each.

- [ ] **Step 6: Drop the table + flag from backup**

- `WorkoutBackup.kt`: remove `exerciseStrengthOverrides` field + import; remove `perExerciseSeedsBackfilled` from the `UserProfile` construction if referenced.
- `BackupJson.kt`: remove `.put("exerciseStrengthOverrides", ...)` (build), the `strengthObj`/`strength` helpers, the `exerciseStrengthOverrides = tables.getJSONArray("exerciseStrengthOverrides")...` (parse), and `perExerciseSeedsBackfilled` from both profile build (line ~97) and parse (line ~193). Parse must tolerate the key's absence going forward (do not `getJSONArray` it).
- `BackupManager.kt`: remove the export (`exerciseStrengthOverrides = ...`), the `deleteAll()` in the destructive-import clear, and the import loop line.
- Keep `WorkoutBackup.DB_VERSION = 19` (set in Task 2).

- [ ] **Step 7: Run to verify pass — unit + the new migration test**

Run:
```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.data.MigrationTest"
```
Expected: PASS. Fix any backup round-trip test that asserted the dropped fields (update it to the new shape).

- [ ] **Step 8: Commit**

```bash
jj describe -m "feat(seed): delete exercise_strength_override table (migration v18->v19); backup + seedInitialWeights follow"
```

---

## Task 5: Full verification, review, and docs

**Files:**
- Modify: `CLAUDE.md` (DB version note; progression seeding note)
- Review: whole branch

- [ ] **Step 1: Full JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (record count). Confirm the belief gate is green at the re-pinned value.

- [ ] **Step 2: Full instrumented suite**

Run: `./gradlew :app:connectedAndroidTest`
Expected: PASS (record count). `MigrationTest` walks 2→19.

- [ ] **Step 3: Lint / assemble**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Update `CLAUDE.md`**

- Bump the Database section: "Room database (`AppDatabase`, version 19)."
- In the Progression section, note that cold-start seeds are **synthesized live** during replay from per-muscle `baseline_override` (or `StartingWeights` defaults) × current `ExerciseCoefficients` — there is no `exercise_strength_override` table; shipping a new coefficient table needs no migration.

- [ ] **Step 5: Whole-branch code review**

Use superpowers:requesting-code-review (opus, whole branch). Address Critical/Important; surface the gate re-baseline number in the summary.

- [ ] **Step 6: Commit docs + finalize**

```bash
jj describe -m "docs: seeds are live-derived; AppDatabase v19"
```

---

## Self-Review

**Spec coverage (Part A of `2026-07-31-fitted-coefficients-and-derived-state-cleanup-design.md`):**
- A1 (live seed expansion) → Tasks 1–2. ✓
- A2 (manual override → ephemeral) → Task 3. ✓
- A3 (detrain → inferred) → already shipped (Plan 1); out of this plan. ✓
- A4 (delete `exercise_strength_override` + migration) → Task 4. ✓
- Runtime-λ simplification → N/A (λ not on main). Noted in Global Constraints. ✓
- Part B (`CoefficientGuesses` generator + λ fit) → out of scope (Plan 3). ✓

**Type consistency:** `SeedBelief`, `ExerciseSeedExpansion.{MuscleBaseline,Seeds,expand,buildSeeds}`, and `runCore(initialSeeds, sessionSeeds)` names are used identically across Tasks 1–2 and BacktestData. `DB_VERSION`/`version` both reach 19 (Task 2 sets backup constant; Task 4 sets DB version + migration).

**Open risk (flagged for the human):** the gate re-baseline in Task 2 Step 9 is a judgment call — the seed source changes from (possibly usv-compressed) materialized rows to live `baseline_override × current coefficients`. The measured shift must be reviewed, not rubber-stamped.
