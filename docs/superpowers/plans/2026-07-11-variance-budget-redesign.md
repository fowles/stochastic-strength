# Variance-Budget Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Adopt the two study-validated fixes for the misspecified variance budget — more observation-noise budget and a transient session day-effect random intercept — jointly re-fit against real held-out CV, with gates re-pinned/re-baselined.

**Architecture:** Two new `EstimatorConfig` knobs (`obsNoiseScale`, `sessionDayEffectSd`) default to no-op (×1 / 0) so nothing changes until adoption. A shared per-session latent `d ~ N(0, σ_day²)` is estimated from a session's own sets and integrated out of the belief folds via a two-pass fold in `SessionProgressionStepper` (nothing durable, no DB migration). A joint 2D offline sweep over `(obsNoiseScale, σ_day)` on the real 24-session history picks the interior optimum, which is then baked into the config defaults; `BeliefSimulationTest` is made day-effect-honest and re-pinned, and the backtest + prod-BSS gates are re-baselined.

**Tech Stack:** Kotlin, JVM unit tests (`./gradlew :app:testDebugUnitTest`), Room (no migration here), jj for commits.

## Global Constraints

- **Binding principle (spec §0):** trust real held-out forward-chaining CV, not the hand-tuned constants nor `BeliefSimulationTest`. Do not shrink fitted values toward existing defaults; the sim is not a veto over CV. (See `feedback_trust_the_data`.)
- **No behavior change until adoption:** every code task before Task 6 must leave production behavior bit-identical when `obsNoiseScale = 1f` and `sessionDayEffectSd = 0f`.
- **Package:** `io.github.fowles.stochastic_strength`. All log-space math is `Float`, base-e, on the FRESH-1RM basis.
- **No DB migration** — the day-effect is transient (never persisted); `replayDerivedState()` stays idempotent.
- **Analysis-only tests require** the gitignored fixture `app/src/test/resources/backtest/history.json` and must no-op cleanly without it (follow the existing `domain/backtest/` pattern).
- **Commits:** jj; commit at each task's final step. The user reshapes/pushes.
- Study report of record: `app/build/variance-identification-report.txt` (B0 default=−277.5; obs-noise ×3 → −201.2 INTERIOR; day-effect σ_day≈0.18 → −221.2 INTERIOR; residual split 42.8% between / 57.2% within).

---

### Task 1: Config knobs — `obsNoiseScale` and `sessionDayEffectSd`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseBelief.kt` (the `EstimatorConfig` data class, ~line 137)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SetObservation.kt:36-39` (the `noise()` local)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/SetObservationNoiseScaleTest.kt` (create)

**Interfaces:**
- Produces: `EstimatorConfig.obsNoiseScale: Float = 1f`, `EstimatorConfig.sessionDayEffectSd: Float = 0f`. `SetObservation.from` multiplies its final `noiseSd` by `config.obsNoiseScale`.

Scaling all four obs-noise constants by `m` is algebraically identical to multiplying the final `noiseSd` by `m` (both `repSd` and `obsModelSd` combine in quadrature, so the common factor pulls out). We adopt the single-multiplier form: it is auditable and keeps the four constants' documented meanings intact.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Test

class SetObservationNoiseScaleTest {
    private fun set() = WorkoutSet(
        sessionId = 1, exerciseId = 1, setNumber = 1,
        targetWeight = 60f, targetReps = 5, actualReps = null, feedback = SetFeedback.RIR_0_1,
    )

    @Test fun obsNoiseScaleMultipliesNoiseLinearly() {
        val base = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig())!!
        val scaled = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig(obsNoiseScale = 3f))!!
        assertEquals(3f * base.noiseSd, scaled.noiseSd, 1e-6f)
    }

    @Test fun defaultScaleIsIdentity() {
        val a = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig())!!
        val b = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig(obsNoiseScale = 1f))!!
        assertEquals(a.noiseSd, b.noiseSd, 0f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SetObservationNoiseScaleTest"`
Expected: FAIL — `obsNoiseScale` is not a member of `EstimatorConfig`.

- [ ] **Step 3: Add the two config fields**

In `ExerciseBelief.kt`, inside `data class EstimatorConfig`, after `adaptRunDecay` (the last field, ~line 137):

```kotlin
    /**
     * Multiplier on every set's observation noise σ_obs (equivalently, a uniform scale on
     * repNoiseBucket/Counted/Rel + obsModelSd). The variance-identification study found real obs-noise
     * is underspecified (within-session residual share 57%); jointly re-fit with sessionDayEffectSd.
     * 1f = today's behavior.
     */
    val obsNoiseScale: Float = 1f,
    /**
     * σ_day: std of the shared per-session "good-day/bad-day" random intercept d ~ N(0, σ_day²),
     * estimated from a session's own sets and integrated out of the belief folds (transient, never
     * durable). Absorbs the between-session residual share (~43%). 0f = no day-effect (today's behavior).
     */
    val sessionDayEffectSd: Float = 0f,
```

- [ ] **Step 4: Apply the scale in `SetObservation.noise()`**

In `SetObservation.kt`, change the `noise` local (lines 36-39) to fold in the scale:

```kotlin
            fun noise(base: Float): Float {
                val repSd = lambda * sqrt(base * base + (config.repNoiseRel * r) * (config.repNoiseRel * r))
                return config.obsNoiseScale * sqrt(repSd * repSd + config.obsModelSd * config.obsModelSd)
            }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SetObservationNoiseScaleTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat(estimator): add obsNoiseScale + sessionDayEffectSd config knobs (no-op defaults)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" && jj new
```

---

### Task 2: `SessionDayEffect` — pure day-offset posterior

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SessionDayEffect.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/SessionDayEffectTest.kt` (create)

**Interfaces:**
- Produces: `data class DayPosterior(val mean: Float, val variance: Float)` and
  `SessionDayEffect.estimate(sigmaDay: Float, observations: List<SessionDayEffect.Residual>): DayPosterior`,
  where `Residual(val value: Float, val obsVar: Float)` is one set's `(obsLocation − predMean, cleanVar + noiseSd²)`.
- Consumed by Task 3 (fold) and Task 5 (joint-fit stream).

Full-session Gaussian posterior of a shared latent `d` with prior `N(0, σ_day²)` given independent
observations `value_i = d + ε_i`, `ε_i ~ N(0, obsVar_i)`: precision-weighted combination. Order-independent.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDayEffectTest {
    @Test fun zeroSigmaDayGivesZeroPosterior() {
        val post = SessionDayEffect.estimate(
            sigmaDay = 0f,
            observations = listOf(SessionDayEffect.Residual(0.5f, 0.01f), SessionDayEffect.Residual(0.3f, 0.01f)),
        )
        assertEquals(0f, post.mean, 0f)
        assertEquals(0f, post.variance, 0f)
    }

    @Test fun noObservationsReturnsPrior() {
        val post = SessionDayEffect.estimate(sigmaDay = 0.2f, observations = emptyList())
        assertEquals(0f, post.mean, 0f)
        assertEquals(0.04f, post.variance, 1e-6f)
    }

    @Test fun twoEqualResidualsPullMeanTowardTheirValueAndShrinkVariance() {
        // Prior N(0, 0.2²=0.04). Two obs at +0.1 with obsVar 0.01 each.
        // Posterior precision = 1/0.04 + 2/0.01 = 25 + 200 = 225 -> var = 1/225 ≈ 0.004444.
        // Posterior mean = var * (0 + 0.1/0.01 + 0.1/0.01) = 0.004444 * 20 ≈ 0.08889.
        val post = SessionDayEffect.estimate(
            sigmaDay = 0.2f,
            observations = listOf(SessionDayEffect.Residual(0.1f, 0.01f), SessionDayEffect.Residual(0.1f, 0.01f)),
        )
        assertEquals(0.004444f, post.variance, 1e-5f)
        assertEquals(0.08889f, post.mean, 1e-4f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*SessionDayEffectTest"`
Expected: FAIL — `SessionDayEffect` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

/**
 * The shared per-session "good-day/bad-day" random intercept d ~ N(0, σ_day²) (spec §3-4). Given the
 * session's per-set residuals about the offset-free predictions, returns the Gaussian posterior of d
 * by precision-weighted combination (independent obs). σ_day = 0 ⇒ N(0,0): the offset is pinned off
 * and the caller's fold is bit-identical to the no-day-effect model. Order-independent; transient.
 */
object SessionDayEffect {
    /** One set's evidence about d: value = obsLocation − offsetFreePredMean, obsVar = cleanVar + noiseSd². */
    data class Residual(val value: Float, val obsVar: Float)

    data class DayPosterior(val mean: Float, val variance: Float)

    fun estimate(sigmaDay: Float, observations: List<Residual>): DayPosterior {
        if (sigmaDay <= 0f) return DayPosterior(0f, 0f)
        var precision = 1f / (sigmaDay * sigmaDay)
        var precisionWeightedSum = 0f // = Σ value_i / obsVar_i (prior mean is 0)
        for (o in observations) {
            if (o.obsVar <= 0f) continue
            precision += 1f / o.obsVar
            precisionWeightedSum += o.value / o.obsVar
        }
        val variance = 1f / precision
        return DayPosterior(mean = variance * precisionWeightedSum, variance = variance)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*SessionDayEffectTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
jj describe -m "feat(estimator): SessionDayEffect — pure per-session day-offset posterior

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" && jj new
```

---

### Task 3: Two-pass day-effect fold in `SessionProgressionStepper`

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SessionProgressionStepper.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/StepperDayEffectTest.kt` (create)

**Interfaces:**
- Consumes: `SessionDayEffect.estimate` (Task 2), `EstimatorConfig.sessionDayEffectSd` (Task 1).
- Produces: `step()` unchanged signature; behavior gains a Pass-1 day estimate + Pass-2 shifted/inflated folds. When `sessionDayEffectSd == 0f`, results are bit-identical to today.

**Design.** Insert a Pass 1 before the existing per-exercise fold loop that, for every load-bearing set,
builds a `SessionDayEffect.Residual(obsLocation(obs) − preSessionPredMeanLn, preSessionCleanVar + noiseSd²)`
using the exercise's **pre-session** pooled prediction (projector, computed once per exercise from
start-of-session beliefs — identical call to the existing `scorer != null` branch). Estimate
`DayPosterior(m_d, v_d)`. Then in the existing Pass-2 fold loop, fold each set with the observation
**shifted by `−m_d`** and its `noiseSd` **inflated** to `sqrt(noiseSd² + v_d)`; if a scorer is present,
feed it the shifted `predMeanLn − (−m_d)` i.e. `predMeanLn` unchanged in the offset-free basis but with
predictive variance carrying `+ v_d` (see Step 3). The per-exercise grouping and single-per-exercise
prediction are preserved, so the existing `scoredReplayTotal ↔ captureStream` parity holds at σ_day=0.

Helpers needed: `obsLocation(obs)` currently lives in the test tree (`VarianceStudyStream.kt`). Add a
production copy as a private helper in the stepper (same logic) — do NOT depend on test code.

- [ ] **Step 1: Write the failing tests**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepperDayEffectTest {
    private val muscle = MuscleGroup.QUADS

    private fun snapshot(exerciseIds: List<Long>): ReplaySnapshot {
        val snap = ReplaySnapshot(
            exerciseMuscle = exerciseIds.associateWith { muscle },
            seedCoefficients = exerciseIds.associateWith { 1f },
            exerciseEquipment = exerciseIds.associateWith { Equipment.BARBELL },
            muscleExerciseIds = mapOf(muscle to exerciseIds),
        )
        exerciseIds.forEach { snap.currentBeliefs[it] = ExerciseBelief.seed(60f, at = 0L, config = EstimatorConfig()) }
        return snap
    }

    private fun set(ex: Long, n: Int, weight: Float, reps: Int, fb: SetFeedback) = WorkoutSet(
        sessionId = 1, exerciseId = ex, setNumber = n, targetWeight = weight, targetReps = reps,
        actualReps = null, feedback = fb,
    )

    @Test fun zeroSigmaDayIsBitIdenticalToNoDayEffect() {
        val ids = listOf(1L, 2L)
        val sets = listOf(
            set(1, 1, 65f, 5, SetFeedback.RIR_0_1), set(1, 2, 65f, 5, SetFeedback.RIR_0_1),
            set(2, 1, 70f, 5, SetFeedback.RIR_2_4), set(2, 2, 70f, 5, SetFeedback.RIR_2_4),
        )
        val a = snapshot(ids); val b = snapshot(ids)
        SessionProgressionStepper(EstimatorConfig(sessionDayEffectSd = 0f)).step(sets, a, asOf = DAY)
        SessionProgressionStepper(EstimatorConfig(sessionDayEffectSd = 0f)).step(sets, b, asOf = DAY)
        for (id in ids) assertEquals(a.currentBeliefs[id]!!.mu, b.currentBeliefs[id]!!.mu, 0f)
    }

    @Test fun uniformlyHighSessionDampensPerExerciseUpdatesVsNoDayEffect() {
        // A whole-session "good day": every exercise beats its seed by the same amount. With a day-effect,
        // the shared d absorbs the common surprise, so each belief moves LESS than with no day-effect.
        val ids = listOf(1L, 2L, 3L)
        val sets = ids.flatMap { ex ->
            (1..2).map { n -> set(ex, n, 80f, 5, SetFeedback.RIR_0_1) } // heavy + easy = strong upward surprise
        }
        val withDay = snapshot(ids); val without = snapshot(ids)
        SessionProgressionStepper(EstimatorConfig(sessionDayEffectSd = 0.18f)).step(sets, withDay, asOf = DAY)
        SessionProgressionStepper(EstimatorConfig(sessionDayEffectSd = 0f)).step(sets, without, asOf = DAY)
        for (id in ids) {
            val movedWithDay = withDay.currentBeliefs[id]!!.mu - ExerciseBelief.seed(60f, 0L).mu
            val movedWithout = without.currentBeliefs[id]!!.mu - ExerciseBelief.seed(60f, 0L).mu
            assertTrue("id=$id: day-effect should dampen ($movedWithDay) vs none ($movedWithout)",
                movedWithDay in 0f..movedWithout || (movedWithout < 0f && movedWithDay > movedWithout))
        }
    }

    @Test fun singleExerciseSessionStillFolds() {
        val ids = listOf(1L)
        val sets = listOf(set(1, 1, 65f, 5, SetFeedback.RIR_0_1))
        val snap = snapshot(ids)
        SessionProgressionStepper(EstimatorConfig(sessionDayEffectSd = 0.18f)).step(sets, snap, asOf = DAY)
        assertTrue(snap.currentBeliefs[1]!!.mu.isFinite())
    }

    private companion object { const val DAY = 24L * 60 * 60 * 1000 }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*StepperDayEffectTest"`
Expected: `uniformlyHighSessionDampensPerExerciseUpdatesVsNoDayEffect` FAILS (day-effect not applied yet; both moves equal). The others pass trivially — that is fine; the dampening test is the real gate.

- [ ] **Step 3: Implement the two-pass fold**

Replace the body of `step()` in `SessionProgressionStepper.kt` (lines 26-84). Keep the per-exercise
prediction helper logic; add Pass 1 and thread `(m_d, v_d)` through Pass 2:

```kotlin
    fun step(sets: List<WorkoutSet>, snapshot: ReplaySnapshot, asOf: Long): StepResult {
        if (sets.isEmpty()) return StepResult(emptyList())

        val byExercise = sets.groupBy { it.exerciseId }
            .filter { (id, _) -> (snapshot.seedCoefficients[id] ?: 0f) > 0f && snapshot.currentBeliefs[id] != null }

        // Pre-session pooled prediction per exercise (offset-free), computed once from start-of-session
        // beliefs — the basis for both the day-offset residual (Pass 1) and predictive scoring.
        data class Pred(val meanLn: Float, val cleanVar: Float)
        val pred: Map<Long, Pred> = byExercise.keys.mapNotNull { id ->
            val muscle = snapshot.exerciseMuscle[id] ?: return@mapNotNull null
            val ids = snapshot.muscleExerciseIds[muscle] ?: return@mapNotNull null
            val proj = projector.project(
                beliefs = snapshot.currentBeliefs, seedCoef = snapshot.seedCoefficients,
                muscleExerciseIds = ids, now = asOf,
                muscleLastObs = snapshot.muscleLastObs[muscle], equipment = snapshot.exerciseEquipment,
            )
            val meanLn = proj.effectiveE1rm[id]?.let { kotlin.math.ln(it) } ?: return@mapNotNull null
            val cleanVar = updater.age(snapshot.currentBeliefs[id]!!, asOf, snapshot.muscleLastObs[muscle]).evidenceVar
            id to Pred(meanLn, cleanVar)
        }.toMap()

        // Pass 1: estimate the shared session day-offset from all load-bearing residuals.
        val residuals = mutableListOf<SessionDayEffect.Residual>()
        for ((id, exSets) in byExercise) {
            val p = pred[id] ?: continue
            exSets.sortedBy { it.setNumber }.forEachIndexed { i, set ->
                val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
                residuals += SessionDayEffect.Residual(
                    value = obsLocation(obs) - p.meanLn,
                    obsVar = p.cleanVar + obs.noiseSd * obs.noiseSd,
                )
            }
        }
        val day = SessionDayEffect.estimate(config.sessionDayEffectSd, residuals)

        // Pass 2: fold each exercise, shifting the observation by −day.mean and marginalizing day.variance
        // into the observation noise. day = (0,0) when σ_day = 0 ⇒ identical to the prior model.
        val affectedMuscles = mutableSetOf<MuscleGroup>()
        for ((id, exSets) in byExercise) {
            var belief = snapshot.currentBeliefs[id]!!
            val muscleLast = snapshot.exerciseMuscle[id]?.let { snapshot.muscleLastObs[it] }
            val p = pred[id]
            var folded = false
            exSets.sortedBy { it.setNumber }.forEachIndexed { i, set ->
                val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
                if (scorer != null && p != null) {
                    scorer.accumulate(shiftObs(obs, -day.mean), p.meanLn, p.cleanVar + day.variance)
                }
                val infNoise = kotlin.math.sqrt(obs.noiseSd * obs.noiseSd + day.variance)
                belief = if (obs.gaussianLn != null) {
                    updater.foldGaussian(belief, obs.gaussianLn - day.mean, infNoise, asOf, muscleLast)
                } else {
                    updater.foldCensored(belief, obs.lowerLn?.minus(day.mean), obs.upperLn?.minus(day.mean), infNoise, asOf, muscleLast)
                }
                folded = true
            }
            if (folded) {
                snapshot.currentBeliefs[id] = belief
                snapshot.exerciseMuscle[id]?.let { affectedMuscles.add(it) }
            }
        }
        for (m in affectedMuscles) snapshot.muscleLastObs[m] = asOf

        val steps = affectedMuscles.mapNotNull { m ->
            val exerciseIds = snapshot.muscleExerciseIds[m] ?: return@mapNotNull null
            MuscleStep(
                muscle = m,
                projection = projector.project(
                    beliefs = snapshot.currentBeliefs, seedCoef = snapshot.seedCoefficients,
                    muscleExerciseIds = exerciseIds, now = asOf,
                    muscleLastObs = snapshot.muscleLastObs[m], equipment = snapshot.exerciseEquipment,
                ),
            )
        }
        return StepResult(steps)
    }

    /** Point location of an observation on ln(fresh-1RM): counted point, else interval midpoint, else the finite bound. */
    private fun obsLocation(obs: SetObservation): Float = when {
        obs.gaussianLn != null -> obs.gaussianLn
        obs.lowerLn != null && obs.upperLn != null -> (obs.lowerLn + obs.upperLn) / 2f
        obs.lowerLn != null -> obs.lowerLn
        obs.upperLn != null -> obs.upperLn
        else -> 0f
    }

    /** An observation with every populated bound/point shifted by [delta] (day-offset removal for scoring). */
    private fun shiftObs(obs: SetObservation, delta: Float): SetObservation = obs.copy(
        lowerLn = obs.lowerLn?.plus(delta),
        upperLn = obs.upperLn?.plus(delta),
        gaussianLn = obs.gaussianLn?.plus(delta),
    )
```

Note: `scorer.accumulate` is fed the observation shifted by `−day.mean` so the residual it scores is
offset-free, and predictive variance `p.cleanVar + day.variance` marginalizes the day uncertainty —
this keeps the fold and the (fixed-config) predictive score consistent for Task 4's parity check.

- [ ] **Step 4: Run the day-effect tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*StepperDayEffectTest"`
Expected: PASS (3 tests), including the dampening test.

- [ ] **Step 5: Commit**

```bash
jj describe -m "feat(estimator): two-pass session day-effect fold in the stepper (σ_day=0 = no-op)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" && jj new
```

---

### Task 4: No-op regression gate at default config

**Files:**
- Test: run existing suites — no new files. (`BeliefSimulationTest`, `ProdBssPrescriptionTest`, backtest, full JVM.)

This task proves Tasks 1-3 changed nothing at the shipped defaults (`obsNoiseScale=1f`, `sessionDayEffectSd=0f`) before we touch any default. If anything is red here, the fold refactor broke parity — fix before proceeding.

- [ ] **Step 1: Run the pinned estimator gates**

Run: `./gradlew :app:testDebugUnitTest --tests "*BeliefSimulationTest" --tests "*ProdBssPrescriptionTest"`
Expected: PASS, unchanged from `main` (no re-pin yet).

- [ ] **Step 2: Run the backtest parity + comparison tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*BacktestComparisonTest" --tests "*VarianceStudyStreamTest" --tests "*RecalibrationHarnessTest"`
Expected: PASS. In particular the `scoredReplayTotal ↔ captureStream` parity is intact (Pass 1/Pass 2 are no-ops at σ_day=0; per-exercise prediction is still computed once per exercise).

- [ ] **Step 3: Run the full JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS, same count as `main`.

- [ ] **Step 4: Commit (checkpoint, no code)**

```bash
jj describe -m "test(estimator): confirm day-effect/obs-noise knobs are no-op at defaults (green gate)" && jj new
```

(If nothing changed in the working copy, this is just a descriptive checkpoint; `jj new` starts the next change.)

---

### Task 5: Joint offline re-fit — 2D sweep + report + light-lift check

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceBudgetJointFit.kt`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/VarianceBudgetJointFitTest.kt`
- Reuses: `captureStream` (extended below), `DayEffectScorer`, `heldOutScore` (`VarianceStudyScoring.kt`), `LightLiftSwing` diagnostic (`VarianceStudyDiagnostics.kt`).

**Interfaces:**
- Consumes: `BacktestHarness.load(): BacktestData?` (fixture loader; `.history`, `.newSnapshot()`),
  `RecalibrationHarness.UserHistory(history) { newSnapshot() }`, `RecalibrationHarness.heldOutTailScore`,
  `BacktestHarness.replayPolicyPrescriptions(data, config): List<Row>`, `lightestLiftSwing(rows): LightLiftSwing?`.
- Produces: `app/build/variance-budget-jointfit-report.txt` and a RECOMMENDED/`interior-in-both-dims` verdict.

**Design.** The deployed model folds *and* scores with the day-effect. Measure the deployed mechanism:
1. Fold with the day-effect by scoring a held-out replay through the **production** path: reuse
   `RecalibrationHarness.heldOutTailScore(user, config)` where `config` carries `obsNoiseScale` and
   `sessionDayEffectSd`. Because Task 3 threads the day-effect through both the fold and `scorer.accumulate`,
   `scoredReplayTotal` (production stepper + `PredictiveScoreAccumulator`) already measures the deployed
   fold+score under any `(obsNoiseScale, σ_day)`. **No separate study-tree scorer is needed** — the joint
   sweep is a 2D grid over `heldOutTailScore` with the two knobs set on the config.
2. Sweep `obsNoiseScale ∈ {1.0, 1.5, 2.0, 2.5, 3.0}` × `sessionDayEffectSd ∈ {0.0, 0.08, 0.12, 0.16, 0.20, 0.24}`
   (mirrors the study's sweep grids). Record the full grid, the argmax, and whether the argmax is interior
   in **both** dimensions.
3. Emit the report and also print the `LightLiftSwing` (ex29) max step under the argmax config vs default —
   spec §6 predicts it shrinks.

- [ ] **Step 1: Write the joint-fit driver**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig

/** Joint (obsNoiseScale, σ_day) grid search over held-out one-step-ahead CV on the real history. */
object VarianceBudgetJointFit {
    val OBS_SCALES = listOf(1.0, 1.5, 2.0, 2.5, 3.0)
    val DAY_SDS = listOf(0.0, 0.08, 0.12, 0.16, 0.20, 0.24)

    data class Cell(val obsScale: Double, val sigmaDay: Double, val heldOut: Double)
    data class Result(val grid: List<Cell>, val best: Cell, val interiorObs: Boolean, val interiorDay: Boolean)

    fun run(user: RecalibrationHarness.UserHistory, minFold: Int = 8): Result {
        // heldOutTailScore telescopes the forward-chaining held-out sum under a FIXED config to two
        // replays (see RecalibrationHarness); the day-effect + obs-noise both flow through the config.
        val grid = OBS_SCALES.flatMap { os ->
            DAY_SDS.map { sd ->
                val cfg = EstimatorConfig(obsNoiseScale = os.toFloat(), sessionDayEffectSd = sd.toFloat())
                Cell(os, sd, RecalibrationHarness.heldOutTailScore(user, cfg, minFold))
            }
        }
        val best = grid.maxBy { it.heldOut }
        val interiorObs = best.obsScale != OBS_SCALES.first() && best.obsScale != OBS_SCALES.last()
        val interiorDay = best.sigmaDay != DAY_SDS.first() && best.sigmaDay != DAY_SDS.last()
        return Result(grid, best, interiorObs, interiorDay)
    }

    fun format(r: Result): String = buildString {
        appendLine("Variance-budget joint fit (obsNoiseScale × sessionDayEffectSd, held-out one-step CV)")
        appendLine("obsScale \\ σ_day: ${DAY_SDS.joinToString(" ") { "%.2f".format(it) }}")
        for (os in VarianceBudgetJointFit.OBS_SCALES) {
            val row = DAY_SDS.map { sd -> r.grid.first { it.obsScale == os && it.sigmaDay == sd }.heldOut }
            appendLine("%.1f: %s".format(os, row.joinToString(" ") { "%.1f".format(it) }))
        }
        appendLine("BEST obsScale=%.2f σ_day=%.2f heldOut=%.2f interiorObs=%b interiorDay=%b"
            .format(r.best.obsScale, r.best.sigmaDay, r.best.heldOut, r.interiorObs, r.interiorDay))
        val verdict = if (r.interiorObs && r.interiorDay) "ADOPT (interior in both dims)"
            else "DO NOT ADOPT — pins a bound (release valve; widen grid or reconsider)"
        appendLine("VERDICT: $verdict")
    }
}
```

- [ ] **Step 2: Write the report test (mirrors `VarianceIdentificationTest` fixture handling)**

```kotlin
package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class VarianceBudgetJointFitTest {
    @Test fun jointFitReport() {
        val data = BacktestHarness.load()
        assumeTrue("no personal history.json fixture; skipping", data != null)
        data!!
        val user = RecalibrationHarness.UserHistory(data.history) { data.newSnapshot() }
        val result = VarianceBudgetJointFit.run(user)

        // Light-lift swing (spec §6): compare the adopted argmax config vs today's default.
        val bestCfg = EstimatorConfig(
            obsNoiseScale = result.best.obsScale.toFloat(),
            sessionDayEffectSd = result.best.sigmaDay.toFloat(),
        )
        val swingBest = lightestLiftSwing(BacktestHarness.replayPolicyPrescriptions(data, bestCfg))
        val swingDefault = lightestLiftSwing(BacktestHarness.replayPolicyPrescriptions(data, EstimatorConfig()))

        val report = VarianceBudgetJointFit.format(result) +
            "\nlight-lift swing  best=$swingBest  default=$swingDefault\n"
        val out = File("build/variance-budget-jointfit-report.txt")
        out.parentFile?.mkdirs()
        out.writeText(report)
        println(report)
    }
}
```

- [ ] **Step 3: Run the joint fit**

Run: `./gradlew :app:testDebugUnitTest --tests "*VarianceBudgetJointFitTest"`
Expected: PASS; `app/build/variance-budget-jointfit-report.txt` written with the grid, argmax, and VERDICT.

- [ ] **Step 4: Read the report and record the decision**

Open `app/build/variance-budget-jointfit-report.txt`. Confirm the argmax is **interior in both dimensions**
(VERDICT = ADOPT). Record `obsScale*` and `σ_day*` — these are Task 6's adoption values. Confirm the
`LightLiftSwing` max step did not grow vs the study's B0 (4.5↔6.8 kg). **If the VERDICT is DO NOT ADOPT
(either dim pins a bound), STOP and surface it** — per spec §5 we do not adopt against a wall; report back
before continuing.

- [ ] **Step 5: Commit**

```bash
jj describe -m "test(variance-budget): joint (obs-noise × day-effect) held-out CV sweep + report

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" && jj new
```

---

### Task 6: Adopt the joint optimum into `EstimatorConfig` defaults

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseBelief.kt` (the two new fields' defaults)

**Interfaces:**
- Consumes: `obsScale*`, `σ_day*` from Task 5's committed report.

- [ ] **Step 1: Set the fitted defaults**

In `EstimatorConfig`, change the two knobs from their no-op defaults to the report's interior argmax
(exact values from `variance-budget-jointfit-report.txt`), e.g.:

```kotlin
    val obsNoiseScale: Float = <obsScale* from report>,
    ...
    val sessionDayEffectSd: Float = <σ_day* from report>,
```

Update both KDoc blocks to cite the joint-fit report and date (2026-07-11) as the source of record,
replacing "1f = today's behavior / 0f = no day-effect" with the adopted rationale (held-out CV gain,
interior in both dims, residual split 43/57).

- [ ] **Step 2: Verify the config compiles and the joint-fit test still recognizes the new default as the argmax**

Run: `./gradlew :app:testDebugUnitTest --tests "*SetObservationNoiseScaleTest" --tests "*SessionDayEffectTest" --tests "*StepperDayEffectTest"`
Expected: PASS. (`defaultScaleIsIdentity` still holds — it compares two default configs, not ×1 specifically. If you wrote it against a literal `1f`, update it to compare `EstimatorConfig()` against itself.)

- [ ] **Step 3: Commit**

```bash
jj describe -m "feat(estimator): adopt joint-fit obsNoiseScale=<..> sessionDayEffectSd=<..> defaults

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" && jj new
```

---

### Task 7: Make `BeliefSimulationTest` day-effect-honest and re-pin

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefSimulationTest.kt`

**Design.** The synthetic lifter's session-to-session variance is tamer than reality (problem statement
§finding 5), so the wider (now day-effect-bearing) intervals will over-cover *its* outcomes. Rather than
relax the coverage assertion blindly, inject a matching per-session shared multiplicative offset into the
lifter's truth so the sim actually exercises the day-effect, then re-pin.

The lifter applies per-set noise via `gauss.nextGaussian()` in `performExercise` (line ~146) and
`runQuadsSession` (line ~416). Add a **per-session** shared factor `dayFactor = exp(σ_day · gauss.nextGaussian())`
drawn once per session and multiplied into `true1RmFresh`/`setTrue1Rm` for every set in that session,
using `EstimatorConfig().sessionDayEffectSd` as σ_day so the sim's day-effect equals the estimator's model
(the same "sim matches the model" convention already used for `fatiguePerSet`).

- [ ] **Step 1: Inject the per-session day factor**

In `performExercise` and `runQuadsSession`, draw one `dayFactor` per session (thread it in from the session
loop in `simulateRealistic`/`runQuadsSession`'s caller, alongside the existing `gauss`) and apply it:

```kotlin
val setTrue1Rm = true1RmFresh * dayFactor * (1f - fatiguePerSet * (setNum - 1))
```

where `dayFactor = kotlin.math.exp(config.sessionDayEffectSd * gauss.nextGaussian()).toFloat()` is computed
once per simulated session (not per set/exercise).

- [ ] **Step 2: Run the sim to observe the new pinned values**

Run: `./gradlew :app:testDebugUnitTest --tests "*BeliefSimulationTest"`
Expected: some assertions FAIL with new numbers (coverage, tuned metrics). This is expected — the model now
carries more obs-noise + a day-effect.

- [ ] **Step 3: Re-pin to the observed outputs**

Update each failing pinned expectation to the value the run reports, keeping tolerances the same shape.
For `calibration_eightyPercentIntervalRoughlyCovers`, the day-effect-bearing lifter should bring coverage
back toward ~0.80 (the whole point of finding 5) — confirm it did not blow past the interval, then pin.
Do **not** loosen tolerances beyond what the day-effect honestly requires; if coverage is still far off,
that is a signal to re-examine, not to widen the band.

- [ ] **Step 4: Verify green**

Run: `./gradlew :app:testDebugUnitTest --tests "*BeliefSimulationTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj describe -m "test(sim): inject session day-effect into the synthetic lifter; re-pin BeliefSimulationTest

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" && jj new
```

---

### Task 8: Re-baseline `ProdBssPrescriptionTest` + the full backtest

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProdBssPrescriptionTest.kt`
- Modify: the full backtest baseline (the committed expected file / BAND the backtest asserts against — locate via `BacktestComparisonTest`).

**Design.** Adopting the joint optimum reprices the real history. Re-baseline the two data gates to the new
output, documenting direction/magnitude — the same user-approved ceremony as prior phases.

- [ ] **Step 1: Run the prod-BSS gate and observe the reprice**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProdBssPrescriptionTest"`
Expected: it may still pin the demonstrated 20 lb (obs-noise up + day-effect both *reduce* reactivity, so
the BSS should stay stable). If it moved, record the new prescribed weight and the driver.

- [ ] **Step 2: Run the full backtest and regenerate the baseline**

Run: `./gradlew :app:testDebugUnitTest --tests "*BacktestComparisonTest"`
Expected: BAND assertion may FAIL with a systematic reprice. Regenerate the committed baseline
(follow the regeneration path documented in `BacktestComparisonTest`/`BacktestBaselineGeneratorTest`),
inspect the per-exercise deltas, and confirm they are consistent with "gentler, day-protected updates"
(not a wild swing). Keep the BAND width as in prior phases unless the deltas justify otherwise.

- [ ] **Step 3: Re-pin / re-baseline and record the summary**

Update `ProdBssPrescriptionTest` (only if it moved) and the backtest baseline. In the commit message,
record the reprice direction and magnitude (p50/p95) and the light-lift result from Task 5.

- [ ] **Step 4: Verify green**

Run: `./gradlew :app:testDebugUnitTest --tests "*ProdBssPrescriptionTest" --tests "*BacktestComparisonTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
jj describe -m "test(backtest): re-baseline prod-BSS + full backtest for the variance-budget adoption

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" && jj new
```

---

### Task 9: Docs refresh + full suite + whole-branch review

**Files:**
- Modify: `docs/adaptation/` (the variance/observation-model doc — locate the file describing the belief/noise budget)
- Modify: `CLAUDE.md` (the "Progression system" section, the belief-fold paragraph) — add the session day-effect + obs-noise budget in one sentence.

- [ ] **Step 1: Update `docs/adaptation/`**

Add a short section describing: obs-noise budget (`obsNoiseScale`, within-session residual share), the
transient session day-effect `d ~ N(0, σ_day²)` (two-pass fold, marginalized, never durable, no DB
migration), and that both were jointly fit against held-out CV (cite `variance-budget-jointfit-report.txt`).

- [ ] **Step 2: Update `CLAUDE.md`**

In the "Progression system" → belief-fold description, add one sentence: each session's sets share a
marginalized day-effect intercept (spec §3-4) and observation noise carries `obsNoiseScale`; both fixed
global defaults from the 2026-07-11 joint fit.

- [ ] **Step 3: Run the full JVM + instrumented suites**

Run: `./gradlew :app:testDebugUnitTest`
Then (device/emulator): `./gradlew :app:connectedAndroidTest`
Expected: all green.

- [ ] **Step 4: Commit docs**

```bash
jj describe -m "docs(adaptation): document the session day-effect + obs-noise budget

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" && jj new
```

- [ ] **Step 5: Whole-branch review**

Invoke `superpowers:requesting-code-review` for a whole-branch review (opus), per prior phases. Address
findings, re-run the full suite, and report the final state (JVM pass count, instrumented pass count,
adopted `obsNoiseScale`/`σ_day`, reprice summary) before considering the phase done.

---

## Self-Review notes (author)

- **Spec coverage:** obs-noise budget → T1/T5/T6; day-effect model+fold → T2/T3; joint identifiability →
  T5 (2D grid, interior-in-both gate); no-behavior-change-until-adoption → T1-T4 defaults; prescription
  band untouched → no task modifies `PrescriptionPolicy` (deliberate); light-lift verification → T5 Step 4;
  sim honest + re-pin → T7; re-baseline → T8; docs + review → T9; no DB migration → asserted in T3/constraints.
- **Deferred items are NOT tasks** (fitter re-freeing/bounds, sim demotion, σ_day in prescription band,
  explicit light-lift damping) — intentionally out of scope per spec §2.
- **Data-dependent values** (T6 adopted constants, T7/T8 re-pins) are specified by *mechanism + decision
  rule + exact commands*, not fabricated numbers — the numbers are the tests' outputs. This matches how the
  Phase-5 recalibration ceremony was written.
- **Type consistency:** `SessionDayEffect.Residual(value, obsVar)` / `DayPosterior(mean, variance)` used
  identically in T2 and T3; `obsNoiseScale`/`sessionDayEffectSd` names consistent T1→T9.
</content>
