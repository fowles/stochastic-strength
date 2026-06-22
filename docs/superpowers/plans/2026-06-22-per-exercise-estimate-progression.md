# Per-Exercise Estimate Progression Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the gauge-conserving `RollingConservingProgressionController` with a per-exercise strength estimate (point + confidence) that updates locally, pools across siblings at read time, and removes the muscle-baseline×coefficient identifiability problem.

**Architecture:** Each loaded exercise holds a derived `ExerciseEstimate { lnE, confidence, updatedAt }`, folded from session signals by a pure `ExerciseEstimateUpdater`. A pure `MuscleStrengthProjector` turns the estimate map into the existing display/prescription projections (`MuscleGroupStrength` = muscle level `L`, and a derived per-exercise coefficient `E_used / L`) so `baseline × coef == E_used` and the planner + UI read surface are unchanged. Persisted seeds/overrides move from muscle-granular (`baseline_override`) to exercise-granular (`exercise_strength_override`), seeded by a one-time Kotlin backfill that expands each muscle row via the seed coefficients.

**Tech Stack:** Kotlin, Android, Room (SQLite), Jetpack Compose, JUnit4 (JVM unit tests + instrumented `androidTest` for migrations).

## Global Constraints

- Package root: `io.github.fowles.stochastic_strength`.
- Min SDK 33, Target SDK 36. Kotlin + Compose; no XML layouts.
- App has real users: every DB version bump needs a proper `Migration` — destructive fallback is NOT configured. Bumping the DB version requires updating `MigrationTest` forward lists and the exported schema JSON under `app/schemas/`.
- Build: `./gradlew :app:assembleDebug`. Unit tests: `./gradlew :app:testDebugUnitTest`. Single class: `./gradlew :app:testDebugUnitTest --tests "FQCN"`. Instrumented: `./gradlew :app:connectedAndroidTest` (emulator is typically running; attempt directly).
- Version control is jj. Commit at each task's final step with `jj describe -m "<msg>"` then `jj new` to start the next change. Every commit message ends with the `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>` trailer.
- Strength math is multiplicative: all estimate updates and pooling happen in **log space**. 1RM conversions use `DefaultProgressionEngine.rawToOneRepMax` / `rawFromOneRepMax`.
- Tuning constants live in `EstimatorConfig` with the defaults given in Task 1; do not scatter magic numbers.

**Spec:** `docs/superpowers/specs/2026-06-21-per-exercise-estimate-progression-design.md`.

**Deviation from spec (intentional, lower-risk):** the spec says `WorkoutPlanner.weightForExercise` calls `SiblingPredictor` live. This plan instead projects the predictor output into the existing `MuscleGroupStrength`/coefficient views at replay time (with a launch-time decay pass), leaving the planner weight formula unchanged. Prescriptions are identical (`baseline × coef == E_used`). The pure `SiblingPredictor`/`MuscleStrengthProjector` logic is unchanged; only its call site differs.

---

## File Structure

New files:
- `domain/progression/ExerciseEstimate.kt` — estimate value type + `EstimatorConfig`.
- `domain/progression/ExerciseEstimateUpdater.kt` — pure session-fold (decay, asymmetric-W log-EMA, cap, HURT).
- `domain/progression/MuscleStrengthProjector.kt` — pure read-path: muscle level `L`, effective e1rm per exercise, derived coefficient.
- `domain/progression/ExerciseSeedExpansion.kt` — pure muscle-override → per-exercise-override expansion.
- `data/model/ExerciseStrengthOverride.kt` — Room entity (per-exercise seeds/overrides).
- `data/dao/ExerciseStrengthOverrideDao.kt` — DAO.
- `domain/ExerciseStrengthOverrideBackfill.kt` — one-time Kotlin backfill (DB wrapper around `ExerciseSeedExpansion`).
- Test files alongside (see each task).

Modified files:
- `data/AppDatabase.kt` — v17, `Migration_16_17`, register entity + DAO + migration.
- `data/model/UserProfile.kt` — add `perExerciseSeedsBackfilled` flag.
- `domain/ReplaySnapshot.kt` — carry `currentEstimates`.
- `domain/WorkoutRepository.kt` — replay rewire, `seedInitialWeights`, `applyManualBaselineOverrides`, `applyDetrainingReduction`, `buildPlanner` override param.
- `domain/WorkoutPlanner.kt` — per-exercise e1rm overrides; replace `deriveBaselineFromSessionWeight`/`recomputeExercise`.
- `domain/StartingWeights.kt` — `exerciseSeedE1rm` mechanism + fallback.
- `domain/DerivedStateBackfill.kt` — run the override backfill.
- `domain/model/WorkoutPlan.kt` — `exerciseOverrides: Map<Long, Float>`.
- `ui/workout/WorkoutSessionController.kt` — per-exercise manual edit + detraining.
- `StochasticStrengthApp.kt` — drop `progressionControllerFactory`.

Deleted files:
- `domain/ProgressionController.kt`, `domain/RobustCenter.kt`.
- Tests: `ProgressionControllerSimulationTest.kt` (rewritten), `ProgressionControllerTest.kt`, `BulgarianBracketCharacterizationTest.kt` (adapted).

---

## Task 1: Exercise estimate value type + updater

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimate.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimateUpdater.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimateUpdaterTest.kt`

**Interfaces:**
- Produces:
  - `data class ExerciseEstimate(val lnE: Float, val confidence: Float, val updatedAt: Long)` with `val e1rm: Float` and `companion object { fun seed(e1rm: Float, at: Long): ExerciseEstimate }`.
  - `data class EstimatorConfig(...)` with the fields/defaults below.
  - `class ExerciseEstimateUpdater(config)` with `fun decayedConfidence(prior, now): Float`, `fun fold(prior, obsE1rm, bracketConfidence, now): ExerciseEstimate`, `fun hurt(prior, now): ExerciseEstimate`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

class ExerciseEstimateUpdaterTest {

    private val updater = ExerciseEstimateUpdater()
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun seedHasZeroConfidenceAndExactE1rm() {
        val e = ExerciseEstimate.seed(100f, at = 0L)
        assertEquals(100f, e.e1rm, 1e-3f)
        assertEquals(0f, e.confidence, 0f)
    }

    @Test
    fun upSignalMovesEstimateGentlyTowardObservation() {
        val prior = ExerciseEstimate(lnE = ln(100f), confidence = 2f, updatedAt = 0L)
        // Observation above current estimate -> gentle up move.
        val next = updater.fold(prior, obsE1rm = 120f, bracketConfidence = 0f, now = 0L)
        assertTrue("should move up", next.e1rm > 100f)
        assertTrue("up move is gentle (well below the observation)", next.e1rm < 110f)
        assertTrue("confidence grows", next.confidence > prior.confidence)
    }

    @Test
    fun downSignalMovesEstimateFastTowardObservation() {
        val prior = ExerciseEstimate(lnE = ln(100f), confidence = 2f, updatedAt = 0L)
        // Observation below current estimate -> fast down move (asymmetric W).
        val next = updater.fold(prior, obsE1rm = 90f, bracketConfidence = 0f, now = 0L)
        assertTrue("down move tracks fast (past the midpoint)", next.e1rm < 95f)
    }

    @Test
    fun bracketConfidenceSnapsDownEvenHarder() {
        val prior = ExerciseEstimate(lnE = ln(100f), confidence = 5f, updatedAt = 0L)
        val soft = updater.fold(prior, obsE1rm = 90f, bracketConfidence = 0f, now = 0L)
        val snap = updater.fold(prior, obsE1rm = 90f, bracketConfidence = 1f, now = 0L)
        assertTrue("bracket snaps down further than a plain down signal", snap.e1rm < soft.e1rm)
    }

    @Test
    fun confidenceDecaysWithStaleness() {
        val prior = ExerciseEstimate(lnE = ln(100f), confidence = 4f, updatedAt = 0L)
        val halfLife = EstimatorConfig().halfLifeMs
        val decayed = updater.decayedConfidence(prior, now = halfLife)
        assertEquals(2f, decayed, 1e-2f)
    }

    @Test
    fun confidenceIsCappedSoLongTrainedExercisesStayAdaptive() {
        var e = ExerciseEstimate.seed(100f, at = 0L)
        repeat(50) { e = updater.fold(e, obsE1rm = 100f, bracketConfidence = 0f, now = 0L) }
        assertTrue("confidence capped", e.confidence <= EstimatorConfig().confidenceCap + 1e-3f)
    }

    @Test
    fun hurtBacksOffByConfiguredFactor() {
        val prior = ExerciseEstimate(lnE = ln(100f), confidence = 3f, updatedAt = 0L)
        val next = updater.hurt(prior, now = 0L)
        assertEquals(100f * EstimatorConfig().hurtFactor, next.e1rm, 0.5f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimateUpdaterTest"`
Expected: FAIL — `ExerciseEstimate` / `ExerciseEstimateUpdater` unresolved.

- [ ] **Step 3: Write `ExerciseEstimate.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.exp
import kotlin.math.ln

/**
 * One loaded exercise's derived strength estimate, in log space.
 *
 * [lnE] is ln(estimated 1RM, kg). [confidence] is a recency-decayed effective sample size:
 * it grows as sessions are folded in (capped by [EstimatorConfig.confidenceCap]) and decays
 * with staleness so a long-unseen exercise leans on its siblings at read time.
 */
data class ExerciseEstimate(
    val lnE: Float,
    val confidence: Float,
    val updatedAt: Long,
) {
    val e1rm: Float get() = exp(lnE)

    companion object {
        fun seed(e1rm: Float, at: Long): ExerciseEstimate =
            ExerciseEstimate(lnE = ln(e1rm), confidence = 0f, updatedAt = at)
    }
}

/**
 * Tuning constants for the per-exercise estimator and the read-time pooling. All in one place;
 * pinned by ExerciseEstimatorSimulationTest.
 */
data class EstimatorConfig(
    /** Confidence half-life. ~21 days, matching the prior controller's recency decay. */
    val halfLifeMs: Long = 21L * 24 * 60 * 60 * 1000,
    /** Cap on confidence so a long-trained exercise keeps a floor learning rate (EMA-like). */
    val confidenceCap: Float = 6f,
    /** Observation weight for an up-signal (gentle progressive overload). */
    val wUp: Float = 0.5f,
    /** Observation weight for a down-signal (fast tracking so a failed weight is not re-prescribed). */
    val wDown: Float = 3f,
    /** Down-signal weight at full bracketConfidence (demonstrated drop-cascade); interpolated from [wDown]. */
    val wDownSnap: Float = 8f,
    /** HURT multiplies the estimate by this factor. */
    val hurtFactor: Float = 0.85f,
    /** Sibling-prior strength (kappa) in the read-time shrink: how many confidence units the pool is worth. */
    val priorStrength: Float = 1.0f,
    /** Minimum decayed confidence for an exercise to vote in the muscle level / be trusted as its own estimate. */
    val confidentThreshold: Float = 1.0f,
)
```

- [ ] **Step 4: Write `ExerciseEstimateUpdater.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.ln
import kotlin.math.pow

/**
 * Pure session-fold for one exercise's [ExerciseEstimate]. Local: a session for exercise i moves
 * only i's estimate, so a failure never touches siblings (Goal 3 is structural). Cross-informing
 * happens later, at read time, in [MuscleStrengthProjector].
 */
class ExerciseEstimateUpdater(private val config: EstimatorConfig = EstimatorConfig()) {

    /** Confidence decayed from [prior.updatedAt] to [now] by the configured half-life. */
    fun decayedConfidence(prior: ExerciseEstimate, now: Long): Float {
        val age = (now - prior.updatedAt).coerceAtLeast(0L)
        return prior.confidence * 0.5f.pow(age.toFloat() / config.halfLifeMs)
    }

    /**
     * Fold one session's aggregated observation into the estimate. [obsE1rm] is the session's
     * implied 1RM (from SessionSignalExtractor). When the observation is below the current estimate
     * (a failure / low-RIR session) the observation weight is large so the estimate snaps down;
     * [bracketConfidence] (a demonstrated drop-cascade) pushes that weight further toward [wDownSnap].
     */
    fun fold(prior: ExerciseEstimate, obsE1rm: Float, bracketConfidence: Float, now: Long): ExerciseEstimate {
        val c = decayedConfidence(prior, now)
        val obsLn = ln(obsE1rm)
        val isDown = obsLn < prior.lnE
        val s = bracketConfidence.coerceIn(0f, 1f)
        val w = if (!isDown) config.wUp else config.wDown + (config.wDownSnap - config.wDown) * s
        val lnE = (c * prior.lnE + w * obsLn) / (c + w)
        val confidence = (c + w).coerceAtMost(config.confidenceCap)
        return ExerciseEstimate(lnE = lnE, confidence = confidence, updatedAt = now)
    }

    /** HURT: back the estimate off by [hurtFactor]; confidence decays to [now] but is retained. */
    fun hurt(prior: ExerciseEstimate, now: Long): ExerciseEstimate =
        ExerciseEstimate(
            lnE = prior.lnE + ln(config.hurtFactor),
            confidence = decayedConfidence(prior, now),
            updatedAt = now,
        )
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimateUpdaterTest"`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat: per-exercise ExerciseEstimate + ExerciseEstimateUpdater (pure)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

## Task 2: Muscle-level projector (read-path pooling)

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjector.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjectorTest.kt`

**Interfaces:**
- Consumes: `ExerciseEstimate`, `EstimatorConfig` (Task 1).
- Produces:
  - `data class MuscleProjection(val level: Float, val effectiveE1rm: Map<Long, Float>, val derivedCoef: Map<Long, Float>)`
  - `class MuscleStrengthProjector(config)` with
    `fun project(estimates: Map<Long, ExerciseEstimate>, seedCoef: Map<Long, Float>, muscleExerciseIds: List<Long>, now: Long): MuscleProjection`.
- `level` is the muscle level `L`; `effectiveE1rm[i]` is the shrunk prescription target `E_used`; `derivedCoef[i] = effectiveE1rm[i] / level`. By construction `level * derivedCoef[i] == effectiveE1rm[i]`, so a downstream `baseline × coef` reproduces the prescription.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class MuscleStrengthProjectorTest {

    private val projector = MuscleStrengthProjector()

    private fun est(e1rm: Float, conf: Float) = ExerciseEstimate(lnE = ln(e1rm), confidence = conf, updatedAt = 0L)

    @Test
    fun confidentExerciseUsesItsOwnEstimate() {
        val estimates = mapOf(1L to est(100f, conf = 6f), 2L to est(60f, conf = 6f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val p = projector.project(estimates, seed, muscleExerciseIds = listOf(1L, 2L), now = 0L)
        assertEquals("confident exercise prescribes its own estimate", 100f, p.effectiveE1rm.getValue(1L), 1f)
        assertEquals(60f, p.effectiveE1rm.getValue(2L), 1f)
    }

    @Test
    fun coldExerciseBorrowsFromConfidentSiblings() {
        // Exercise 2 is cold (conf 0); its sibling 1 is well trained at 100 with seed ratio 0.6.
        val estimates = mapOf(1L to est(100f, conf = 6f), 2L to est(40f, conf = 0f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val p = projector.project(estimates, seed, muscleExerciseIds = listOf(1L, 2L), now = 0L)
        // Sibling-implied target = L * seed_2 = (100/1.0) * 0.6 = 60, not the stale seed of 40.
        assertEquals("cold exercise pulled toward sibling prediction", 60f, p.effectiveE1rm.getValue(2L), 3f)
    }

    @Test
    fun levelTimesDerivedCoefReproducesEffectiveE1rm() {
        val estimates = mapOf(1L to est(100f, conf = 6f), 2L to est(55f, conf = 2f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val p = projector.project(estimates, seed, muscleExerciseIds = listOf(1L, 2L), now = 0L)
        for (id in listOf(1L, 2L)) {
            assertEquals(p.effectiveE1rm.getValue(id), p.level * p.derivedCoef.getValue(id), 1e-2f)
        }
    }

    @Test
    fun noConfidentSiblingsFallsBackToOwnSeedEstimate() {
        // Everything cold -> each exercise just uses its own seed estimate.
        val estimates = mapOf(1L to est(100f, conf = 0f), 2L to est(60f, conf = 0f))
        val seed = mapOf(1L to 1.0f, 2L to 0.6f)
        val p = projector.project(estimates, seed, muscleExerciseIds = listOf(1L, 2L), now = 0L)
        assertEquals(100f, p.effectiveE1rm.getValue(1L), 1e-2f)
        assertEquals(60f, p.effectiveE1rm.getValue(2L), 1e-2f)
        assertTrue("level is positive", p.level > 0f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjectorTest"`
Expected: FAIL — `MuscleStrengthProjector` unresolved.

- [ ] **Step 3: Write `MuscleStrengthProjector.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.exp
import kotlin.math.ln

data class MuscleProjection(
    /** Muscle level L: confidence-weighted geomean of E_j / seedCoef_j over confident exercises. */
    val level: Float,
    /** Shrunk prescription target per exercise (own estimate blended toward the sibling prediction). */
    val effectiveE1rm: Map<Long, Float>,
    /** Display/prescription coefficient: effectiveE1rm[i] / level (so level * coef == effectiveE1rm). */
    val derivedCoef: Map<Long, Float>,
)

/**
 * Read-time pooling. Computes a muscle level from confidently-trained exercises, predicts each
 * exercise from that level via its seed coefficient, and shrinks each exercise's own estimate
 * toward its prediction by confidence. Pure; cross-informing happens here and never mutates the
 * stored per-exercise estimates.
 */
class MuscleStrengthProjector(private val config: EstimatorConfig = EstimatorConfig()) {

    fun project(
        estimates: Map<Long, ExerciseEstimate>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        now: Long,
    ): MuscleProjection {
        fun conf(e: ExerciseEstimate): Float {
            val age = (now - e.updatedAt).coerceAtLeast(0L)
            return e.confidence * 0.5f.pow(age.toFloat() / config.halfLifeMs)
        }

        // Muscle level L = conf-weighted geomean of E_j / seedCoef_j over confident loaded exercises.
        val votes = muscleExerciseIds.mapNotNull { id ->
            val e = estimates[id] ?: return@mapNotNull null
            val coef = seedCoef[id] ?: return@mapNotNull null
            val c = conf(e)
            if (coef <= 0f || c < config.confidentThreshold) return@mapNotNull null
            Pair(e.lnE - ln(coef), c) // ln(E_j / coef_j), weight c
        }
        val lnLevel: Float? =
            if (votes.isEmpty()) null
            else votes.sumOf { (it.first * it.second).toDouble() }.toFloat() /
                votes.sumOf { it.second.toDouble() }.toFloat()
        val level = lnLevel?.let { exp(it) } ?: fallbackLevel(estimates, seedCoef, muscleExerciseIds)

        val effective = mutableMapOf<Long, Float>()
        val coefs = mutableMapOf<Long, Float>()
        for (id in muscleExerciseIds) {
            val e = estimates[id] ?: continue
            val coef = seedCoef[id] ?: continue
            if (coef <= 0f) continue
            val cSelf = conf(e)
            val lnPred = if (lnLevel != null) ln(coef) + lnLevel else e.lnE // cold muscle -> own seed
            val lnUsed = (cSelf * e.lnE + config.priorStrength * lnPred) / (cSelf + config.priorStrength)
            val used = exp(lnUsed)
            effective[id] = used
            coefs[id] = if (level > 0f) used / level else coef
        }
        return MuscleProjection(level = level, effectiveE1rm = effective, derivedCoef = coefs)
    }

    /** When no exercise is confident, pick a representative level so display has a value. */
    private fun fallbackLevel(
        estimates: Map<Long, ExerciseEstimate>,
        seedCoef: Map<Long, Float>,
        ids: List<Long>,
    ): Float {
        val lvls = ids.mapNotNull { id ->
            val e = estimates[id] ?: return@mapNotNull null
            val coef = seedCoef[id] ?: return@mapNotNull null
            if (coef <= 0f) null else e.lnE - ln(coef)
        }
        return if (lvls.isEmpty()) 0f else exp(lvls.average().toFloat())
    }

    private fun Float.pow(x: Float): Float = Math.pow(this.toDouble(), x.toDouble()).toFloat()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.MuscleStrengthProjectorTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
jj describe -m "feat: MuscleStrengthProjector read-time sibling pooling (pure)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

## Task 3: Per-exercise override entity, DAO, and seed-expansion logic

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/ExerciseStrengthOverride.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/data/dao/ExerciseStrengthOverrideDao.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseSeedExpansion.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseSeedExpansionTest.kt`

**Interfaces:**
- Consumes: `BaselineChangeReason`, `MuscleGroup`, `Exercise`, `CoefficientSource` (existing).
- Produces:
  - `@Entity(tableName = "exercise_strength_override") data class ExerciseStrengthOverride(id, sessionId: Long?, exerciseId: Long, e1rm: Float, asOf: Long, reason: BaselineChangeReason)`.
  - `ExerciseStrengthOverrideDao` with `getInitials()`, `getNonInitials()`, `getForSession(sessionId)`, `insert(row): Long`, `deleteInitialFor(exerciseId)`.
  - `object ExerciseSeedExpansion { fun expand(muscleOverrides, exercises, coefSource): List<ExerciseStrengthOverride> }` where `muscleOverrides: List<MuscleOverrideRow>` and `data class MuscleOverrideRow(sessionId: Long?, muscleGroup: MuscleGroup, baselineWeight: Float, asOf: Long, reason: BaselineChangeReason)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.CoefficientSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseSeedExpansionTest {

    private fun ex(id: Long, muscle: MuscleGroup) = Exercise(
        id = id, name = "ex$id", primaryMuscle = muscle, secondaryMuscles = emptyList(),
        equipment = Equipment.BARBELL, isDisliked = false, isUnilateral = false, isTimed = false,
    )

    private val coef = object : CoefficientSource {
        override fun get(exercise: Exercise): Float? = when (exercise.id) {
            1L -> 1.0f; 2L -> 0.6f; 3L -> 0.0f; else -> null
        }
    }

    @Test
    fun expandsOneMuscleRowIntoPerExerciseRows() {
        val rows = ExerciseSeedExpansion.expand(
            muscleOverrides = listOf(
                ExerciseSeedExpansion.MuscleOverrideRow(null, MuscleGroup.CHEST, 80f, 0L, BaselineChangeReason.INITIAL),
            ),
            exercises = listOf(ex(1L, MuscleGroup.CHEST), ex(2L, MuscleGroup.CHEST), ex(3L, MuscleGroup.CHEST)),
            coefSource = coef,
        )
        // Loaded chest exercises 1 (coef 1.0) and 2 (coef 0.6) get rows; 3 (coef 0) is skipped.
        assertEquals(2, rows.size)
        assertEquals(80f, rows.first { it.exerciseId == 1L }.e1rm, 1e-3f)
        assertEquals(48f, rows.first { it.exerciseId == 2L }.e1rm, 1e-3f)
        assertTrue("zero-coef exercise excluded", rows.none { it.exerciseId == 3L })
    }

    @Test
    fun preservesSessionAsOfAndReason() {
        val rows = ExerciseSeedExpansion.expand(
            muscleOverrides = listOf(
                ExerciseSeedExpansion.MuscleOverrideRow(7L, MuscleGroup.CHEST, 90f, 1234L, BaselineChangeReason.DETRAIN),
            ),
            exercises = listOf(ex(1L, MuscleGroup.CHEST)),
            coefSource = coef,
        )
        assertEquals(1, rows.size)
        assertEquals(7L, rows[0].sessionId)
        assertEquals(1234L, rows[0].asOf)
        assertEquals(BaselineChangeReason.DETRAIN, rows[0].reason)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseSeedExpansionTest"`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the entity, DAO, and expansion**

`ExerciseStrengthOverride.kt`:
```kotlin
package io.github.fowles.stochastic_strength.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Per-exercise strength seed/override (replaces the per-muscle baseline_override).
 *
 * - `sessionId = null` is the *initial* estimated 1RM for [exerciseId] (replay starting point).
 * - `sessionId = N` is a user edit or detraining adjustment applied at session N.
 * `e1rm` is an estimated 1RM in kg.
 */
@Entity(tableName = "exercise_strength_override")
data class ExerciseStrengthOverride(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long? = null,
    val exerciseId: Long,
    val e1rm: Float,
    val asOf: Long,
    val reason: BaselineChangeReason = BaselineChangeReason.OVERRIDE,
)
```

`ExerciseStrengthOverrideDao.kt`:
```kotlin
package io.github.fowles.stochastic_strength.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride

@Dao
interface ExerciseStrengthOverrideDao {

    @Query("SELECT * FROM exercise_strength_override WHERE sessionId IS NULL")
    suspend fun getInitials(): List<ExerciseStrengthOverride>

    @Query("SELECT * FROM exercise_strength_override WHERE sessionId IS NOT NULL")
    suspend fun getNonInitials(): List<ExerciseStrengthOverride>

    @Query("SELECT * FROM exercise_strength_override WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: Long): List<ExerciseStrengthOverride>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ExerciseStrengthOverride): Long

    @Query("DELETE FROM exercise_strength_override WHERE sessionId IS NULL AND exerciseId = :exerciseId")
    suspend fun deleteInitialFor(exerciseId: Long)
}
```

`ExerciseSeedExpansion.kt`:
```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.CoefficientSource

/** Expands per-muscle baseline overrides into per-exercise overrides via the seed coefficients. */
object ExerciseSeedExpansion {

    data class MuscleOverrideRow(
        val sessionId: Long?,
        val muscleGroup: MuscleGroup,
        val baselineWeight: Float,
        val asOf: Long,
        val reason: BaselineChangeReason,
    )

    fun expand(
        muscleOverrides: List<MuscleOverrideRow>,
        exercises: List<Exercise>,
        coefSource: CoefficientSource,
    ): List<ExerciseStrengthOverride> {
        val loadedByMuscle = exercises
            .mapNotNull { ex -> coefSource.get(ex)?.takeIf { it > 0f }?.let { Triple(ex.primaryMuscle, ex.id, it) } }
            .groupBy({ it.first }, { it.second to it.third })
        return muscleOverrides.flatMap { row ->
            loadedByMuscle[row.muscleGroup].orEmpty().map { (exerciseId, coef) ->
                ExerciseStrengthOverride(
                    sessionId = row.sessionId,
                    exerciseId = exerciseId,
                    e1rm = row.baselineWeight * coef,
                    asOf = row.asOf,
                    reason = row.reason,
                )
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseSeedExpansionTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
jj describe -m "feat: ExerciseStrengthOverride entity/DAO + muscle->exercise seed expansion

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

## Task 4: Database v17 — table, profile flag, migration, schema, MigrationTest

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/AppDatabase.kt` (version, entities, DAO accessor, `MIGRATION_16_17`, registration)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/data/model/UserProfile.kt` (add flag)
- Create: `app/schemas/io.github.fowles.stochastic_strength.data.AppDatabase/17.json` (generated by build)
- Modify: `app/src/androidTest/java/io/github/fowles/stochastic_strength/data/MigrationTest.kt`

**Interfaces:**
- Consumes: `ExerciseStrengthOverride`, `ExerciseStrengthOverrideDao` (Task 3).
- Produces: `AppDatabase.MIGRATION_16_17`, `AppDatabase.exerciseStrengthOverrideDao()`, `UserProfile.perExerciseSeedsBackfilled: Boolean`.

- [ ] **Step 1: Add the profile flag**

In `UserProfile.kt`, add the column with a default so the migration can `ADD COLUMN ... DEFAULT 0`:
```kotlin
    val perExerciseSeedsBackfilled: Boolean = false,
```
(Place it after the existing columns, before the closing paren. Match the existing property style.)

- [ ] **Step 2: Register entity/DAO and bump version in `AppDatabase.kt`**

- Add `ExerciseStrengthOverride::class` to the `@Database(entities = [...])` list.
- Change `version = 16` to `version = 17`.
- Add the DAO accessor: `abstract fun exerciseStrengthOverrideDao(): ExerciseStrengthOverrideDao`.
- Add the import for both classes.

- [ ] **Step 3: Write `MIGRATION_16_17` and register it**

Add alongside the other migrations in `AppDatabase.Companion`:
```kotlin
        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exercise_strength_override` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` INTEGER, `exerciseId` INTEGER NOT NULL, " +
                        "`e1rm` REAL NOT NULL, `asOf` INTEGER NOT NULL, `reason` TEXT NOT NULL)"
                )
                db.execSQL(
                    "ALTER TABLE `user_profile` ADD COLUMN `perExerciseSeedsBackfilled` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }
```
Then add `MIGRATION_16_17` to the `.addMigrations(...)` list.

Note: the muscle→exercise data expansion is NOT done in SQL (it needs the seed coefficients, which live in Kotlin). The empty table + flag are created here; Task 5 populates rows. `baseline_override` is intentionally left in place (read once by the backfill, then vestigial).

- [ ] **Step 4: Generate the v17 schema JSON**

Run: `./gradlew :app:assembleDebug`
Expected: build succeeds and `app/schemas/io.github.fowles.stochastic_strength.data.AppDatabase/17.json` is created. Commit it.

- [ ] **Step 5: Add the migration test**

Append to `MigrationTest.kt`. This builds a v16 DB with the prior schema, inserts a `baseline_override` row, migrates through Room to the current version, and asserts the new table exists and is queryable (empty — population is the Task 5 backfill's job) and the profile column exists.

```kotlin
    @Test
    fun migrate16To17_createsExerciseStrengthOverrideTableAndFlag() {
        val dbName16 = "migration-test-db-16"
        context.deleteDatabase(dbName16)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName16)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `primaryMuscle` TEXT NOT NULL, `secondaryMuscles` TEXT NOT NULL, `equipment` TEXT NOT NULL, `isDisliked` INTEGER NOT NULL, `isUnilateral` INTEGER NOT NULL, `isTimed` INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `known_locations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `location_excluded_exercises` (`locationId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, PRIMARY KEY(`locationId`, `exerciseId`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `workout_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `locationId` INTEGER, `startTime` INTEGER NOT NULL, `endTime` INTEGER, `stravaActivityId` INTEGER)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `workout_sets` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, `setNumber` INTEGER NOT NULL, `targetWeight` REAL NOT NULL, `targetReps` INTEGER NOT NULL, `actualReps` INTEGER, `feedback` TEXT, `completedAt` INTEGER, `durationSeconds` INTEGER)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `user_profile` (`id` INTEGER NOT NULL, `sex` TEXT NOT NULL, `strengthLevel` TEXT NOT NULL, `weightUnit` TEXT NOT NULL, `preferredExerciseCount` INTEGER, `preferredRepMin` INTEGER, `preferredRepMax` INTEGER, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `baseline_override` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER, `muscleGroup` TEXT NOT NULL, `baselineWeight` REAL NOT NULL, `asOf` INTEGER NOT NULL, `reason` TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `exercise_hurt_state` (`exerciseId` INTEGER NOT NULL, `isHurt` INTEGER NOT NULL, `asOf` INTEGER NOT NULL, PRIMARY KEY(`exerciseId`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `baseline_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER, `muscleGroup` TEXT NOT NULL, `previousBaseline` REAL NOT NULL, `newBaseline` REAL NOT NULL, `changeReason` TEXT NOT NULL, `feedbacks` TEXT, `sessionReps` INTEGER, `minReductionFraction` REAL, `timestamp` INTEGER NOT NULL, `heuristicName` TEXT, `heuristicMetadata` TEXT)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `coefficient_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `exerciseId` INTEGER NOT NULL, `previousCoefficient` REAL, `coefficient` REAL NOT NULL, `heuristicName` TEXT NOT NULL, `heuristicMetadata` TEXT, `computedAt` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_coefficient_history_exerciseId` ON `coefficient_history` (`exerciseId`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                        // identity hash placeholder; Room only validates after the migration runs.
                        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '00000000000000000000000000000000')")
                        db.execSQL("INSERT INTO user_profile (id, sex, strengthLevel, weightUnit, preferredExerciseCount, preferredRepMin, preferredRepMax) VALUES (1, 'MALE', 'MEDIUM', 'KG', 5, 5, 10)")
                        db.execSQL("INSERT INTO baseline_override (sessionId, muscleGroup, baselineWeight, asOf, reason) VALUES (NULL, 'CHEST', 80.0, 0, 'INITIAL')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        helper.writableDatabase.close()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName16)
            .addMigrations(AppDatabase.MIGRATION_16_17)
            .allowMainThreadQueries()
            .build()
        try {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM exercise_strength_override").use { c ->
                c.moveToFirst(); assertEquals(0, c.getInt(0))
            }
            db.openHelper.readableDatabase.query("PRAGMA table_info(user_profile)").use { c ->
                val names = mutableListOf<String>()
                while (c.moveToNext()) names += c.getString(c.getColumnIndexOrThrow("name"))
                assertTrue(names.contains("perExerciseSeedsBackfilled"))
            }
        } finally {
            db.close(); context.deleteDatabase(dbName16)
        }
    }
```
Note: if the v16 column list above does not exactly match the real v16 schema, copy the table DDL from `app/schemas/.../16.json` verbatim. The `identity_hash` value does not matter because the migration runs before Room validates.

- [ ] **Step 6: Run the migration test**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.data.MigrationTest.migrate16To17_createsExerciseStrengthOverrideTableAndFlag"`
Expected: PASS. If no emulator, note it and continue; gate it in Task 11.

- [ ] **Step 7: Commit**

```bash
jj describe -m "feat: DB v17 exercise_strength_override table + perExerciseSeedsBackfilled flag

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

## Task 5: One-time seed backfill (muscle overrides → per-exercise rows)

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ExerciseStrengthOverrideBackfill.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/DerivedStateBackfill.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ExerciseStrengthOverrideBackfillTest.kt`

**Interfaces:**
- Consumes: `ExerciseSeedExpansion` (Task 3), `BaselineOverrideDao`, `ExerciseStrengthOverrideDao`, `ExerciseDao`, `UserProfileDao`, `ExerciseCoefficients`.
- Produces: `class ExerciseStrengthOverrideBackfill(db) { suspend fun run() }` — idempotent, gated by `UserProfile.perExerciseSeedsBackfilled`.

Design: keep the logic in the pure `ExerciseSeedExpansion` (already tested). The backfill is a thin DB wrapper. Test the wrapper with an in-memory Room DB on the JVM (Robolectric is not configured; instead unit-test the wrapper against a small fake by extracting the DB calls). To stay on the JVM unit path without Robolectric, test the **decision + expansion** through a pure helper:

```kotlin
// In ExerciseStrengthOverrideBackfill.kt, expose the pure planning step for unit testing.
internal fun planBackfill(
    alreadyDone: Boolean,
    muscleOverrides: List<ExerciseSeedExpansion.MuscleOverrideRow>,
    exercises: List<Exercise>,
): List<ExerciseStrengthOverride> =
    if (alreadyDone) emptyList()
    else ExerciseSeedExpansion.expand(muscleOverrides, exercises, ExerciseCoefficients)
```

- [ ] **Step 1: Write the failing test (pure planning step)**

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.BaselineChangeReason
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.domain.progression.ExerciseSeedExpansion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseStrengthOverrideBackfillTest {

    private fun ex(id: Long, name: String, muscle: MuscleGroup) = Exercise(
        id = id, name = name, primaryMuscle = muscle, secondaryMuscles = emptyList(),
        equipment = Equipment.BARBELL, isDisliked = false, isUnilateral = false, isTimed = false,
    )

    @Test
    fun skipsWhenAlreadyDone() {
        val rows = planBackfill(
            alreadyDone = true,
            muscleOverrides = listOf(
                ExerciseSeedExpansion.MuscleOverrideRow(null, MuscleGroup.CHEST, 80f, 0L, BaselineChangeReason.INITIAL),
            ),
            exercises = listOf(ex(1L, "Barbell Bench Press", MuscleGroup.CHEST)),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun expandsUsingRealSeedCoefficients() {
        // "Barbell Bench Press" seed coef is 1.0 in ExerciseCoefficients.
        val rows = planBackfill(
            alreadyDone = false,
            muscleOverrides = listOf(
                ExerciseSeedExpansion.MuscleOverrideRow(null, MuscleGroup.CHEST, 80f, 0L, BaselineChangeReason.INITIAL),
            ),
            exercises = listOf(ex(1L, "Barbell Bench Press", MuscleGroup.CHEST)),
        )
        assertEquals(1, rows.size)
        assertEquals(80f, rows[0].e1rm, 1e-3f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ExerciseStrengthOverrideBackfillTest"`
Expected: FAIL — `planBackfill` unresolved.

- [ ] **Step 3: Write `ExerciseStrengthOverrideBackfill.kt`**

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseStrengthOverride
import io.github.fowles.stochastic_strength.domain.progression.ExerciseSeedExpansion

/**
 * One-time expansion of the legacy per-muscle baseline_override rows into per-exercise
 * exercise_strength_override rows, using the seed coefficients. Idempotent: gated by the
 * user_profile.perExerciseSeedsBackfilled flag. Runs once at launch from [DerivedStateBackfill].
 */
class ExerciseStrengthOverrideBackfill(private val db: AppDatabase) {

    suspend fun run() {
        val profile = db.userProfileDao().getProfile() ?: return
        if (profile.perExerciseSeedsBackfilled) return

        val muscleOverrides =
            (db.baselineOverrideDao().getInitials() + db.baselineOverrideDao().getNonInitials())
                .map {
                    ExerciseSeedExpansion.MuscleOverrideRow(
                        sessionId = it.sessionId,
                        muscleGroup = it.muscleGroup,
                        baselineWeight = it.baselineWeight,
                        asOf = it.asOf,
                        reason = it.reason,
                    )
                }
        val exercises = db.exerciseDao().getAll()
        val rows = planBackfill(alreadyDone = false, muscleOverrides = muscleOverrides, exercises = exercises)
        for (row in rows) db.exerciseStrengthOverrideDao().insert(row)
        db.userProfileDao().insert(profile.copy(perExerciseSeedsBackfilled = true))
    }
}

internal fun planBackfill(
    alreadyDone: Boolean,
    muscleOverrides: List<ExerciseSeedExpansion.MuscleOverrideRow>,
    exercises: List<Exercise>,
): List<ExerciseStrengthOverride> =
    if (alreadyDone) emptyList()
    else ExerciseSeedExpansion.expand(muscleOverrides, exercises, ExerciseCoefficients)
```
Note: confirm `UserProfileDao.insert` upserts (it does — `OnConflictStrategy.REPLACE` on id=1). If `getAll()` is not the exercise DAO method name, use the actual one (`db.exerciseDao().getAll()` is used elsewhere in the repo).

- [ ] **Step 4: Wire into `DerivedStateBackfill.run()`**

Edit so the override backfill runs before the replay (so the replay sees the new rows):
```kotlin
    suspend fun run() {
        val profile = database.userProfileDao().getProfile() ?: return
        ActualRepsBackfill(database, profile.weightUnit).run()
        ExerciseStrengthOverrideBackfill(database).run()
        repository.replayDerivedState()
    }
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ExerciseStrengthOverrideBackfillTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat: one-time backfill expanding muscle overrides into per-exercise seeds

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

## Task 6: Rewire replay to per-exercise estimates

This is the core integration. After it, the app prescribes from per-exercise estimates while the UI read surface (MuscleGroupStrength + coefficient projections) keeps working.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ReplaySnapshot.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (`replayDerivedState`, `applySessionProgression`, helpers)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt` (remove `progressionControllerFactory`)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ReplayProjectionTest.kt` (pure projection of a synthetic estimate map)

**Interfaces:**
- Consumes: `ExerciseEstimateUpdater`, `MuscleStrengthProjector`, `ExerciseEstimate`, `EstimatorConfig`, `SessionSignalExtractor`, `ExerciseStrengthOverrideDao`.
- Produces: replay that maintains `ReplaySnapshot.currentEstimates: MutableMap<Long, ExerciseEstimate>` and projects `MuscleGroupStrength` + `coefficient_history` rows for display, with prescriptions satisfying `baseline × coef == effectiveE1rm`.

Implementation outline (replace the controller usage):

1. **`ReplaySnapshot`**: add `val currentEstimates: MutableMap<Long, ExerciseEstimate> = mutableMapOf()` and a `muscleExerciseIds: Map<MuscleGroup, List<Long>>` (loaded exercise ids per muscle, derived from `seedCoefficients`). Drop `currentBaselines`/`currentCoefficients` reads from the controller path (keep the fields only if other code still needs them; prefer removing).

2. **Replay init** (`replayDerivedState`):
   - Load per-exercise initials via `db.exerciseStrengthOverrideDao().getInitials()`; for each, `currentEstimates[exerciseId] = ExerciseEstimate.seed(e1rm, at = asOf)`.
   - Group non-initial overrides by `sessionId`.

3. **Per session, in order**:
   - Apply that session's per-exercise override rows first (manual/detrain): for each, set `currentEstimates[exerciseId] = ExerciseEstimate(lnE = ln(e1rm), confidence = confidentThreshold, updatedAt = asOf)` (a user-set value is trusted: seed it at the confident threshold so it is used immediately and still adapts).
   - Then fold the session's sets via the new `applySessionProgression` (below).
   - Then **project** and write display rows for affected muscles.

4. **`applySessionProgression`** becomes:
   ```kotlin
   val updater = ExerciseEstimateUpdater()
   val sets = db.workoutSetDao().getSetsForSession(sessionId)
   // HURT first (muscle-level): for any hurt muscle, hurt every loaded exercise estimate in it.
   val hurtMuscles = sets.filter { it.feedback == SetFeedback.HURT }
       .mapNotNull { snapshot.exerciseMuscle[it.exerciseId] }.toSet()
   for (m in hurtMuscles) for (id in snapshot.muscleExerciseIds[m].orEmpty()) {
       snapshot.currentEstimates[id]?.let { snapshot.currentEstimates[id] = updater.hurt(it, asOf) }
   }
   // Per-exercise fold from the session aggregate.
   sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
       if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
       val agg = SessionSignalExtractor.aggregateSession(exSets) ?: return@forEach
       val prior = snapshot.currentEstimates[id] ?: return@forEach
       snapshot.currentEstimates[id] = updater.fold(prior, agg.est1RM, agg.bracketConfidence, asOf)
   }
   ```

5. **Projection + display writes** (run after each session, and a final pass at end of replay with `now = System.currentTimeMillis()` for freshness): for each muscle, call `MuscleStrengthProjector.project(currentEstimates, seedCoefficients, muscleExerciseIds[m], now)` and:
   - `scratch.upsertMuscleGroupStrength(MuscleGroupStrength(m, projection.level))`.
   - For each exercise, insert a `CoefficientHistory` row with `coefficient = projection.derivedCoef[id]`, `computedAt = now`, `heuristicName = "per-exercise-estimate"`. To keep the history readable, write one row per exercise only when the projected coefficient changed beyond a small epsilon from the previously written one (track last-written per exercise in the snapshot). Also write a `BaselineHistory` row per muscle when `level` changes (reuse the existing `writeBaselineUpdate` shape, `heuristicName = "per-exercise-estimate"`).

6. **`StochasticStrengthApp.kt`**: remove `progressionControllerFactory = { RollingConservingProgressionController() }` and the constructor param (Task 9 deletes the type). For this task, change the factory to a no-op or remove the param if the repository no longer takes it. Simplest: drop the `controller`/`progressionControllerFactory` from `WorkoutRepository`'s constructor and all call sites.

- [ ] **Step 1: Write the failing test (pure projection invariant)**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class ReplayProjectionTest {
    @Test
    fun projectionPreservesPrescriptionIdentity() {
        // baseline (level) * derivedCoef == effectiveE1rm for every exercise -> planner formula holds.
        val estimates = mapOf(
            10L to ExerciseEstimate(ln(100f), confidence = 6f, updatedAt = 0L),
            11L to ExerciseEstimate(ln(58f), confidence = 3f, updatedAt = 0L),
            12L to ExerciseEstimate(ln(40f), confidence = 0f, updatedAt = 0L),
        )
        val seed = mapOf(10L to 1.0f, 11L to 0.6f, 12L to 0.4f)
        val p = MuscleStrengthProjector().project(estimates, seed, listOf(10L, 11L, 12L), now = 0L)
        for (id in listOf(10L, 11L, 12L)) {
            assertEquals(p.effectiveE1rm.getValue(id), p.level * p.derivedCoef.getValue(id), 1e-2f)
        }
    }
}
```

- [ ] **Step 2: Run it to verify it passes already**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ReplayProjectionTest"`
Expected: PASS (this guards the invariant the replay relies on; `MuscleStrengthProjector` already exists).

- [ ] **Step 3: Implement the `ReplaySnapshot` changes**

Add `currentEstimates` and `muscleExerciseIds` (computed in `loadStaticFromDb` from `seedCoefficients`):
```kotlin
    val currentEstimates: MutableMap<Long, ExerciseEstimate> = mutableMapOf()
    val muscleExerciseIds: Map<MuscleGroup, List<Long>> =
        seedCoefficients.filterValues { it > 0f }.keys
            .mapNotNull { id -> exerciseMuscle[id]?.let { it to id } }
            .groupBy({ it.first }, { it.second })
    /** Last derived coefficient written per exercise (epsilon-dedupe for history rows). */
    val lastWrittenCoef: MutableMap<Long, Float> = mutableMapOf()
```

- [ ] **Step 4: Rewrite `applySessionProgression` and the projection in `WorkoutRepository`**

Replace the controller `step` call and the baseline/coefficient bookkeeping with the per-exercise fold + projection described in the outline. Remove `controller`/`progressionControllerFactory` from the repository and `StochasticStrengthApp`. Keep `writeBaselineUpdate`'s `BaselineHistory` insertion (used by the baseline chart) but feed it the projected `level`. Write `CoefficientHistory` rows from `projection.derivedCoef` with epsilon-dedupe via `snapshot.lastWrittenCoef`.

- [ ] **Step 5: Build and run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: compiles; pre-existing progression tests may now fail (they reference the deleted controller) — that is expected and handled in Tasks 9–10. Confirm the NEW tests (Tasks 1–3, 5, 6) pass. If other modules fail to compile due to the removed controller param, fix call sites in this task.

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat: replay maintains per-exercise estimates and projects display views

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

## Task 7: Per-exercise initial seeding (`seedInitialWeights` + `StartingWeights`)

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/StartingWeights.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (`seedInitialWeights`)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/StartingWeightsTest.kt`

**Interfaces:**
- Produces:
  - `StartingWeights.exerciseSeedE1rm(sex, level, exercise): Float?` (curated; null today).
  - `StartingWeights.seedInitialE1rm(sex, level, exercise): Float` = `exerciseSeedE1rm(...) ?: muscleReference × seedCoef`.
- `seedInitialWeights` writes per-exercise `ExerciseStrengthOverride` initials and sets `perExerciseSeedsBackfilled = true` (a new user has no legacy muscle rows to expand).

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.Sex
import io.github.fowles.stochastic_strength.data.model.StrengthLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class StartingWeightsTest {
    private fun ex(name: String, muscle: MuscleGroup) = Exercise(
        id = 1, name = name, primaryMuscle = muscle, secondaryMuscles = emptyList(),
        equipment = Equipment.BARBELL, isDisliked = false, isUnilateral = false, isTimed = false,
    )

    @Test
    fun fallsBackToMuscleReferenceTimesSeedCoef() {
        // No curated entry -> muscleReference(MALE, MEDIUM, CHEST)=80 * seedCoef("Barbell Bench Press")=1.0
        val e1rm = StartingWeights.seedInitialE1rm(Sex.MALE, StrengthLevel.MEDIUM, ex("Barbell Bench Press", MuscleGroup.CHEST))
        assertEquals(80f, e1rm, 1e-3f)
    }

    @Test
    fun fallbackScalesByCoefForAccessory() {
        // "Dumbbell Bench Press" seed coef is 0.40 -> 80 * 0.40 = 32.
        val e1rm = StartingWeights.seedInitialE1rm(Sex.MALE, StrengthLevel.MEDIUM, ex("Dumbbell Bench Press", MuscleGroup.CHEST))
        assertEquals(32f, e1rm, 1e-3f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.StartingWeightsTest"`
Expected: FAIL — `seedInitialE1rm` unresolved.

- [ ] **Step 3: Extend `StartingWeights.kt`**

Keep `baseline(...)` (the muscle reference, now also the fallback). Add:
```kotlin
    /** Curated per-exercise starting 1RM; null falls back to muscle reference × seed coefficient. */
    fun exerciseSeedE1rm(sex: Sex, level: StrengthLevel, exercise: Exercise): Float? = null

    /** Per-exercise initial estimated 1RM for a new user. */
    fun seedInitialE1rm(sex: Sex, level: StrengthLevel, exercise: Exercise): Float {
        exerciseSeedE1rm(sex, level, exercise)?.let { return it }
        val ref = baseline(sex, level, exercise.primaryMuscle)
        val coef = ExerciseCoefficients.get(exercise) ?: 0f
        return ref * coef
    }
```
Import `Exercise` and `ExerciseCoefficients`.

- [ ] **Step 4: Rewrite `seedInitialWeights` in `WorkoutRepository`**

```kotlin
    suspend fun seedInitialWeights(sex: Sex, strengthLevel: StrengthLevel, weightUnit: WeightUnit) {
        db.userProfileDao().insert(
            UserProfile(sex = sex, strengthLevel = strengthLevel, weightUnit = weightUnit, perExerciseSeedsBackfilled = true)
        )
        val exercises = db.exerciseDao().getAll()
        for (ex in exercises) {
            val e1rm = StartingWeights.seedInitialE1rm(sex, strengthLevel, ex)
            if (e1rm > 0f) {
                db.exerciseStrengthOverrideDao().deleteInitialFor(ex.id)
                db.exerciseStrengthOverrideDao().insert(
                    ExerciseStrengthOverride(sessionId = null, exerciseId = ex.id, e1rm = e1rm, asOf = 0L)
                )
            }
        }
        replayDerivedState()
    }
```
Remove the old per-muscle `baselineOverrideDao` seeding loop. Import `ExerciseStrengthOverride`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.StartingWeightsTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat: per-exercise initial seeding mechanism with muscle-ref fallback

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

## Task 8: Per-exercise manual edits and detraining

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/model/WorkoutPlan.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutPlanner.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt` (`buildPlanner`, `applyManualBaselineOverrides`, `applyDetrainingReduction`)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/workout/WorkoutSessionController.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutPlannerOverrideTest.kt`

**Interfaces:**
- `WorkoutPlan.exerciseOverrides: Map<Long, Float>` (per-exercise e1rm) replaces `strengthOverrides`/`detrainOverrides` maps; `effectiveOverrides: Map<Long, Float>`.
- `WorkoutPlanner` gains `exerciseE1rmOverrides: Map<Long, Float>` (constructor param); `weightForExercise` uses an override when present.
- `WorkoutPlanner.e1rmFromSessionWeight(weight, reps): Float = progressionEngine.toOneRepMax(weight, reps)` replaces `deriveBaselineFromSessionWeight`.
- `WorkoutPlanner.recomputeExercise(pe, newE1rmKg)` (takes an e1rm, applies only to that exercise).
- `buildPlanner(locationId, weightUnit, exerciseOverrides: Map<Long, Float> = emptyMap())`.
- `applyManualBaselineOverrides(sessionId, overrides: Map<Long, Float>)` and `applyDetrainingReduction(sessionId, overrides: Map<Long, Float>)` write `ExerciseStrengthOverride` rows.

Key semantic change: editing one exercise's weight sets only that exercise's e1rm. `adjustExerciseWeight` no longer recomputes sibling weights. Detraining still applies uniformly, but now per exercise: it scales each loaded exercise's *current prescribed e1rm* by the factor.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.MuscleGroupStrength
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WorkoutPlannerOverrideTest {

    private fun ex(id: Long, name: String) = Exercise(
        id = id, name = name, primaryMuscle = MuscleGroup.CHEST, secondaryMuscles = emptyList(),
        equipment = Equipment.BARBELL, isDisliked = false, isUnilateral = false, isTimed = false,
    )

    private fun planner(overrides: Map<Long, Float>) = WorkoutPlanner(
        availableExercises = listOf(ex(1, "Barbell Bench Press"), ex(2, "Incline Barbell Bench Press")),
        strengths = mapOf(MuscleGroup.CHEST to MuscleGroupStrength(MuscleGroup.CHEST, 80f)),
        recentHistory = emptyMap(),
        weightUnit = WeightUnit.KG,
        locationId = null,
        random = Random(1),
        exerciseE1rmOverrides = overrides,
    )

    @Test
    fun exerciseOverrideAffectsOnlyThatExercise() {
        val base = planner(emptyMap())
        val pe1 = base.generateWorkout(5).exercises // sanity that it builds
        // Override exercise 1's e1rm to 120; exercise 2 must be unchanged vs no-override planner.
        val overridden = planner(mapOf(1L to 120f))
        val w1NoOverride = base.weightForExerciseTest(ex(1, "Barbell Bench Press"), 5)
        val w1Override = overridden.weightForExerciseTest(ex(1, "Barbell Bench Press"), 5)
        val w2NoOverride = base.weightForExerciseTest(ex(2, "Incline Barbell Bench Press"), 5)
        val w2Override = overridden.weightForExerciseTest(ex(2, "Incline Barbell Bench Press"), 5)
        assertTrue("override raises ex1", w1Override > w1NoOverride)
        assertEquals("ex2 untouched by ex1 override", w2NoOverride, w2Override, 1e-3f)
    }
}
```
Note: add an `internal fun weightForExerciseTest(exercise, reps) = weightForExercise(exercise, reps)` shim in `WorkoutPlanner` (or make `weightForExercise` internal) so the test can call it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerOverrideTest"`
Expected: FAIL — `exerciseE1rmOverrides` param unresolved.

- [ ] **Step 3: Update `WorkoutPlanner`**

- Add constructor param `private val exerciseE1rmOverrides: Map<Long, Float> = emptyMap()`.
- In `weightForExercise`:
```kotlin
    private fun weightForExercise(exercise: Exercise, sessionReps: Int): Float {
        val coeff = coefficientSource.get(exercise) ?: return 0f
        if (coeff <= 0f) return 0f
        val e1rm = exerciseE1rmOverrides[exercise.id]
            ?: ((strengths[exercise.primaryMuscle]?.baselineWeight ?: return 0f) * coeff)
        if (e1rm <= 0f) return 0f
        return WeightFormatter.round(progressionEngine.fromOneRepMax(e1rm, sessionReps), weightUnit)
    }
    internal fun weightForExerciseTest(exercise: Exercise, sessionReps: Int) = weightForExercise(exercise, sessionReps)
```
- Replace `deriveBaselineFromSessionWeight` with:
```kotlin
    fun e1rmFromSessionWeight(sessionWeight: Float, sessionReps: Int): Float =
        progressionEngine.toOneRepMax(sessionWeight, sessionReps)
```
- Change `recomputeExercise(pe, newBaselineKg)` to `recomputeExercise(pe, newE1rmKg)` and use `newE1rmKg` directly in `fromOneRepMax(newE1rmKg, pe.sessionReps)` (drop the `× coeff`).

- [ ] **Step 4: Update `WorkoutPlan` model**

```kotlin
data class WorkoutPlan(
    val exercises: List<PlannedExercise>,
    val locationId: Long?,
    val sessionReps: Int = 10,
    val sessionRejectedIds: Set<Long> = emptySet(),
    val exerciseOverrides: Map<Long, Float> = emptyMap(),     // per-exercise e1rm (manual edits)
    val detrainOverrides: Map<Long, Float> = emptyMap(),      // per-exercise e1rm (detraining)
) {
    val estimatedDurationSeconds: Int get() = exercises.sumOf { it.estimatedSeconds }
    /** Per-exercise e1rm feeding the planner: detraining first, manual edits override it. */
    val effectiveOverrides: Map<Long, Float> get() = detrainOverrides + exerciseOverrides
}
```

- [ ] **Step 5: Update `buildPlanner` and override writers in `WorkoutRepository`**

- `buildPlanner(locationId, weightUnit, exerciseOverrides: Map<Long, Float> = emptyMap())`: pass `exerciseE1rmOverrides = exerciseOverrides` into `WorkoutPlanner`; drop the muscle `strengths` override-merge (still pass `strengths = dbStrengths`).
- `applyManualBaselineOverrides(sessionId, overrides: Map<Long, Float>)`:
```kotlin
        for ((exerciseId, e1rm) in overrides) {
            db.exerciseStrengthOverrideDao().insert(
                ExerciseStrengthOverride(sessionId = sessionId, exerciseId = exerciseId, e1rm = e1rm, asOf = asOf, reason = BaselineChangeReason.OVERRIDE)
            )
        }
        replayDerivedState()
```
- `applyDetrainingReduction(sessionId, overrides: Map<Long, Float>)`: same shape with `reason = BaselineChangeReason.DETRAIN`.
(Use the existing `asOf`/session-time source these functions already use.)

- [ ] **Step 6: Update `WorkoutSessionController`**

- `adjustExerciseWeight`: compute `newE1rm = p.e1rmFromSessionWeight(newWeight, pe.sessionReps)`; set only `exercises[index]` weight + warmups; **remove the sibling recompute loop**; `updatedOverrides = state.plan.exerciseOverrides + (pe.exercise.id to newE1rm)`; rebuild planner with `state.plan.detrainOverrides + updatedOverrides`.
- `applyDetraining(fraction)`: build `detrainOverrides: Map<Long, Float>` by scaling each loaded exercise's *current prescribed e1rm*. Compute current e1rm per exercise from its prescribed `sessionWeight`: `val cur = p.e1rmFromSessionWeight(ex.sessionWeight, ex.sessionReps); detrainOverrides[ex.exercise.id] = DetrainingModel.reduce(cur, fraction)`. Recompute each exercise via `p.recomputeExercise(ex, detrainOverrides[ex.exercise.id]!!)`.
- `startFirstExercise`: `applyDetrainingReduction(sessionId, plan.detrainOverrides)` and `applyManualBaselineOverrides(sessionId, plan.exerciseOverrides)` (both now `Map<Long, Float>`).
- Fix all `strengthOverrides` references to `exerciseOverrides`, and `effectiveOverrides` usages now key on exercise id.

- [ ] **Step 7: Run tests + build**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutPlannerOverrideTest"` then `./gradlew :app:assembleDebug`
Expected: PASS + app compiles.

- [ ] **Step 8: Commit**

```bash
jj describe -m "feat: per-exercise manual edits and detraining (overrides no longer leak to siblings)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

## Task 9: Delete the gauge-conserving controller stack

**Files:**
- Delete: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt`
- Delete: `app/src/main/java/io/github/fowles/stochastic_strength/domain/RobustCenter.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/SessionSignalExtractor.kt` (drop only the now-unused `BRACKET_CONFIDENCE` snap *consumers*; keep `bracketConfidence` output)
- Modify: any remaining references (`StochasticStrengthApp.kt`, `ReplaySnapshot.kt` `seedCoefficients` reclaim-only plumbing if now unused)

- [ ] **Step 1: Delete the files**

```bash
rm app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt
rm app/src/main/java/io/github/fowles/stochastic_strength/domain/RobustCenter.kt
```

- [ ] **Step 2: Remove dangling references**

Search and fix:
```bash
grep -rn "ProgressionController\|RollingConserving\|RobustCenter\|ProgressionStepInput\|ProgressionStepOutput\|ProgressionObservation\|BaselineUpdate\|CoefficientUpdate\|progressionControllerFactory" app/src/main
```
Remove each. `SessionSignalExtractor` keeps `bracketConfidence` and `BRACKET_CONFIDENCE` (the updater consumes it); only delete references tied to the controller's `snap` config.

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: compiles with no references to deleted types.

- [ ] **Step 4: Commit**

```bash
jj describe -m "refactor: delete RollingConservingProgressionController + RobustCenter

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

## Task 10: Rewrite the simulation + characterization tests

**Files:**
- Delete: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerTest.kt`
- Replace: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerSimulationTest.kt` → `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimatorSimulationTest.kt`
- Replace: `app/src/test/java/io/github/fowles/stochastic_strength/domain/BulgarianBracketCharacterizationTest.kt` → adapt to drive the estimator directly.

**Interfaces:**
- Consumes: `ExerciseEstimateUpdater`, `MuscleStrengthProjector`, `SessionSignalExtractor`, real `ExerciseLibrary`, `WorkoutGenerator`, `RepRangePicker`.

The new simulation reuses the existing synthetic-lifter harness (cross-set fatigue `fatiguePerSet = 0.03`, real library with bands removed, `WorkoutGenerator` selection, lognormal-perturbed true coefficients, optional `growthPerSession`). Replace the `controller.step(...)` drive with: per session, fold each exercise's sets into a per-exercise `ExerciseEstimate` map via `ExerciseEstimateUpdater`; prescribe via `MuscleStrengthProjector.project(...).effectiveE1rm[id]` → `fromOneRepMax(..., reps)`.

- [ ] **Step 1: Write the new simulation test**

Port the harness from the existing file (lines 100–270 of the old test) with these changes:
- State: `val estimates = mutableMapOf<Long, ExerciseEstimate>()` seeded per loaded exercise from `seedBaselineFactor × trueBaseline_muscle × seedCoef` at `t=0`.
- Prescription for exercise `id` this session: `val proj = projector.project(estimates, seedCoef, muscleExercises.getValue(muscle), now = t); val e1rm = proj.effectiveE1rm.getValue(id); val w0 = WeightFormatter.round(fromOneRepMax(e1rm, reps), unit)`.
- Update after the session: `SessionSignalExtractor.aggregateSession(sets)?.let { estimates[id] = updater.fold(estimates.getValue(id), it.est1RM, it.bracketConfidence, t) }`.
- Error metric: compare `proj.effectiveE1rm[id]` to `trueBaseline_muscle × gMul × trueCoef_id × steadyFactor`.

Keep these asserts (same thresholds as the old test):
```kotlin
@Test fun gains_settle_last_set_near_rir01() {
    // rir in 0.0f..1.5f ; failRate <= 0.20f ; convSessions <= 12 ; jitter <= 1.5f
    // static run: finite metrics + trainedEndErr <= 8.0
}
```
Replace the gauge assert with a tracking assert:
```kotlin
@Test fun muscle_aggregate_tracks_truth_under_growth() {
    // For growth in {0.0, 0.002, 0.004}: tail mean prescribed error over well-trained exercises <= 8%.
}
```
Add the two goal asserts:
```kotlin
@Test fun cold_exercise_with_trained_siblings_is_prescribed_near_truth() {
    // One muscle, 3 lifts; train 2 to convergence, leave 1 untrained.
    // The untrained lift's projected effectiveE1rm is within 12% of its true capacity.
}

@Test fun failure_drops_next_prescription_below_failed_weight() {
    // Fold a clear failure (est1RM below current, bracketConfidence 0.95) into one exercise.
    // Next projected prescription weight < the failed weight.
}
```

- [ ] **Step 2: Run the new simulation test**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.ExerciseEstimatorSimulationTest"`
Expected: PASS. If a behavioral assert fails, tune `EstimatorConfig` (`wUp`, `wDown`, `priorStrength`, `confidenceCap`) — the constants are the tuning surface; the asserts encode the validated behavior and must not be loosened without a documented decision.

- [ ] **Step 3: Adapt the Bulgarian-bracket characterization**

Rewrite to assert the estimator behavior: given a drop-cascade session (top-set failure + completed lighter set), `SessionSignalExtractor.aggregateSession` yields `bracketConfidence > 0` and an `est1RM` below the failed top weight; folding it drops the exercise's estimate so the next prescription is below the failed weight. Delete `ProgressionControllerTest.kt`.

- [ ] **Step 4: Run the full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all unit tests green).

- [ ] **Step 5: Commit**

```bash
jj describe -m "test: rewrite simulation + characterization around per-exercise estimator

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

## Task 11: Read-site sweep, full build, and full verification

**Files:**
- Audit/modify: `ui/components/StrengthGrid.kt`, `ui/debug/DebugStatsViewModel.kt`, `ui/history/HistoryViewModel.kt`, `ui/workout/DetrainingDialog.kt`, `ui/workout/WorkoutState.kt`, and any other consumer surfaced by grep.

- [ ] **Step 1: Sweep for stale per-muscle override / coefficient assumptions**

```bash
grep -rn "strengthOverrides\|deriveBaselineFromSessionWeight\|baselineOverrideDao\|Map<MuscleGroup, Float>" app/src/main
```
For each hit: `strengthOverrides` → `exerciseOverrides`; muscle-keyed override maps in UI → exercise-keyed. `DetrainingDialog`/`WorkoutState` show per-muscle current strengths from `MuscleGroupStrength` — those still exist (projected `level`), so display is unaffected; only the override-write path changed. Confirm each compiles and the displayed strengths still read `derivedState` projections.

- [ ] **Step 2: Confirm coefficient/baseline history consumers still populate**

`DebugStatsViewModel` / `getAllCoefficientRows` / baseline + coefficient history screens read `coefficient_history` and `baseline_history`, which Task 6 still writes (now tagged `heuristicName = "per-exercise-estimate"`). Build the app and spot-check these screens are non-empty after a seeded run (manual check noted; not automated here).

- [ ] **Step 3: Full build + full unit suite**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: both succeed; all unit tests green.

- [ ] **Step 4: Instrumented migration tests**

Run: `./gradlew :app:connectedAndroidTest --tests "io.github.fowles.stochastic_strength.data.MigrationTest"`
Expected: PASS (including `migrate16To17_...`). If no device is available, state that explicitly in the final report.

- [ ] **Step 5: Lint**

Run: `./gradlew :app:lint`
Expected: no new errors introduced by these changes.

- [ ] **Step 6: Commit**

```bash
jj describe -m "chore: read-site sweep + full verification for per-exercise progression

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
jj new
```

---

## Self-Review Notes (for the implementer)

- **Spec coverage:** per-exercise estimate (T1), read-time pooling/Goal 2 (T2, T10), asymmetric down-tracking/Goal 3 (T1, T10), per-exercise persisted overrides + migration (T3–T5, T8), per-exercise seeding mechanism + fallback (T7), deletions (T9), test rewrite incl. kept/added asserts (T10), read-site audit (T11).
- **Deviation:** prescription via replay projection rather than a live `SiblingPredictor` call in the planner (documented in the header). The pure pooling logic is identical; only the call site differs. If the reviewer prefers the spec's live-planner wiring, Task 6's projection step is replaced by threading `currentEstimates` into `buildPlanner`/`WorkoutPlanner` and calling `MuscleStrengthProjector` at plan time.
- **Curated seeds are out of scope** (mechanism + fallback only): `StartingWeights.exerciseSeedE1rm` returns null today.
- **Tuning surface:** `EstimatorConfig` only. Behavioral asserts in T10 are the lock; tune constants, never loosen asserts without a documented decision.
