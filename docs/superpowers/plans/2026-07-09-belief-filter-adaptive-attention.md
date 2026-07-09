# Belief Filter — Adaptive Attention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the per-exercise Kalman belief actually follow clear, consistent evidence (e.g. a run of failed heavy sets ending in a clean RIR_0_1 set) instead of discounting it, so the prod Bulgarian-Split-Squat (BSS) prescription returns to the user-demonstrated ~20 lb rather than the current 30 lb — with no post-hoc snap-down heuristic.

**Architecture:** Two coupled, native-Kalman fixes in `BeliefUpdater`, driven by config constants. (A) An **observation model-uncertainty floor**: every set carries irreducible uncertainty about *fresh* 1RM, so a single set can no longer drive σ to the floor and deafen the filter. (B) **Innovation-driven adaptive variance inflation**: when the standardized innovation sequence is large *and* persistently one-signed (the belief is wrong, not the observation noisy), the prior variance is re-inflated before the gain is computed, so the filter re-opens and the clean signal lands. Both are symmetric and data-driven; they re-open upward on new PRs just as readily as downward on new failures.

**Tech Stack:** Kotlin, JVM unit tests (JUnit4), Gradle. Pure domain code under `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/`.

## Global Constraints

- **No Room migration.** `ExerciseBelief` is in-memory derived state in `DerivedStateStore`, rebuilt from the session log by `WorkoutRepository.replayDerivedState()`. It is never persisted to Room and never serialized in backups. Adding a field with a default is safe and requires no schema/version bump.
- **All tuning constants live in `EstimatorConfig`.** No magic numbers in `BeliefUpdater` / `SetObservation`.
- **Replay must stay deterministic and idempotent.** No wall-clock, no randomness; adaptation state is a pure function of the folded observation sequence and lives on `ExerciseBelief`.
- **Pinned tests are the gates.** `BeliefUpdaterFoldTest` (exact fold math), `BeliefSimulationTest` (pins `uncertaintyZ`, `overloadDelta`, `poolObsVar`), `ProdBssPrescriptionTest` (real-history end-to-end). Constant *values* are measured-and-pinned during execution, following the existing repo pattern ("Measured at Task 7", "re-pinned 2026-07-08"). Mechanism code is fully specified here; three constants (`obsModelSd`, `adaptRunThreshold`, `adaptInflationPerExcess`) are a tuning surface fit to satisfy the ProdBss + BeliefSimulation gates, with starting values given.
- **Test commands:**
  - Single class: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefUpdaterFoldTest"`
  - Full unit suite: `./gradlew :app:testDebugUnitTest`
  - Instrumented (emulator up): `./gradlew :app:connectedAndroidTest`

## Background — why the filter currently discounts the signal

Replaying the prod BSS history (id 55, sessions 16 & 18) through the live phase-2 path:

- Set 1 alone (a single 2-rep failure) has observation sd ≈ 0.025 and drives σ from the 0.25 seed to 0.024 **in one fold** — the model claims a single failed set pins fresh 1RM to ±2.5%. σ then sits at the 0.02 floor for the rest of the session.
- The clean, target-relevant set (session 18 set 3: 20 lb × 10, RIR_0_1) arrives with prior σ = 0.02, observation sd ≈ 0.042. Kalman gain k = σ²/(σ²+s²) = 0.185, so a **−29% innovation** moves the mean only ~5% (25.1→23.9 kg). Normalized innovation squared NIS = ν²/(σ²+s²) ≈ **40** (a calibrated filter expects ≈ 1): the likelihood is screaming the prior variance was understated ~40×, and a static filter has no way to hear it.
- Net: fresh-1RM belief 23.9 kg → policy target 22.65 kg → **30 lb**. Projector pooling (κ=0), ceiling (inert), HURT (1.0), z/δ (cancel) are all neutral; the entire error is the filter going deaf.

Fix A stops the one-fold σ collapse; Fix B lets a *consistent* run of surprises re-open σ.

---

## File Structure

- **Modify** `domain/progression/EstimatorConfig.kt` — add `obsModelSd`, `adaptRunThreshold`, `adaptInflationPerExcess`, `adaptRunDecay`.
- **Modify** `domain/progression/SetObservation.kt` — floor observation noise with `obsModelSd` (Fix A).
- **Modify** `domain/progression/ExerciseBelief.kt` — add `innovationRun` field (Fix B state carrier); reset in `seed()`/`override()`.
- **Modify** `domain/progression/BeliefUpdater.kt` — extract `kalmanStep`, add `adaptPrior`, wire both folds (Fix B).
- **Modify (pins)** `domain/progression/BeliefSimulationTest.kt`, `domain/ProdBssPrescriptionTest.kt`.
- **Add** `domain/progression/BeliefAdaptationTest.kt` — behavioral unit tests for Fix A + Fix B.
- **Modify (docs)** `docs/adaptation/03-exercise-estimates.md`, `CLAUDE.md` (progression section).

---

## Task 1: Observation model-uncertainty floor (Fix A)

Stops a single confident set from collapsing σ to the floor. Every observation gets an irreducible fresh-1RM uncertainty combined in quadrature.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/EstimatorConfig.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SetObservation.kt:36`
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefAdaptationTest.kt`

**Interfaces:**
- Produces: `EstimatorConfig.obsModelSd: Float` (default `0.08f`); `SetObservation.from(...)` returns observations whose `noiseSd >= obsModelSd`.

- [ ] **Step 1: Write the failing test** — create `BeliefAdaptationTest.kt`

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertTrue
import org.junit.Test

class BeliefAdaptationTest {
    private val config = EstimatorConfig()
    private val updater = BeliefUpdater(config)

    private fun tooHard(w: Float, reps: Int, got: Int) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = w,
            targetReps = reps, actualReps = got, feedback = SetFeedback.TOO_HARD)

    @Test
    fun singleSetObservationCarriesModelUncertaintyFloor() {
        // A lone 2-rep failure must NOT claim ±2.5% knowledge of fresh 1RM.
        val obs = SetObservation.from(tooHard(w = 25f, reps = 10, got = 2), fatigueRank = 1, config = config)!!
        assertTrue("obs noise must be floored by obsModelSd (got ${obs.noiseSd})",
            obs.noiseSd >= config.obsModelSd)
    }

    @Test
    fun oneConfidentFailureDoesNotCollapseSigmaToFloor() {
        // Fold one tight failure from the seed prior; σ must stay well above the floor so the
        // filter can still hear later sets in the same session.
        val seed = ExerciseBelief.seed(e1rm = 38f, at = 0L, config = config)
        val obs = SetObservation.from(tooHard(w = 29.5f, reps = 10, got = 2), fatigueRank = 1, config = config)!!
        val after = updater.foldGaussian(seed, obs.gaussianLn!!, obs.noiseSd, at = 0L, muscleLastObs = null)
        assertTrue("σ must not collapse to the floor after one fold (σ=${after.sigma})",
            after.sigma > 0.06f)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefAdaptationTest"`
Expected: FAIL — `obsModelSd` unresolved (compile error), or (once added) σ collapses below 0.06.

- [ ] **Step 3: Add `obsModelSd` to `EstimatorConfig`**

Insert after the `repNoiseRel` field (near the report-noise bases block):

```kotlin
    /**
     * Irreducible per-observation uncertainty about FRESH 1RM (log-units, ≈ ±8%). Combined in
     * quadrature with the rep-derived noise so a single set — especially a low-rep failure where
     * the 1RM curve is flat and the rep-noise term is tiny — cannot drive σ to the floor and
     * deafen the filter. This is the "one session can't tell you fresh 1RM to ±2.5%" floor.
     */
    val obsModelSd: Float = 0.08f,
```

- [ ] **Step 4: Apply the floor in `SetObservation.noise`**

In `SetObservation.from`, replace the `noise` helper (line ~36):

```kotlin
            fun noise(base: Float): Float {
                val repSd = lambda * sqrt(base * base + (config.repNoiseRel * r) * (config.repNoiseRel * r))
                return sqrt(repSd * repSd + config.obsModelSd * config.obsModelSd)
            }
```

- [ ] **Step 5: Run the new test + the exact-math fold test**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefAdaptationTest" --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefUpdaterFoldTest"`
Expected: `BeliefAdaptationTest` PASS. `BeliefUpdaterFoldTest` PASS (it calls folds with explicit `noiseSd`, not via `SetObservation`, so it is unaffected).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/EstimatorConfig.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/SetObservation.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefAdaptationTest.kt
git commit -m "belief: add observation model-uncertainty floor (obsModelSd) so one set can't deafen the filter"
```

---

## Task 2: Innovation-run state on `ExerciseBelief` (Fix B, part 1)

Adds the augmented-filter state that tracks a signed run of consistent surprises. No behavior change yet — this task only threads the field so Task 3 can use it.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseBelief.kt`

**Interfaces:**
- Produces: `ExerciseBelief.innovationRun: Float` (default `0f`), reset to `0f` by `seed()` and `override()`.

- [ ] **Step 1: Add the field with a default**

Replace the `ExerciseBelief` data class declaration:

```kotlin
data class ExerciseBelief(
    val mu: Float,
    val sigma2: Float,
    val updatedAt: Long,
    /**
     * Augmented adaptive-filter state (spec §2 / adaptive-attention): a signed running sum of
     * standardized innovations while they stay one-signed. A large |innovationRun| means the belief
     * has been consistently surprised in one direction — the prior variance is understated — and the
     * fold re-inflates it (see [BeliefUpdater.adaptPrior]). Reset to 0 by seed/override. Not
     * persisted (in-memory derived state, rebuilt by replay).
     */
    val innovationRun: Float = 0f,
) {
```

- [ ] **Step 2: Confirm `seed`/`override` reset the run**

They construct `ExerciseBelief(mu = ..., sigma2 = ..., updatedAt = at)`, so `innovationRun` defaults to `0f`. No change needed — verify by reading the companion object.

- [ ] **Step 3: Compile**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefUpdaterFoldTest"`
Expected: PASS (field is additive, defaulted; nothing reads it yet).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseBelief.kt
git commit -m "belief: add innovationRun augmented-filter state to ExerciseBelief (no behavior yet)"
```

---

## Task 3: Innovation-driven adaptive variance inflation (Fix B, part 2)

The core mechanism. Before computing the gain, measure the standardized innovation; accumulate it into a signed run while it stays one-signed; once the run exceeds a threshold, inflate the prior variance so a *consistent* surprise re-opens the belief. A single surprise (run below threshold) is left alone — that's what separates "the belief is wrong" from "one noisy set."

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/EstimatorConfig.kt`
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/BeliefUpdater.kt`
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefAdaptationTest.kt`

**Interfaces:**
- Consumes: `ExerciseBelief.innovationRun`, `EstimatorConfig.obsModelSd`.
- Produces: `EstimatorConfig.adaptRunThreshold` (`3.5f`), `adaptInflationPerExcess` (`1.0f`), `adaptRunDecay` (`0.5f`); folds now write `innovationRun` on the returned belief and inflate the prior when the run is large.

- [ ] **Step 1: Write the failing tests** — append to `BeliefAdaptationTest.kt`

```kotlin
    // --- Fix B: adaptive attention ---

    /** A single surprising observation (run below threshold) must NOT yank a tight belief. */
    @Test
    fun loneSurpriseDoesNotYankTightBelief() {
        val tight = ExerciseBelief(mu = 3.6f, sigma2 = 0.02f * 0.02f, updatedAt = 0L)
        val after = updater.foldGaussian(tight, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null)
        assertTrue("one surprise should barely move a tight belief (μ=${after.mu})", after.mu > 3.5f)
    }

    /** A consistent one-signed run of surprises must re-open σ and let the belief track the data. */
    @Test
    fun consistentRunOfSurprisesReopensAndTracks() {
        var b = ExerciseBelief(mu = 3.6f, sigma2 = 0.02f * 0.02f, updatedAt = 0L)
        repeat(5) { b = updater.foldGaussian(b, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null) }
        assertTrue("consistent down-run must drag the belief toward the data (μ=${b.mu})", b.mu < 3.35f)
        assertTrue("innovationRun must have accumulated downward (${b.innovationRun})", b.innovationRun < -config.adaptRunThreshold)
    }

    /** Turning adaptation off (threshold huge) leaves the belief stuck — proves the run is doing the work. */
    @Test
    fun withoutAdaptationTheBeliefStaysStuck() {
        val noAdapt = EstimatorConfig(adaptRunThreshold = 1e6f)
        val u = BeliefUpdater(noAdapt)
        var b = ExerciseBelief(mu = 3.6f, sigma2 = 0.02f * 0.02f, updatedAt = 0L)
        repeat(5) { b = u.foldGaussian(b, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null) }
        assertTrue("without adaptation a tight belief cannot follow (μ=${b.mu})", b.mu > 3.5f)
    }

    /** A direction flip restarts the run rather than compounding it. */
    @Test
    fun signFlipRestartsRun() {
        var b = ExerciseBelief(mu = 3.6f, sigma2 = 0.02f * 0.02f, updatedAt = 0L)
        repeat(3) { b = updater.foldGaussian(b, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null) }
        assertTrue("run is negative after a down-sequence", b.innovationRun < 0f)
        val flipped = updater.foldGaussian(b, obsLnE1rm = b.mu + 0.5f, noiseSd = 0.05f, at = 0L, muscleLastObs = null)
        assertTrue("an up-surprise restarts the run positive (${flipped.innovationRun})", flipped.innovationRun > 0f)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefAdaptationTest"`
Expected: FAIL — `adaptRunThreshold` unresolved; `consistentRunOfSurprisesReopensAndTracks` and `signFlipRestartsRun` fail (no adaptation).

- [ ] **Step 3: Add the adaptation constants to `EstimatorConfig`**

Insert after `obsModelSd`:

```kotlin
    /**
     * Adaptive attention (innovation-covariance matching). The filter re-inflates its prior variance
     * only once the standardized-innovation run — a signed sum of consecutive one-signed surprises —
     * exceeds [adaptRunThreshold] (in std units). Below it a lone surprise is treated as noise, so
     * one bad set cannot yank the belief; above it a *consistent* run (the belief is wrong, not the
     * observation noisy) re-opens σ so the clear signal lands. Symmetric up/down. Tuning surface fit
     * to the ProdBss + BeliefSimulation gates; pinned by BeliefSimulationTest.
     */
    val adaptRunThreshold: Float = 3.5f,
    /** Prior-variance multiplier added per (run-excess-over-threshold)², i.e. inflate = 1 + g·excess². */
    val adaptInflationPerExcess: Float = 1.0f,
    /** Decay applied to the run when an observation lands on-belief (no surprise), so it fades toward 0. */
    val adaptRunDecay: Float = 0.5f,
```

- [ ] **Step 4: Refactor `BeliefUpdater` — extract `kalmanStep`, add `adaptPrior`, wire both folds**

Add these imports at the top of `BeliefUpdater.kt` (alongside `import kotlin.math.sqrt`):

```kotlin
import kotlin.math.abs
import kotlin.math.sign
```

Replace `foldGaussian` and `foldCensored` (lines ~42–96) with:

```kotlin
    /** Pure Kalman measurement update — no aging, no adaptation; carries [run] onto the result. */
    private fun kalmanStep(prior: ExerciseBelief, obsLnE1rm: Float, s2: Float, run: Float, at: Long): ExerciseBelief {
        val k = prior.sigma2 / (prior.sigma2 + s2)
        return ExerciseBelief(
            mu = prior.mu + k * (obsLnE1rm - prior.mu),
            sigma2 = clampVar((1f - k) * prior.sigma2),
            updatedAt = at,
            innovationRun = run,
        )
    }

    /**
     * Adaptive-attention step (spec §2). Measures the standardized innovation of [obsLoc] against the
     * aged prior with predicted variance [predVar], accumulates it into a signed run while one-signed,
     * and — once the run passes [EstimatorConfig.adaptRunThreshold] — inflates the prior variance so a
     * *consistent* surprise re-opens the belief. Returns the (possibly inflated) prior with its updated
     * run. A lone or direction-flipping surprise leaves σ² essentially untouched.
     */
    private fun adaptPrior(aged: ExerciseBelief, obsLoc: Float, predVar: Float): ExerciseBelief {
        val zstd = (obsLoc - aged.mu) / sqrt(predVar)
        val prev = aged.innovationRun
        val run = when {
            abs(zstd) < 1e-3f -> prev * config.adaptRunDecay          // on-belief obs: fade the run
            prev == 0f || sign(prev) == sign(zstd) -> prev + zstd     // consistent direction: accumulate
            else -> zstd                                              // direction flipped: restart
        }
        val excess = (abs(run) - config.adaptRunThreshold).coerceAtLeast(0f)
        val inflate = 1f + config.adaptInflationPerExcess * excess * excess
        return aged.copy(sigma2 = clampVar(aged.sigma2 * inflate), innovationRun = run)
    }

    fun foldGaussian(
        prior: ExerciseBelief,
        obsLnE1rm: Float,
        noiseSd: Float,
        at: Long,
        muscleLastObs: Long?,
    ): ExerciseBelief {
        val aged0 = age(prior, at, muscleLastObs)
        val s2 = noiseSd * noiseSd
        val prior1 = adaptPrior(aged0, obsLoc = obsLnE1rm, predVar = aged0.sigma2 + s2)
        return kalmanStep(prior1, obsLnE1rm, s2, prior1.innovationRun, at)
    }

    /**
     * Fold one censored observation z = x + s·ε constrained to [lowerLn, upperLn] (either side
     * may be null = unbounded). Truncated-Gaussian moment match (spec §2) — exact for this model.
     * Adaptation uses the violated bound as the observation location for the innovation.
     */
    fun foldCensored(
        prior: ExerciseBelief,
        lowerLn: Float?,
        upperLn: Float?,
        noiseSd: Float,
        at: Long,
        muscleLastObs: Long?,
    ): ExerciseBelief {
        val aged0 = age(prior, at, muscleLastObs)
        // Innovation location: the bound the prior violates (if any); otherwise the prior mean → no surprise.
        val obsLoc = when {
            upperLn != null && aged0.mu > upperLn -> upperLn
            lowerLn != null && aged0.mu < lowerLn -> lowerLn
            else -> aged0.mu
        }
        val aged = adaptPrior(aged0, obsLoc, predVar = aged0.sigma2 + noiseSd * noiseSd)
        val run = aged.innovationRun
        val st2 = aged.sigma2 + noiseSd * noiseSd
        val st = sqrt(st2)
        val alpha = (if (lowerLn != null) (lowerLn - aged.mu) / st else -CLAMP).coerceIn(-CLAMP, CLAMP)
        val beta = (if (upperLn != null) (upperLn - aged.mu) / st else CLAMP).coerceIn(-CLAMP, CLAMP)
        val z = NormalCdf.cdf(beta) - NormalCdf.cdf(alpha)
        if (z < MIN_MASS) {
            // Prior mass misses the window entirely: treat as a Gaussian obs at the violated bound.
            val bound = when {
                upperLn != null && aged.mu >= upperLn -> upperLn
                lowerLn != null && aged.mu <= lowerLn -> lowerLn
                else -> lowerLn ?: upperLn ?: aged.mu
            }
            return kalmanStep(aged, bound, noiseSd * noiseSd, run, at)
        }
        val phiA = NormalCdf.pdf(alpha)
        val phiB = NormalCdf.pdf(beta)
        val mz = aged.mu + st * (phiA - phiB) / z
        val vz = st2 * (1f + (alpha * phiA - beta * phiB) / z - ((phiA - phiB) / z).let { it * it })
        val k = aged.sigma2 / st2
        return ExerciseBelief(
            mu = aged.mu + k * (mz - aged.mu),
            sigma2 = clampVar(aged.sigma2 - k * k * (st2 - vz)),
            updatedAt = at,
            innovationRun = run,
        )
    }
```

> Note: the `z < MIN_MASS` fallback now calls the private `kalmanStep` (no re-aging, no double-adaptation) — the prior is already aged and adapted at this point.

- [ ] **Step 5: Run the adaptation tests + exact-math fold tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefAdaptationTest" --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefUpdaterFoldTest"`
Expected: `BeliefAdaptationTest` all PASS. `BeliefUpdaterFoldTest` PASS — its cases use priors far from the floor and single folds, so `adaptPrior` produces `run` below threshold → `inflate == 1f` → identical moments (the numeric-integration assertions have 5% tolerance and are unaffected). If any exact-math case now fails, STOP and use `superpowers:systematic-debugging` — do not loosen the tolerance.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/EstimatorConfig.kt \
        app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/BeliefUpdater.kt \
        app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefAdaptationTest.kt
git commit -m "belief: innovation-driven adaptive variance inflation (consistent surprise re-opens the filter)"
```

---

## Task 4: Re-baseline the belief simulation pins

Fix A raises observation noise and Fix B changes how surprises propagate; the synthetic-lifter pins (`uncertaintyZ`, `overloadDelta`, `poolObsVar`) may shift slightly. The synthetic lifter's fatigue equals `config.fatiguePerSet` and its observation model matches truth, so innovations stay small and `adaptPrior` rarely triggers — expect small or no movement. Re-pin from measurement, following the repo's established measure-and-pin pattern.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefSimulationTest.kt`

**Interfaces:**
- Consumes: the new `EstimatorConfig` defaults.

- [ ] **Step 1: Run the simulation as-is and read the failures**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefSimulationTest"`
Expected: either PASS (nothing to do — skip to Step 4) or FAIL with concrete measured deltas (the assertions print measured vs. pinned).

- [ ] **Step 2: If any pin moved, update the pinned assertion values**

For each failing assertion, replace the pinned expected value with the measured value the failure message reports. Do **not** change the tolerance/BAND. Only the three tuning-surface constants (`uncertaintyZ`, `overloadDelta`, `poolObsVar`) or their pinned targets are eligible; if any *other* constant appears to need changing to pass, STOP — that indicates a mechanism bug, use `superpowers:systematic-debugging`.

- [ ] **Step 3: Re-run to green**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefSimulationTest"`
Expected: PASS.

- [ ] **Step 4: Add an NIS calibration guard** — append to `BeliefSimulationTest.kt`

A well-tuned filter has mean normalized-innovation-squared ≈ 1; a value ≫ 1 means it is still overconfident and will discount signals. Add a test that folds the synthetic lifter's sessions and asserts the mean NIS over Gaussian folds sits in a sane band. Use the existing helpers in the class (`achievableReps`, the per-session fold loop) as a template; if extracting NIS is awkward, gate it as a coarse assertion:

```kotlin
    @Test
    fun filterIsNotChronicallyOverconfident() {
        // Mean normalized innovation squared over the synthetic lifter should be O(1), not O(10+).
        // A large value means the filter claims more precision than the data supports (the prod-BSS
        // failure mode). Band is deliberately wide — this is a smoke guard, not a tight pin.
        val meanNis = meanNormalizedInnovationSquaredOverSyntheticLifter()  // implement using existing sim scaffolding
        assertTrue("filter overconfident: mean NIS=$meanNis (want < 5)", meanNis < 5f)
        assertTrue("filter sluggish: mean NIS=$meanNis (want > 0.2)", meanNis > 0.2f)
    }
```

Implement `meanNormalizedInnovationSquaredOverSyntheticLifter()` by reusing the class's existing session-generation loop: for each Gaussian fold, accumulate `(obsLn - agedMu)² / (agedSigma2 + noiseSd²)` and average. If the existing scaffolding does not expose aged μ/σ cleanly, compute it inline via `updater.age(...)` before each fold (the class already holds `updater`).

- [ ] **Step 5: Run and commit**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.BeliefSimulationTest"`
Expected: PASS.

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefSimulationTest.kt
git commit -m "belief: re-baseline simulation pins after adaptive-attention; add NIS calibration guard"
```

---

## Task 5: Re-pin ProdBss to the demonstrated ~20 lb and fix the comment

This is the acceptance criterion for the user's intent: respect the user's directly-demonstrated set-3 capacity (20 lb × 10, RIR_0_1) until *new* successful data lifts it. Older/fresh heavy-single failures must no longer override it.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProdBssPrescriptionTest.kt`

- [ ] **Step 1: Run both ProdBss cases and read the new number**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProdBssPrescriptionTest"`
Expected: FAIL — both `reportBssPrescription` (projector-only) and `policyPathSafetyBounds` (policy path) now produce a lower weight than the pinned 30 lb. Record the measured lbs from each failure message.

- [ ] **Step 2: Confirm the target is met**

The measured policy-path prescription MUST be `<= WeightUnit.LBS.toKg(20f)` within grid tolerance (i.e. 20 lb, or the nearest grid point at/below the demonstrated set-3 capacity). If it is materially above 20 lb (e.g. 25 lb+), the fixes are under-powered: STOP and tune the Task 3 starting constants toward stronger attention — raise `adaptInflationPerExcess` and/or lower `adaptRunThreshold` — re-running Task 4 (sim + NIS) after each change to keep those gates green. Iterate constants, not mechanism. If ≥3 constant settings fail to reconcile ProdBss with the sim gate, STOP and escalate (this indicates the mechanism needs rethinking — invoke `superpowers:systematic-debugging`, do not keep nudging).

- [ ] **Step 3: Update the two assertions to the demonstrated capacity**

In `policyPathSafetyBounds`, replace the pinned block (currently 30 lb) with the measured value and an honest comment:

```kotlin
        // PINNED to the user's directly-demonstrated set-3 capacity (session 18 set 3: 20 lb × 10,
        // RIR_0_1), adjudicated 2026-07-09. The prescription targets the THIRD set at RIR 0–1, and
        // 20 lb is the only clean 10-rep RIR_0_1 set in the history; every ≥35 lb attempt at 10 reps
        // was TOO_HARD. The earlier 30 lb came from a fatigue-blind fresh-1RM belief that the static
        // filter could not walk back; the adaptive-attention fixes (obsModelSd floor + innovation-run
        // inflation) let the consistent down-run of failures ending in the clean RIR_0_1 set re-open
        // the belief so it lands on the demonstrated capacity. Rises again only on NEW successful data.
        assertTrue("BSS policy prescription must not exceed the demonstrated set-3 capacity (20 lb)",
            weightKg <= WeightUnit.LBS.toKg(20f) + 1e-3f)
        assertEquals("BSS policy prescription pinned at demonstrated 20 lb (got ${weightKg / WeightUnit.LBS.toKg(1f)} lbs)",
            WeightUnit.LBS.toKg(20f), weightKg, 1e-3f)
```

In `reportBssPrescription`, replace the projector-only pin (currently 30 lb) with the measured projector-only value and update its comment to note it is the pre-policy belief-only figure (drop the stale "exactly 30 lb" text). If the measured projector-only value is not exactly 20 lb, pin the measured value — this case documents the belief before z/δ/fatigue, not the final prescription.

- [ ] **Step 4: Run to green**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProdBssPrescriptionTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/io/github/fowles/stochastic_strength/domain/ProdBssPrescriptionTest.kt
git commit -m "belief: re-pin ProdBss to demonstrated 20 lb — adaptive attention respects the user's set-3 evidence"
```

---

## Task 6: Full suite, instrumented run, and living docs

**Files:**
- Modify: `docs/adaptation/03-exercise-estimates.md`
- Modify: `CLAUDE.md` (Progression system section, item 2)

- [ ] **Step 1: Full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS. If a *different* pinned test fails (e.g. a chart/backtest test that consumed the old belief trajectory), read it, decide whether the new behavior is correct, and re-pin the *measured* value with a one-line justification. Do not weaken a test to hide a regression — if the change looks wrong, use `superpowers:systematic-debugging`.

- [ ] **Step 2: Instrumented suite (emulator up)**

Run: `./gradlew :app:connectedAndroidTest`
Expected: PASS (per repo norm, ~78 instrumented green). If the emulator is down, note it and proceed; flag for the user.

- [ ] **Step 3: Update `docs/adaptation/03-exercise-estimates.md`**

Add a subsection documenting: (a) the observation model-uncertainty floor (`obsModelSd`) and why a single low-rep failure must not collapse σ; (b) innovation-driven adaptive inflation (`adaptRunThreshold`, `adaptInflationPerExcess`, `adaptRunDecay`) as native adaptive-Kalman / innovation-covariance matching — a *consistent* one-signed run re-opens σ, a lone surprise does not; (c) the NIS ≈ 1 calibration target. Match the file's existing prose style and cross-reference the prescription-policy doc.

- [ ] **Step 4: Update `CLAUDE.md` Progression system item 2**

Amend the sentence describing the fold ("Each fold ages the prior first: variance grows by q ...") to note that after aging, the fold (i) floors observation noise by `obsModelSd`, and (ii) applies innovation-run adaptive variance inflation so a consistent surprise re-opens the belief; Gaussian updates use a Kalman step, censored updates use truncated-Gaussian moment matching. Keep it to one or two sentences consistent with the surrounding density.

- [ ] **Step 5: Commit**

```bash
git add docs/adaptation/03-exercise-estimates.md CLAUDE.md
git commit -m "docs: adaptive-attention belief filter (obsModelSd floor + innovation-run inflation)"
```

---

## Self-Review Notes (for the executor)

- **Coverage:** Fix A (Task 1), Fix B state (Task 2) + mechanism (Task 3), sim re-baseline + NIS guard (Task 4), ProdBss acceptance (Task 5), regression + docs (Task 6). Every mechanism claim in the Background maps to a task.
- **Type consistency:** `innovationRun: Float` is defined in Task 2 and consumed in Task 3; `obsModelSd`, `adaptRunThreshold`, `adaptInflationPerExcess`, `adaptRunDecay` all live in `EstimatorConfig`. `kalmanStep`/`adaptPrior` are private to `BeliefUpdater`. `SetObservation.from` signature is unchanged (noise floored internally).
- **The three tuning constants** (`obsModelSd=0.08`, `adaptRunThreshold=3.5`, `adaptInflationPerExcess=1.0`) are starting values. Task 5 Step 2 is the explicit fit loop against the ProdBss + BeliefSimulation gates. This mirrors the repo's existing "measured-and-pinned" convention rather than pretending the values are known a priori.
- **Non-goals:** no snap-down heuristic; no per-exercise φ (that is the separate fatigue-axis / phase-3-adjacent work); no Room migration.
