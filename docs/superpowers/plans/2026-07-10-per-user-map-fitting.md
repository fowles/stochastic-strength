# Per-user MAP fitting (Phase 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auto-tune five per-user hyperparameters by one-step-ahead predictive scoring during replay, regularized toward global defaults, with zero schema change and no observable behavior for thin histories.

**Architecture:** A pure `HyperparameterFitter` runs Nelder-Mead over five log-multipliers on `EstimatorConfig` defaults; each evaluation is one in-memory replay over preloaded `ReplayHistory` accumulating a predictive log-likelihood (half-blended pooled mean + clean own variance) plus lognormal log-priors. `DerivedStateStore` becomes the single source of the active (fitted) `EstimatorConfig`; the fit runs in the background off the workout-finish path, keyed on history so it self-warms and never loops.

**Tech Stack:** Kotlin, Android (min SDK 33), JUnit4 JVM unit tests, jj (Jujutsu) for commits, Gradle.

## Global Constraints

- **Commit tool:** `jj commit -m "<msg>"` ONLY. Never `git commit` (detached HEAD folds work into the wrong change). One commit per task's final step.
- **Commit message trailer:** end every commit message with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.
- **Test tool selection:** use the Read/Grep/Glob tools, not `cat`/`grep`/`find`.
- **Package root:** `io.github.fowles.stochastic_strength`.
- **Unit test command:** `./gradlew :app:testDebugUnitTest --tests "<fqcn>"` for one class; `./gradlew :app:testDebugUnitTest` for the full JVM suite.
- **No Room migration.** θ is never persisted. Nothing new is stored in the database.
- **Not fitted:** `uncertaintyZ`, `overloadDelta`, `levelAnchorPrecision`. Fitting these is out of scope.
- **Fitted set (exactly five):** `detrainRatePerWeek`, a shared multiplier on `repNoiseBucket`+`repNoiseCounted`, `fatiguePerSet`, `processNoisePerDay`, a shared multiplier on `tauBarbell`+`tauMachineCable`+`tauOtherLoaded`.
- **Scorer decisions (from the spec):** predict from the pooled/LOO-shrunk mean (`MuscleProjection.effectiveE1rm`), paired with the exercise's **clean** own variance (`ExerciseBelief.evidenceVar`, aged); predictive variance = clean var + observation noise `s²`.
- Spec: `docs/superpowers/specs/2026-07-10-per-user-map-fitting-design.md`.

---

## File Structure

**New (pure, `app/src/main/java/.../domain/progression/`):**
- `PredictiveDensity.kt` — Gaussian log-density + censored log-mass for scoring one observation.
- `PredictiveScoreAccumulator.kt` — running sum an observer feeds during replay.
- `NelderMead.kt` — pure downhill-simplex minimizer of `(DoubleArray) -> Double`.
- `HyperparameterFitter.kt` — `FitConfig`, θ↔config mapping, MAP objective, guardrails, `fit()`.

**New (`app/src/main/java/.../domain/derived/`):**
- `FitDiagnostics.kt` — fitted-vs-default readout for the debug panel.

**Modified:**
- `domain/progression/NormalCdf.kt` — add `intervalLogMass`.
- `domain/progression/SessionProgressionStepper.kt` — optional scorer accumulation.
- `domain/derived/DerivedStateStore.kt` — active-config + fit-key + diagnostics slots.
- `domain/WorkoutRepository.kt` — read active config everywhere; launch background fit; inject scope.
- `StochasticStrengthApp.kt` — pass `applicationScope` to the repository.
- `ui/debug/ExerciseCoefficientDetailViewModel.kt` + `ExerciseCoefficientDetailScreen.kt` — fitted-θ panel.
- `docs/adaptation/` — new `06-fitting` page; version bump.

**Modified (tests):**
- `test/.../domain/backtest/BacktestHarness.kt` + `BacktestComparisonTest.kt` — fit-then-replay gate.
- `test/.../domain/progression/BeliefSimulationTest.kt` — planted-parameter recovery pin.

---

## Task 1: PredictiveDensity + NormalCdf.intervalLogMass

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/NormalCdf.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/PredictiveDensity.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/PredictiveDensityTest.kt`

**Interfaces:**
- Produces:
  - `NormalCdf.intervalLogMass(mean: Float, sd: Float, lowerLn: Float?, upperLn: Float?): Float` — natural-log of the probability mass in `[lowerLn, upperLn]` under `N(mean, sd²)`; null bound = unbounded that side; clamps standardized bounds to ±6; floors mass at `1e-6`.
  - `object PredictiveDensity { fun gaussianLogDensity(obsLn: Float, predMeanLn: Float, predVar: Float): Float; fun censoredLogMass(lowerLn: Float?, upperLn: Float?, predMeanLn: Float, predVar: Float): Float }` — `predVar` already includes `s²`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

class PredictiveDensityTest {
    // Reference Gaussian log-density: -0.5*ln(2πV) - (x-m)²/(2V).
    @Test fun gaussianMatchesClosedForm() {
        val m = ln(100f); val v = 0.05f; val x = ln(105f)
        val expected = (-0.5 * ln(2 * PI * v) - (x - m).toDouble() * (x - m) / (2 * v))
        assertEquals(expected, PredictiveDensity.gaussianLogDensity(x, m, v).toDouble(), 1e-4)
    }

    // Interval mass by coarse numerical integration of the predictive Normal.
    @Test fun censoredMatchesNumericalIntegration() {
        val m = ln(100f); val v = 0.04f; val lo = ln(98f); val hi = ln(104f)
        val sd = sqrt(v)
        var mass = 0.0
        val steps = 20000; val a = m - 6 * sd; val b = m + 6 * sd; val dx = (b - a) / steps
        var x = a
        while (x < b) {
            if (x in lo..hi) mass += exp(-0.5 * ((x - m) / sd).toDouble() * ((x - m) / sd)) / (sd * sqrt(2 * PI)) * dx
            x += dx
        }
        // Rectangle-rule Riemann sum has ~O(dx) boundary error at the interval edges; 5e-3 keeps
        // this a real closed-form check (~0.5%) while the 1e-4 consistency test below is the tight guard.
        assertEquals(ln(mass), PredictiveDensity.censoredLogMass(lo, hi, m, v).toDouble(), 5e-3)
    }

    @Test fun oneSidedLowerIsHalfAtMean() {
        // Mass of [mean, +∞) under the predictive Normal is 0.5 → log ≈ ln(0.5).
        val m = ln(50f); val v = 0.06f
        assertEquals(ln(0.5), PredictiveDensity.censoredLogMass(m, null, m, v).toDouble(), 1e-3)
    }

    // Consistency guard: the scorer's interval mass equals the fold's own Z (never diverge).
    @Test fun intervalLogMassAgreesWithFoldZ() {
        val m = ln(80f); val v = 0.05f; val lo = ln(78f); val hi = ln(85f); val sd = sqrt(v)
        val a = ((lo - m) / sd).coerceIn(-6f, 6f); val b = ((hi - m) / sd).coerceIn(-6f, 6f)
        val z = NormalCdf.cdf(b) - NormalCdf.cdf(a)
        assertEquals(ln(z.toDouble()), NormalCdf.intervalLogMass(m, sd, lo, hi).toDouble(), 1e-4)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.PredictiveDensityTest"`
Expected: FAIL — unresolved reference `PredictiveDensity` / `intervalLogMass`.

- [ ] **Step 3: Add `intervalLogMass` to NormalCdf**

Add to `object NormalCdf` in `NormalCdf.kt` (after `cdf`):

```kotlin
    /** Natural-log of the probability mass in [lowerLn, upperLn] under N(mean, sd²); null = unbounded.
     *  Standardized bounds clamped to ±6; mass floored at 1e-6 to keep the log finite. */
    fun intervalLogMass(mean: Float, sd: Float, lowerLn: Float?, upperLn: Float?): Float {
        val a = (if (lowerLn != null) (lowerLn - mean) / sd else -CLAMP).coerceIn(-CLAMP, CLAMP)
        val b = (if (upperLn != null) (upperLn - mean) / sd else CLAMP).coerceIn(-CLAMP, CLAMP)
        val z = (cdf(b) - cdf(a)).coerceAtLeast(MIN_MASS)
        return kotlin.math.ln(z)
    }

    private const val CLAMP = 6f
    private const val MIN_MASS = 1e-6f
```

- [ ] **Step 4: Create PredictiveDensity.kt**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Predictive log-scores for one observation given a prediction N(predMeanLn, predVar), where predVar
 * already folds in the observation noise s². Used by the phase-4 fitter's scoring objective; the
 * censored branch reuses NormalCdf.intervalLogMass so it can never diverge from the fold's own Z.
 */
object PredictiveDensity {
    fun gaussianLogDensity(obsLn: Float, predMeanLn: Float, predVar: Float): Float {
        val d = (obsLn - predMeanLn).toDouble()
        return (-0.5 * ln(2 * PI * predVar) - d * d / (2 * predVar)).toFloat()
    }

    fun censoredLogMass(lowerLn: Float?, upperLn: Float?, predMeanLn: Float, predVar: Float): Float =
        NormalCdf.intervalLogMass(predMeanLn, sqrt(predVar), lowerLn, upperLn)
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.PredictiveDensityTest"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat(fit): predictive log-density helpers (gaussian + censored mass)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Scorer accumulation in SessionProgressionStepper

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/PredictiveScoreAccumulator.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SessionProgressionStepper.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/StepperScoringTest.kt`

**Interfaces:**
- Consumes: `PredictiveDensity` (Task 1), `MuscleStrengthProjector.project`, `BeliefUpdater.age`, `SetObservation`.
- Produces:
  - `class PredictiveScoreAccumulator { val total: Double; fun accumulate(obs: SetObservation, predMeanLn: Float, predCleanVar: Float) }` — adds `predCleanVar + obs.noiseSd²` as the predictive variance.
  - `SessionProgressionStepper(updater, projector, config, scorer: PredictiveScoreAccumulator? = null)` — 4th constructor arg; when non-null, scores each folded observation against the pre-fold pooled prediction. `null` (production) is unchanged.

- [ ] **Step 1: Write the failing test**

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

class StepperScoringTest {
    private fun snapshot(): ReplaySnapshot {
        val snap = ReplaySnapshot(
            exerciseMuscle = mapOf(1L to MuscleGroup.QUADS),
            seedCoefficients = mapOf(1L to 1.0f),
            exerciseEquipment = mapOf(1L to Equipment.BARBELL),
        )
        snap.currentBeliefs[1L] = ExerciseBelief.seed(100f, at = 0L)
        return snap
    }

    private fun set(reps: Int, fb: SetFeedback) = WorkoutSet(
        sessionId = 1L, exerciseId = 1L, setNumber = 1, targetWeight = 80f,
        targetReps = reps, actualReps = null, feedback = fb,
    )

    @Test fun accumulatorSumsOneScorePerLoadObservation() {
        val acc = PredictiveScoreAccumulator()
        val stepper = SessionProgressionStepper(scorer = acc)
        val snap = snapshot()
        stepper.step(listOf(set(8, SetFeedback.RIR_2_4)), snap, asOf = 1_000L)
        // One load-bearing set → exactly one finite score contribution.
        assertTrue(acc.total.isFinite())
        val before = acc.total
        stepper.step(listOf(set(8, SetFeedback.HURT)), snapshot(), asOf = 2_000L)
        // HURT carries no observation → no additional score beyond the first accumulator's state.
        assertEquals(before, acc.total, 1e-9) // acc unchanged: HURT contributes nothing
    }

    @Test fun productionPathWithoutScorerIsUnaffected() {
        val snap = snapshot()
        val before = snap.currentBeliefs[1L]!!.mu
        SessionProgressionStepper().step(listOf(set(8, SetFeedback.RIR_2_4)), snap, asOf = 1_000L)
        assertTrue(snap.currentBeliefs[1L]!!.mu != before) // fold still happened, no scorer needed
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.StepperScoringTest"`
Expected: FAIL — `SessionProgressionStepper` has no `scorer` parameter; `PredictiveScoreAccumulator` unresolved.

- [ ] **Step 3: Create PredictiveScoreAccumulator.kt**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

/**
 * Running sum of one-step-ahead predictive log-scores, fed by [SessionProgressionStepper] when a
 * candidate config is being scored during a fit replay. Predictive variance = clean own variance
 * (predCleanVar) + the observation's own noise s². Not thread-safe; one accumulator per fit eval.
 */
class PredictiveScoreAccumulator {
    var total: Double = 0.0
        private set

    fun accumulate(obs: SetObservation, predMeanLn: Float, predCleanVar: Float) {
        val v = predCleanVar + obs.noiseSd * obs.noiseSd
        total += if (obs.gaussianLn != null) {
            PredictiveDensity.gaussianLogDensity(obs.gaussianLn, predMeanLn, v).toDouble()
        } else {
            PredictiveDensity.censoredLogMass(obs.lowerLn, obs.upperLn, predMeanLn, v).toDouble()
        }
    }
}
```

- [ ] **Step 4: Wire the optional scorer into the stepper**

In `SessionProgressionStepper.kt`, add the constructor arg and the scoring block. Replace the constructor header:

```kotlin
class SessionProgressionStepper(
    private val updater: BeliefUpdater = BeliefUpdater(),
    private val projector: MuscleStrengthProjector = MuscleStrengthProjector(),
    private val config: EstimatorConfig = EstimatorConfig(),
    private val scorer: PredictiveScoreAccumulator? = null,
) {
```

Then inside `step`, in the `sets.groupBy { it.exerciseId }.forEach { (id, exSets) -> ... }` block, after `val muscleLast = ...` and before `var folded = false`, add the pre-fold prediction, and score inside the set loop. The full block becomes:

```kotlin
        sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
            if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
            var belief = snapshot.currentBeliefs[id] ?: return@forEach
            val muscleLast = snapshot.exerciseMuscle[id]?.let { snapshot.muscleLastObs[it] }
            // Pre-fold pooled prediction for scoring (spec §2): half-blended mean μ̃ from the projector,
            // clean own variance aged to asOf. Computed once per exercise, before its own sets fold.
            var predMeanLn: Float? = null
            var predCleanVar = 0f
            if (scorer != null) {
                val muscle = snapshot.exerciseMuscle[id]
                val ids = muscle?.let { snapshot.muscleExerciseIds[it] }
                if (ids != null) {
                    val proj = projector.project(
                        beliefs = snapshot.currentBeliefs, seedCoef = snapshot.seedCoefficients,
                        muscleExerciseIds = ids, now = asOf,
                        muscleLastObs = snapshot.muscleLastObs[muscle], equipment = snapshot.exerciseEquipment,
                    )
                    predMeanLn = proj.effectiveE1rm[id]?.let { kotlin.math.ln(it) }
                    predCleanVar = updater.age(belief, asOf, muscleLast).evidenceVar
                }
            }
            var folded = false
            exSets.sortedBy { it.setNumber }.forEachIndexed { i, set ->
                val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
                if (scorer != null && predMeanLn != null) scorer.accumulate(obs, predMeanLn, predCleanVar)
                belief = if (obs.gaussianLn != null) {
                    updater.foldGaussian(belief, obs.gaussianLn, obs.noiseSd, asOf, muscleLast)
                } else {
                    updater.foldCensored(belief, obs.lowerLn, obs.upperLn, obs.noiseSd, asOf, muscleLast)
                }
                folded = true
            }
            if (folded) {
                snapshot.currentBeliefs[id] = belief
                snapshot.exerciseMuscle[id]?.let { affectedMuscles.add(it) }
            }
        }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.StepperScoringTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Run the existing stepper/projection tests to confirm no regression**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.*"`
Expected: PASS (all existing progression tests unchanged).

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat(fit): optional predictive-score accumulation in the stepper

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: NelderMead minimizer

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/NelderMead.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/NelderMeadTest.kt`

**Interfaces:**
- Produces: `object NelderMead { fun minimize(start: DoubleArray, step: Double, maxIter: Int, f: (DoubleArray) -> Double): DoubleArray }` — returns the best point found; deterministic (fixed initial simplex = `start` plus `start + step·e_i`).

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Test

class NelderMeadTest {
    @Test fun findsMinimumOfShiftedQuadratic() {
        // f(x) = (x0-1.5)² + (x1+2)² + (x2-0.3)², min at (1.5, -2, 0.3).
        val target = doubleArrayOf(1.5, -2.0, 0.3)
        val best = NelderMead.minimize(doubleArrayOf(0.0, 0.0, 0.0), step = 0.5, maxIter = 500) { x ->
            var s = 0.0; for (i in x.indices) { val d = x[i] - target[i]; s += d * d }; s
        }
        for (i in target.indices) assertEquals(target[i], best[i], 1e-3)
    }

    @Test fun isDeterministic() {
        val f = { x: DoubleArray -> x.sumOf { (it - 1.0) * (it - 1.0) } }
        val a = NelderMead.minimize(doubleArrayOf(0.0, 0.0), 0.4, 300, f)
        val b = NelderMead.minimize(doubleArrayOf(0.0, 0.0), 0.4, 300, f)
        assertEquals(a[0], b[0], 0.0); assertEquals(a[1], b[1], 0.0)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.NelderMeadTest"`
Expected: FAIL — unresolved reference `NelderMead`.

- [ ] **Step 3: Create NelderMead.kt**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

/**
 * Pure, dependency-free downhill-simplex (Nelder-Mead) minimizer. Deterministic: the initial simplex
 * is [start] plus one vertex per dimension offset by [step]. Standard reflection/expansion/contraction/
 * shrink coefficients. Used by [HyperparameterFitter]; the objective clamps its own parameter bounds.
 */
object NelderMead {
    fun minimize(start: DoubleArray, step: Double, maxIter: Int, f: (DoubleArray) -> Double): DoubleArray {
        val n = start.size
        val simplex = Array(n + 1) { i -> start.copyOf().also { if (i > 0) it[i - 1] += step } }
        val fv = DoubleArray(n + 1) { f(simplex[it]) }
        repeat(maxIter) {
            val order = (0..n).sortedBy { fv[it] }
            val best = order.first(); val worst = order.last(); val second = order[order.size - 2]
            // Centroid of all but the worst.
            val centroid = DoubleArray(n)
            for (i in 0..n) if (i != worst) for (d in 0 until n) centroid[d] += simplex[i][d] / n
            fun at(coef: Double): DoubleArray = DoubleArray(n) { centroid[it] + coef * (centroid[it] - simplex[worst][it]) }
            val refl = at(1.0); val fRefl = f(refl)
            if (fRefl < fv[best]) {
                val exp = at(2.0); val fExp = f(exp)
                if (fExp < fRefl) { simplex[worst] = exp; fv[worst] = fExp } else { simplex[worst] = refl; fv[worst] = fRefl }
            } else if (fRefl < fv[second]) {
                simplex[worst] = refl; fv[worst] = fRefl
            } else {
                val contract = at(0.5); val fCon = f(contract)
                if (fCon < fv[worst]) { simplex[worst] = contract; fv[worst] = fCon }
                else { // shrink toward best
                    for (i in 0..n) if (i != best) {
                        for (d in 0 until n) simplex[i][d] = simplex[best][d] + 0.5 * (simplex[i][d] - simplex[best][d])
                        fv[i] = f(simplex[i])
                    }
                }
            }
        }
        val bestIdx = (0..n).minByOrNull { fv[it] }!!
        return simplex[bestIdx]
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.NelderMeadTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(fit): pure Nelder-Mead simplex minimizer

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: HyperparameterFitter

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/HyperparameterFitter.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/HyperparameterFitterTest.kt`

**Interfaces:**
- Consumes: `EstimatorConfig`, `ReplayHistory`, `ReplaySnapshot`, `ReplayEngine`, `SessionProgressionStepper`, `PredictiveScoreAccumulator`, `NelderMead`.
- Produces:
  - `data class FitConfig(minFitSessions: Int = 15, boundMultiplierLo: Double = 0.25, boundMultiplierHi: Double = 4.0, priorSd: Double = 0.5, maxIterations: Int = 200)`
  - `class HyperparameterFitter(defaults: EstimatorConfig = EstimatorConfig(), fitConfig: FitConfig = FitConfig())`
  - `HyperparameterFitter.Result(config: EstimatorConfig, score: Double, defaultScore: Double, atDefaults: Boolean, sessionCount: Int)`
  - `HyperparameterFitter.fit(history: ReplayHistory, newSnapshot: () -> ReplaySnapshot): Result` — `newSnapshot` builds a fresh scored-replay snapshot (fresh mutable belief maps, shared static inputs) per evaluation.
  - `HyperparameterFitter.applyTheta(logTheta: DoubleArray): EstimatorConfig` — maps five log-multipliers (order: drift, repNoise, fatigue, procNoise, tau) onto defaults, each multiplier clamped to `[lo, hi]`. Public for the mapping test.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSession
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HyperparameterFitterTest {
    private val defaults = EstimatorConfig()
    private val fitter = HyperparameterFitter(defaults)

    @Test fun applyThetaZeroIsDefaults() {
        val c = fitter.applyTheta(DoubleArray(5) { 0.0 })
        assertEquals(defaults.fatiguePerSet, c.fatiguePerSet, 1e-7f)
        assertEquals(defaults.processNoisePerDay, c.processNoisePerDay, 1e-9f)
        assertEquals(defaults.tauBarbell, c.tauBarbell, 1e-7f)
        assertEquals(defaults.detrainRatePerWeek, c.detrainRatePerWeek, 1e-7f)
        assertEquals(defaults.repNoiseBucket, c.repNoiseBucket, 1e-7f)
    }

    @Test fun applyThetaClampsToBounds() {
        // A huge positive log-multiplier saturates at ×4; huge negative at ÷4.
        val hi = fitter.applyTheta(doubleArrayOf(0.0, 0.0, 10.0, 0.0, 0.0))
        assertEquals(defaults.fatiguePerSet * 4f, hi.fatiguePerSet, 1e-6f)
        val lo = fitter.applyTheta(doubleArrayOf(0.0, 0.0, -10.0, 0.0, 0.0))
        assertEquals(defaults.fatiguePerSet * 0.25f, lo.fatiguePerSet, 1e-6f)
    }

    @Test fun belowFloorReturnsDefaults() {
        val history = fatiguePlantedHistory(nSessions = 5, trueFatigue = 0.09f)
        val r = HyperparameterFitter(defaults).fit(history) { syntheticSnapshot() }
        assertTrue(r.atDefaults)
        assertEquals(defaults.fatiguePerSet, r.config.fatiguePerSet, 0f)
    }

    @Test fun recoversHigherFatigueFromSyntheticHistory() {
        // A lifter whose TRUE per-set fatigue (0.09) is well above default (0.03). Each session is three
        // counted TOO_HARD sets at a fixed weight whose achieved reps are generated from the true fatigue
        // via the SAME 1RM formula the estimator uses — so the data is exactly what such a lifter produces
        // and recovering high fatigue is a genuine round-trip. The fitter should move fatiguePerSet up
        // toward truth (bounded at ×4 = 0.12).
        val history = fatiguePlantedHistory(nSessions = 30, trueFatigue = 0.09f)
        val r = HyperparameterFitter(defaults).fit(history) { syntheticSnapshot() }
        assertTrue("expected a real fit", !r.atDefaults)
        assertTrue("fatigue should rise toward truth, got ${r.config.fatiguePerSet}",
            r.config.fatiguePerSet > defaults.fatiguePerSet)
        assertTrue(r.score >= r.defaultScore)
    }

    // --- synthetic fixtures: one barbell exercise; three counted sets/session with reps that fall
    // set-over-set exactly as a lifter with [trueFatigue] would produce at a fixed weight. ---
    private fun syntheticSnapshot() = ReplaySnapshot(
        exerciseMuscle = mapOf(1L to MuscleGroup.QUADS),
        seedCoefficients = mapOf(1L to 1.0f),
        exerciseEquipment = mapOf(1L to Equipment.BARBELL),
    ).also { it.currentBeliefs[1L] = ExerciseBelief.seed(100f, at = 0L) }

    private fun fatiguePlantedHistory(
        nSessions: Int, trueFatigue: Float, trueFresh1RM: Float = 100f, weight: Float = 80f,
    ): ReplayHistory {
        val dayMs = 86_400_000L
        // Integer reps at [weight] whose implied 1RM is closest to a target capacity (formula inverse).
        fun repsFor(capacity: Float): Int = (1..15).minByOrNull { rep ->
            kotlin.math.abs(DefaultProgressionEngine.rawToOneRepMax(weight, rep.toFloat()) - capacity)
        }!!
        val sessions = (1..nSessions).map { WorkoutSession(id = it.toLong(), endTime = it * dayMs) }
        val sets = sessions.associate { s ->
            val rows = (1..3).map { k ->
                val capacity = trueFresh1RM * (1f - trueFatigue * (k - 1))
                WorkoutSet(sessionId = s.id, exerciseId = 1L, setNumber = k, targetWeight = weight,
                    targetReps = 8, actualReps = repsFor(capacity), feedback = SetFeedback.TOO_HARD)
            }
            s.id to rows
        }
        return ReplayHistory(sessions, sets, initialOverrides = emptyList(), sessionOverrides = emptyMap())
    }
}
```

Add the import `import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine` to the test file.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.HyperparameterFitterTest"`
Expected: FAIL — unresolved reference `HyperparameterFitter`.

- [ ] **Step 3: Create HyperparameterFitter.kt**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import kotlin.math.exp

/** Guardrail constants for per-user fitting (spec §8). Multipliers are on each parameter's default. */
data class FitConfig(
    val minFitSessions: Int = 15,
    val boundMultiplierLo: Double = 0.25,
    val boundMultiplierHi: Double = 4.0,
    val priorSd: Double = 0.5,
    val maxIterations: Int = 200,
)

/**
 * Per-user MAP fitting (spec §2–§3). Nelder-Mead over five log-multipliers on [defaults]
 * (order: drift, repNoise, fatigue, procNoise, tau). Each objective evaluation is one in-memory
 * scored replay (predictive log-likelihood via [PredictiveScoreAccumulator]) plus lognormal
 * log-priors centered on the defaults. MAP; regularized so a thin history stays at defaults.
 * θ is never persisted; the caller caches the returned config.
 */
class HyperparameterFitter(
    private val defaults: EstimatorConfig = EstimatorConfig(),
    private val fitConfig: FitConfig = FitConfig(),
) {
    data class Result(
        val config: EstimatorConfig,
        val score: Double,
        val defaultScore: Double,
        val atDefaults: Boolean,
        val sessionCount: Int,
    )

    /** Maps five log-multipliers onto the defaults, each multiplier clamped to [lo, hi]. */
    fun applyTheta(logTheta: DoubleArray): EstimatorConfig {
        fun m(i: Int): Float =
            exp(logTheta[i]).coerceIn(fitConfig.boundMultiplierLo, fitConfig.boundMultiplierHi).toFloat()
        return defaults.copy(
            detrainRatePerWeek = defaults.detrainRatePerWeek * m(0),
            repNoiseBucket = defaults.repNoiseBucket * m(1),
            repNoiseCounted = defaults.repNoiseCounted * m(1),
            fatiguePerSet = defaults.fatiguePerSet * m(2),
            processNoisePerDay = defaults.processNoisePerDay * m(3),
            tauBarbell = defaults.tauBarbell * m(4),
            tauMachineCable = defaults.tauMachineCable * m(4),
            tauOtherLoaded = defaults.tauOtherLoaded * m(4),
        )
    }

    fun fit(history: ReplayHistory, newSnapshot: () -> ReplaySnapshot): Result {
        val sessionCount = history.sessions.count { it.endTime != null }
        val defaultScore = mapObjective(DoubleArray(5) { 0.0 }, history, newSnapshot)
        if (sessionCount < fitConfig.minFitSessions) {
            return Result(defaults, defaultScore, defaultScore, atDefaults = true, sessionCount)
        }
        // NelderMead minimizes, so it optimizes the negated MAP objective.
        val best = NelderMead.minimize(DoubleArray(5) { 0.0 }, step = 0.35, maxIter = fitConfig.maxIterations) {
            -mapObjective(it, history, newSnapshot)
        }
        val bestScore = mapObjective(best, history, newSnapshot)
        return if (bestScore > defaultScore) {
            Result(applyTheta(best), bestScore, defaultScore, atDefaults = false, sessionCount)
        } else {
            Result(defaults, defaultScore, defaultScore, atDefaults = true, sessionCount)
        }
    }

    /** MAP objective (higher is better): predictive log-likelihood + lognormal log-priors. */
    private fun mapObjective(logTheta: DoubleArray, history: ReplayHistory, newSnapshot: () -> ReplaySnapshot): Double {
        val config = applyTheta(logTheta)
        val acc = PredictiveScoreAccumulator()
        val engine = ReplayEngine(SessionProgressionStepper(config = config, scorer = acc), config)
        engine.run(history, newSnapshot()) { _, _, _, _, _ -> }
        var logPrior = 0.0
        for (t in logTheta) logPrior += -0.5 * (t / fitConfig.priorSd) * (t / fitConfig.priorSd)
        return acc.total + logPrior
    }
}
```

Note: `ReplayEngine.run(history, snapshot, observer)` is the existing preloaded overload; the scorer lives on the stepper, so the observer is a no-op. `ln` import kept for readability parity though not directly referenced — remove if the linter flags it unused.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.HyperparameterFitterTest"`
Expected: PASS (4 tests). If `recoversHigherFatigueFromSyntheticHistory` is flaky on the synthetic fixture, widen `nSessions` to 40 and confirm direction (fitted fatigue strictly above default); do not weaken the `score >= defaultScore` assertion.

- [ ] **Step 5: Commit**

```bash
jj commit -m "feat(fit): HyperparameterFitter — MAP objective, floor, fallback

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: DerivedStateStore active-config + fit slots

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/derived/FitDiagnostics.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStore.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/derived/DerivedStateStoreFitTest.kt`

**Interfaces:**
- Produces:
  - `data class FitKey(val sessionCount: Int, val latestEndTime: Long)`
  - `data class FitDiagnostics(val fitted: EstimatorConfig, val defaults: EstimatorConfig, val score: Double, val defaultScore: Double, val atDefaults: Boolean, val sessionCount: Int)`
  - On `DerivedStateStore`: `fun activeConfig(): EstimatorConfig` (default `EstimatorConfig()`), `fun activeFitKey(): FitKey?`, `fun fitDiagnostics(): FitDiagnostics?`, `fun setFit(config: EstimatorConfig, key: FitKey, diagnostics: FitDiagnostics)`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain.derived

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DerivedStateStoreFitTest {
    @Test fun defaultsBeforeAnyFit() {
        val store = DerivedStateStore()
        assertEquals(EstimatorConfig(), store.activeConfig())
        assertNull(store.activeFitKey())
        assertNull(store.fitDiagnostics())
    }

    @Test fun setFitRoundTrips() {
        val store = DerivedStateStore()
        val fitted = EstimatorConfig().copy(fatiguePerSet = 0.05f)
        val key = FitKey(sessionCount = 20, latestEndTime = 999L)
        val diag = FitDiagnostics(fitted, EstimatorConfig(), score = -10.0, defaultScore = -12.0, atDefaults = false, sessionCount = 20)
        store.setFit(fitted, key, diag)
        assertEquals(fitted, store.activeConfig())
        assertEquals(key, store.activeFitKey())
        assertEquals(diag, store.fitDiagnostics())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.derived.DerivedStateStoreFitTest"`
Expected: FAIL — unresolved `FitKey` / `FitDiagnostics` / `activeConfig`.

- [ ] **Step 3: Create FitDiagnostics.kt**

```kotlin
package io.github.fowles.stochastic_strength.domain.derived

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig

/** Identity of a fit: the history it was computed over. θ re-fits only when this changes. */
data class FitKey(val sessionCount: Int, val latestEndTime: Long)

/** Read-only fitted-vs-default readout for the debug panel (spec §7). */
data class FitDiagnostics(
    val fitted: EstimatorConfig,
    val defaults: EstimatorConfig,
    val score: Double,
    val defaultScore: Double,
    val atDefaults: Boolean,
    val sessionCount: Int,
)
```

- [ ] **Step 4: Add the slots to DerivedStateStore**

In `DerivedStateStore.kt`, add imports and fields. After the `private var live` declaration add:

```kotlin
    @Volatile private var activeEstimatorConfig: EstimatorConfig = EstimatorConfig()
    @Volatile private var fitKey: FitKey? = null
    @Volatile private var diagnostics: FitDiagnostics? = null

    /** The active (fitted, or default) config — single source for replay + prescription. */
    fun activeConfig(): EstimatorConfig = activeEstimatorConfig
    fun activeFitKey(): FitKey? = fitKey
    fun fitDiagnostics(): FitDiagnostics? = diagnostics

    /** Installs a completed fit. θ lives only here (never persisted). */
    fun setFit(config: EstimatorConfig, key: FitKey, diagnostics: FitDiagnostics) {
        this.activeEstimatorConfig = config
        this.fitKey = key
        this.diagnostics = diagnostics
    }
```

Add the import at the top of the file:

```kotlin
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.derived.DerivedStateStoreFitTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
jj commit -m "feat(fit): DerivedStateStore holds the active config + fit diagnostics

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: WorkoutRepository single-source + background fit

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/StochasticStrengthApp.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/WorkoutRepositoryFitWiringTest.kt`

**Interfaces:**
- Consumes: `DerivedStateStore.activeConfig/activeFitKey/setFit` (Task 5), `HyperparameterFitter` (Task 4), `ReplayHistory`, `FitKey`, `FitDiagnostics`.
- Produces:
  - `WorkoutRepository(db, derivedState, progressionEngine, scope: CoroutineScope)` — new `scope` param (default `CoroutineScope(SupervisorJob() + Dispatchers.Default)` for tests).
  - `replayDerivedState()` reads `derivedState.activeConfig()` for the replay config and projector; after rebuild, if the current `FitKey` differs from `derivedState.activeFitKey()`, launches a background fit on `scope`.
  - `fitBlocking()` — suspend, testable: loads history, fits, installs via `setFit`, and re-runs `replayDerivedState`. The background launch calls this.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain

import androidx.test.core.app.ApplicationProvider
import io.github.fowles.stochastic_strength.data.AppDatabase
import io.github.fowles.stochastic_strength.domain.derived.DerivedStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WorkoutRepositoryFitWiringTest {
    // The fit skips below minFitSessions, so an empty DB leaves the active config at defaults and
    // records a fit key equal to the empty history — a second fit is a no-op (no loop).
    @Test fun emptyHistoryStaysAtDefaultsAndSetsKey() = runBlocking {
        val db = AppDatabase.getInstance(ApplicationProvider.getApplicationContext(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default))
        val derived = DerivedStateStore()
        val repo = WorkoutRepository(db, derivedState = derived,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default))
        repo.fitBlocking()
        assertEquals(progression.EstimatorConfig(), derived.activeConfig())
        // Key installed → activeFitKey non-null → subsequent replay won't relaunch for the same history.
        assert(derived.activeFitKey() != null)
    }
}
```

(If the project has no Robolectric test dependency, place this assertion in `androidTest` instead as an instrumented test with the same body using `ApplicationProvider`; the wiring logic is what matters. Verify by grepping `build.gradle` for `robolectric` before choosing.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryFitWiringTest"`
Expected: FAIL — no `scope` parameter / no `fitBlocking`.

- [ ] **Step 3: Add the scope param and imports**

In `WorkoutRepository.kt`, update the constructor and add imports:

```kotlin
class WorkoutRepository(
    private val db: AppDatabase,
    val derivedState: DerivedStateStore = DerivedStateStore(),
    private val progressionEngine: ProgressionEngine = DefaultProgressionEngine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val replayMutex = Mutex()
    private val fitMutex = Mutex()
```

Add imports:

```kotlin
import io.github.fowles.stochastic_strength.domain.derived.FitDiagnostics
import io.github.fowles.stochastic_strength.domain.derived.FitKey
import io.github.fowles.stochastic_strength.domain.progression.HyperparameterFitter
import io.github.fowles.stochastic_strength.domain.progression.ReplayHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
```

Delete the two fixed fields that hardcode the default config (they are replaced by per-rebuild construction):

```kotlin
    private val stepper = SessionProgressionStepper()
    private val replayEngine = ReplayEngine(stepper)
```

- [ ] **Step 4: Route replayDerivedState through the active config + preloaded history, and trigger the fit**

Replace the body of `replayDerivedState` so it (a) reads `derivedState.activeConfig()`, (b) loads history once, (c) builds a config-aware engine, and (d) triggers the fit after rebuild. Replace lines 200–250 region:

```kotlin
    suspend fun replayDerivedState() = replayMutex.withLock {
        val activeConfig = derivedState.activeConfig()
        val history = ReplayHistory.loadFromDb(db)
        val engine = ReplayEngine(SessionProgressionStepper(config = activeConfig), activeConfig)
        derivedState.rebuild { scratch ->
            val snapshot = ReplaySnapshot.loadStaticFromDb(db)
            val policyBuilder = PolicyStateBuilder()

            engine.run(history, snapshot) { sessionId, asOf, sets, snap, result ->
                policyBuilder.onSession(asOf, sets, snap)
                for (stepResult in result.steps) {
                    writeLevelUpdate(stepResult.muscle, stepResult.projection.level, sessionId, asOf, scratch)
                    val exerciseIds = snapshot.muscleExerciseIds[stepResult.muscle] ?: continue
                    writeDerivedCoefficients(
                        muscleExerciseIds = exerciseIds,
                        derivedCoef = stepResult.projection.derivedCoef,
                        snapshot = snapshot,
                        asOf = asOf,
                        scratch = scratch,
                    )
                }
            }
            scratch.putPolicyState(policyBuilder.build(snapshot.muscleLastObs.toMap()))
            scratch.putExerciseBeliefs(snapshot.currentBeliefs.toMap())

            val displayProjector = MuscleStrengthProjector(activeConfig)
            val displayNow = snapshot.currentBeliefs.values.maxOfOrNull { it.updatedAt } ?: 0L
            for ((muscle, exerciseIds) in snapshot.muscleExerciseIds) {
                if (scratch.muscleGroupStrength(muscle) != null) continue
                val projection = displayProjector.project(
                    beliefs = snapshot.currentBeliefs,
                    seedCoef = snapshot.seedCoefficients,
                    muscleExerciseIds = exerciseIds,
                    now = displayNow,
                    muscleLastObs = snapshot.muscleLastObs[muscle],
                    equipment = snapshot.exerciseEquipment,
                )
                if (projection.level > 0f) {
                    scratch.upsertMuscleGroupStrength(
                        MuscleGroupStrength(muscleGroup = muscle, baselineWeight = projection.level)
                    )
                }
            }
        }
        maybeLaunchFit(history)
    }

    /** Kicks a background fit when the history changed since the cached θ was fit. Keyed on history
     *  only, so the fit's own follow-up rebuild sees an unchanged key and does not relaunch. */
    private fun maybeLaunchFit(history: ReplayHistory) {
        val key = keyFor(history)
        if (key == derivedState.activeFitKey()) return
        scope.launch { fitBlocking(history) }
    }

    private fun keyFor(history: ReplayHistory): FitKey {
        val completed = history.sessions.filter { it.endTime != null }
        return FitKey(sessionCount = completed.size, latestEndTime = completed.maxOfOrNull { it.endTime!! } ?: 0L)
    }

    /** Fit θ over [history] (loaded if null), install it, and rebuild once with the fitted config. */
    suspend fun fitBlocking(history: ReplayHistory? = null) = fitMutex.withLock {
        val h = history ?: ReplayHistory.loadFromDb(db)
        val key = keyFor(h)
        if (key == derivedState.activeFitKey()) return@withLock
        val staticInputs = ReplaySnapshot.loadStaticFromDb(db)
        val result = HyperparameterFitter().fit(h) { freshSnapshot(staticInputs) }
        derivedState.setFit(
            result.config, key,
            FitDiagnostics(result.config, EstimatorConfig(), result.score, result.defaultScore, result.atDefaults, result.sessionCount),
        )
        replayDerivedState()
    }

    /** A fresh scored-replay snapshot: shared static inputs, fresh mutable belief maps per evaluation. */
    private fun freshSnapshot(staticInputs: ReplaySnapshot): ReplaySnapshot =
        ReplaySnapshot(staticInputs.exerciseMuscle, staticInputs.seedCoefficients, staticInputs.exerciseEquipment)
```

- [ ] **Step 5: Route buildPlanner through the active config**

In `buildPlanner`, replace the two default-config constructions. Change line 73 `val projector = MuscleStrengthProjector()` to use the active config, and line 82 `val config = EstimatorConfig()`:

```kotlin
        val config = derivedState.activeConfig()
        val projector = MuscleStrengthProjector(config)
```

Move the `val config = ...` line above its first use (before the `for ((muscle, ids) in muscleIds)` loop at line 76) and delete the later `val config = EstimatorConfig()` at line 82 so `config` is declared once.

- [ ] **Step 6: Pass applicationScope from the app**

In `StochasticStrengthApp.kt`, update the repository construction:

```kotlin
    val workoutRepository: WorkoutRepository by lazy {
        WorkoutRepository(
            database,
            derivedState = derivedStateStore,
            scope = applicationScope,
        )
    }
```

- [ ] **Step 7: Run the wiring test + the full replay-touching suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.WorkoutRepositoryFitWiringTest"`
Expected: PASS.
Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.*"`
Expected: PASS (existing repository/replay tests unaffected — active config defaults to `EstimatorConfig()` until a real fit installs one).

- [ ] **Step 8: Commit**

```bash
jj commit -m "feat(fit): single active-config source + background self-warming fit

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Real-history backtest with fitting

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestHarness.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/backtest/BacktestComparisonTest.kt`
- Test: the modified `BacktestComparisonTest`.

**Interfaces:**
- Consumes: `HyperparameterFitter`, `FitConfig`, `BacktestData` (existing).
- Produces: `BacktestHarness.replayPolicyPrescriptions(data: BacktestData, config: EstimatorConfig = EstimatorConfig()): List<Row>` — the existing method gains a config param threaded into both the `ReplayEngine` stepper and the `PrescriptionPolicy`. New helper `BacktestHarness.fitConfigFor(data: BacktestData): HyperparameterFitter.Result`.

- [ ] **Step 1: Write the failing test (fitting gate)**

Add to `BacktestComparisonTest.kt`:

```kotlin
    @Test fun fittedThetaIsInBoundsAndScoresAtLeastDefaults() {
        val data = BacktestHarness(resourcesDir()).load()
        val result = BacktestHarness(resourcesDir()).fitConfigFor(data)
        // In-bounds: every fitted parameter within ÷4..×4 of its default (spec §8).
        val d = EstimatorConfig(); val c = result.config
        fun within(f: Float, def: Float) = f in def * 0.25f..def * 4f
        assert(within(c.fatiguePerSet, d.fatiguePerSet))
        assert(within(c.processNoisePerDay, d.processNoisePerDay))
        assert(within(c.detrainRatePerWeek, d.detrainRatePerWeek))
        assert(within(c.tauBarbell, d.tauBarbell))
        assert(within(c.repNoiseBucket, d.repNoiseBucket))
        // MAP: fitted never scores below defaults (else fallback fires).
        assert(result.score >= result.defaultScore)
    }
```

(`resourcesDir()` is the existing helper the other tests use; reuse it verbatim. If the helper name differs, grep `BacktestComparisonTest` for how `BacktestHarness` is constructed and match it.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BacktestComparisonTest"`
Expected: FAIL — no `fitConfigFor`.

- [ ] **Step 3: Thread config into replayPolicyPrescriptions and add fitConfigFor**

In `BacktestHarness.kt`, change the signature and internal uses:

```kotlin
    fun replayPolicyPrescriptions(data: BacktestData, config: EstimatorConfig = EstimatorConfig()): List<Row> {
        val snapshot = data.newSnapshot()
        val projector = MuscleStrengthProjector(config)
        val builder = PolicyStateBuilder()
        val exercisesById = data.backup.exercises.associateBy { it.id }
        val rows = mutableListOf<Row>()
        ReplayEngine(SessionProgressionStepper(config = config), config).run(data.history, snapshot) { sessionId, asOf, sets, snap, _ ->
            builder.onSession(asOf, sets, snap)
            val policyState = builder.build(snap.muscleLastObs.toMap())
            for ((muscle, ids) in snap.muscleExerciseIds) {
                val proj = projector.project(snap.currentBeliefs, snap.seedCoefficients, ids, asOf, policyState.muscleLastObs[muscle], equipment = snap.exerciseEquipment)
                val pooledMap = proj.effectiveE1rm.entries.associate { (id, e1rm) ->
                    id to PooledBelief(e1rm, proj.pooledSigma[id] ?: 0f)
                }
                val policy = PrescriptionPolicy(
                    pooled = pooledMap,
                    state = policyState,
                    config = config,
                    progressionEngine = DefaultProgressionEngine,
                    weightUnit = data.weightUnit,
                    nowMs = asOf,
                )
                for (id in ids.sorted()) {
                    val exercise = exercisesById[id] ?: continue
                    val w = policy.prescribe(exercise, REFERENCE_REPS) ?: continue
                    rows += Row(sessionId, id, w)
                }
            }
        }
        return rows
    }

    fun fitConfigFor(data: BacktestData): HyperparameterFitter.Result =
        HyperparameterFitter().fit(data.history) { data.newSnapshot() }
```

Add imports for `SessionProgressionStepper` and `HyperparameterFitter` at the top of the harness.

- [ ] **Step 4: Run to verify the fitting-gate test passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BacktestComparisonTest"`
Expected: `fittedThetaIsInBoundsAndScoresAtLeastDefaults` PASS. The existing comparison-vs-baseline test still runs against the DEFAULT config (unchanged call site) and should stay within its pinned band.

- [ ] **Step 5: Add the fitted-vs-baseline comparison and inspect deltas**

Add a second test that compares fitted-config prescriptions to the frozen baseline, printing per-exercise deltas:

```kotlin
    @Test fun fittedPrescriptionsWithinBandOfBaseline() {
        val harness = BacktestHarness(resourcesDir())
        val data = harness.load()
        val baseline = harness.readBaseline() ?: error("no baseline; run the generator")
        val fitted = harness.replayPolicyPrescriptions(data, harness.fitConfigFor(data).config)
        val byKey = baseline.associate { (it.sessionId to it.exerciseId) to it.weightKg }
        var maxRel = 0.0
        for (r in fitted) {
            val b = byKey[r.sessionId to r.exerciseId] ?: continue
            val rel = kotlin.math.abs(r.weightKg - b) / b.coerceAtLeast(1e-3f)
            if (rel > maxRel) maxRel = rel.toDouble()
        }
        println("BACKTEST fitted-vs-baseline maxRel=$maxRel")
        assert(maxRel <= BAND) { "fitted prescriptions drifted $maxRel > band $BAND — inspect before re-baselining" }
    }
```

Add `private const val BAND = 0.05` if not already present in the test (match the phase-3 band constant; reuse the existing one if defined).

- [ ] **Step 6: Run and adjudicate**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BacktestComparisonTest"`
Expected: If `maxRel <= 0.05`, PASS. **If it exceeds the band, STOP and surface to the user** — a systemic reprice from real per-user fitting is plausible (as in phase 3) and requires explicit approval to re-baseline. Do not silently widen `BAND` or regenerate the baseline.

- [ ] **Step 7: Commit**

```bash
jj commit -m "test(fit): real-history backtest gate with per-user fitting

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: BeliefSimulationTest re-pin with planted-parameter recovery

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefSimulationTest.kt`
- Test: the modified `BeliefSimulationTest`.

**Interfaces:**
- Consumes: `HyperparameterFitter`, the existing synthetic-lifter rig in the test.

- [ ] **Step 1: Confirm the existing pins hold unchanged**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefSimulationTest"`
Expected: PASS. Fitting is not wired into this test's rig, so its pins are untouched. This step is a guard — record that it is green before adding the recovery pin.

- [ ] **Step 2: Add a planted-parameter recovery test**

Append to `BeliefSimulationTest`:

```kotlin
    @Test fun fitting_recoversPlantedFatigueOnLongHistory() {
        // Build a long synthetic history whose true per-set fatigue exceeds the default, then confirm
        // the fitter moves fatiguePerSet toward truth and never scores below defaults.
        val defaults = EstimatorConfig()
        val history = buildPlantedFatigueHistory(nSessions = 40, trueFatigue = 0.08f)
        val result = HyperparameterFitter(defaults).fit(history) { plantedSnapshot() }
        assert(!result.atDefaults) { "expected a real fit on a 40-session history" }
        assert(result.config.fatiguePerSet > defaults.fatiguePerSet) {
            "fitted fatigue ${result.config.fatiguePerSet} did not rise above ${defaults.fatiguePerSet}"
        }
        assert(result.score >= result.defaultScore)
    }
```

Add the two private helpers `buildPlantedFatigueHistory(nSessions, trueFatigue): ReplayHistory` and `plantedSnapshot(): ReplaySnapshot` modeled on the `HyperparameterFitterTest` fixtures (one QUADS barbell exercise, three sets/session, later-set reps falling with `trueFatigue`, `RIR_0_1` feedback, one-day session spacing, seeded belief at 100 kg). Reuse the exact fixture shape from Task 4 so the two tests stay consistent.

- [ ] **Step 3: Run to verify the new pin passes and the old pins still pass**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefSimulationTest"`
Expected: PASS (existing pins + the new recovery pin).

- [ ] **Step 4: Commit**

```bash
jj commit -m "test(fit): BeliefSimulation planted-parameter recovery pin

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 9: Debug panel — fitted-vs-default readout

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailViewModel.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/ui/debug/ExerciseCoefficientDetailScreen.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/ui/debug/FitPanelRowsTest.kt`

**Interfaces:**
- Consumes: `DerivedStateStore.fitDiagnostics()` (Task 5).
- Produces:
  - `data class FitPanelRow(val label: String, val fitted: String, val default: String)`
  - `fun buildFitPanelRows(diag: FitDiagnostics?): List<FitPanelRow>` — top-level function in the debug package; returns empty list when `diag == null`, otherwise one row per fitted parameter plus a score-gain row. Pure and unit-testable.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.ui.debug

import io.github.fowles.stochastic_strength.domain.derived.FitDiagnostics
import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitPanelRowsTest {
    @Test fun nullDiagnosticsYieldsNoRows() {
        assertEquals(emptyList<FitPanelRow>(), buildFitPanelRows(null))
    }

    @Test fun rowsCoverFiveParamsPlusScore() {
        val d = EstimatorConfig()
        val fitted = d.copy(fatiguePerSet = d.fatiguePerSet * 1.5f)
        val diag = FitDiagnostics(fitted, d, score = -100.0, defaultScore = -110.0, atDefaults = false, sessionCount = 30)
        val rows = buildFitPanelRows(diag)
        // five parameters + one score-gain row
        assertEquals(6, rows.size)
        assertTrue(rows.any { it.label.contains("fatigue", ignoreCase = true) })
        assertTrue(rows.any { it.label.contains("score", ignoreCase = true) })
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.debug.FitPanelRowsTest"`
Expected: FAIL — unresolved `buildFitPanelRows` / `FitPanelRow`.

- [ ] **Step 3: Add the pure row builder**

Add to `ExerciseCoefficientDetailViewModel.kt` (top-level, below the existing helper functions):

```kotlin
data class FitPanelRow(val label: String, val fitted: String, val default: String)

/** Pure fitted-vs-default rows for the debug panel (spec §7). Empty when no fit has run. */
fun buildFitPanelRows(diag: FitDiagnostics?): List<FitPanelRow> {
    if (diag == null) return emptyList()
    val f = diag.fitted; val d = diag.defaults
    fun row(label: String, a: Float, b: Float) = FitPanelRow(label, "%.4g".format(a), "%.4g".format(b))
    val gain = diag.score - diag.defaultScore
    return listOf(
        row("Drift rate/wk", f.detrainRatePerWeek, d.detrainRatePerWeek),
        row("Rep-noise bucket", f.repNoiseBucket, d.repNoiseBucket),
        row("Fatigue/set", f.fatiguePerSet, d.fatiguePerSet),
        row("Var growth/day", f.processNoisePerDay, d.processNoisePerDay),
        row("τ barbell", f.tauBarbell, d.tauBarbell),
        FitPanelRow("Score gain (n=${diag.sessionCount})", "%.2f".format(gain), if (diag.atDefaults) "at defaults" else "fitted"),
    )
}
```

Add the import `import io.github.fowles.stochastic_strength.domain.derived.FitDiagnostics`.

- [ ] **Step 4: Run to verify the row builder passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.ui.debug.FitPanelRowsTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Surface diagnostics in the VM state and render the panel**

In the ViewModel, add `fitRows: List<FitPanelRow> = emptyList()` to its UI state data class, and populate it in the load block from `repository.derivedState.snapshot()`… — note `fitDiagnostics()` is on the store itself, so use `app.workoutRepository.derivedState.fitDiagnostics()`:

```kotlin
            fitRows = buildFitPanelRows(repository.derivedState.fitDiagnostics()),
```

In `ExerciseCoefficientDetailScreen.kt`, render a small section when `state.fitRows.isNotEmpty()` above the existing chart content:

```kotlin
        if (state.fitRows.isNotEmpty()) {
            SectionHeader("Per-user fit")
            state.fitRows.forEach { r ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(r.label, style = MaterialTheme.typography.bodySmall)
                    Text("${r.fitted}  (def ${r.default})", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
```

Match the existing imports/composables in that file (`SectionHeader`, `Row`, `Text`, `Modifier`, `Arrangement`, `MaterialTheme`); add any missing import to match the file's style.

- [ ] **Step 6: Build to confirm the UI compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
jj commit -m "feat(fit): debug panel showing fitted-vs-default hyperparameters

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 10: Docs, version bump, full verification, checkboxes

**Files:**
- Create: `docs/adaptation/06-fitting.md`
- Modify: `app/build.gradle` (or `.kts`) — `versionCode`/`versionName` bump.
- Modify: `docs/superpowers/plans/2026-07-10-per-user-map-fitting.md` — tick all checkboxes.
- Modify: `CLAUDE.md` — one sentence noting phase-4 fitting is live (progression section).

- [ ] **Step 1: Write the fitting docs page**

Create `docs/adaptation/06-fitting.md` describing: the five fitted parameters; the half-blended + clean-variance predictive objective; MAP with lognormal priors; the ÷4/×4 bounds, `minFitSessions`, prior sd, and default-fallback guardrails; the background self-warming execution model; and that θ is never persisted. Keep it consistent with the existing `docs/adaptation/` page style (prose, no code dumps). Cross-reference the spec.

- [ ] **Step 2: Bump the app version**

In `app/build.gradle`, increment `versionCode` by 1 and bump `versionName` per convention (grep the current values first; do not guess). No `AppDatabase` version change — there is no migration.

- [ ] **Step 3: Run the full JVM suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (all tests, including the new Task 1–9 tests and the untouched pins).

- [ ] **Step 4: Run lint and assemble**

Run: `./gradlew :app:lint :app:assembleDebug`
Expected: BUILD SUCCESSFUL; no new lint errors introduced by the fit code.

- [ ] **Step 5: Run the instrumented suite (device/emulator)**

Run: `./gradlew :app:connectedAndroidTest`
Expected: PASS. (If no device is attached, note it and defer — do not claim green without running.)

- [ ] **Step 6: Tick every checkbox in this plan and update CLAUDE.md**

Edit this plan file to mark all `- [ ]` as `- [x]`. Add one sentence to `CLAUDE.md`'s progression section noting per-user MAP fitting (phase 4) is live: five hyperparameters fit by predictive scoring during replay, background, θ never persisted.

- [ ] **Step 7: Commit**

```bash
jj commit -m "docs(fit): 06-fitting page, version bump, phase-4 complete

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review notes (author checklist — verify during execution)

- **Spec coverage:** §1 fitted set → Task 4 `applyTheta` + Global Constraints; §2 objective (half-blend + clean var + censored/Gaussian) → Tasks 1–2, 4; §3 fitter (Nelder-Mead, log space, bounds, floor, fallback) → Tasks 3–4; §4 execution model (background, self-warming, keyed, never persisted) → Tasks 5–6; §5 single-source reshaping → Task 6; §6 new pure code → Tasks 1–4; §7 debug panel → Task 9; §8 guardrail constants → Task 4 `FitConfig`; §9 testing (unit, sim re-pin, backtest gate, instrumented) → Tasks 1–9, 10; §10 rollout (docs, version bump, no migration) → Task 10.
- **Type consistency:** `PredictiveScoreAccumulator.accumulate(obs, predMeanLn, predCleanVar)`, `HyperparameterFitter.fit(history, newSnapshot)`, `HyperparameterFitter.Result(config, score, defaultScore, atDefaults, sessionCount)`, `DerivedStateStore.setFit(config, key, diagnostics)`, `FitKey(sessionCount, latestEndTime)`, `FitDiagnostics(fitted, defaults, score, defaultScore, atDefaults, sessionCount)` used identically across tasks.
- **Adjudication gate:** Task 7 Step 6 halts for user approval on any backtest band breach (re-baseline is user-owned, as in phases 2–3).
- **Known risk to watch (from phase 3 memory):** "eager borrowing" of cold isolations — the fit's τScale can move this; the backtest band is the guard.
