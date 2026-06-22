# Concordance-Attribution Controller Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the progression controller attribute a session reading by *concordance* (shared movement → baseline, exercise-specific deviation → coefficient) at a speed set by *confidence*, so a single too-heavy lift drops only its own coefficient while a wrong baseline (revealed by many lifts bracketing low) self-corrects into the baseline.

**Architecture:** Three changes to `RollingConservingProgressionController`, all driven by a shared Huber M-estimator (`RobustCenter`): (1) a robust common mode that rejects a lone outlier within a session; (2) confidence-scaled speed (EMA bypass + baseline-clamp relaxation) so a high-confidence bracket moves fast; (3) a product-preserving geomean reclaimer that pulls *collective* coefficient drift into the baseline across sessions. The `ProgressionController` interface gains one input field (`seedCoefficients`); `SessionSignalExtractor` is unchanged.

**Tech Stack:** Kotlin, JVM unit tests (JUnit4), Gradle. No DB/Room changes. No new dependencies.

## Global Constraints

- Package: `io.github.fowles.stochastic_strength.domain` (verbatim).
- Unit tests run on the JVM: `./gradlew :app:testDebugUnitTest`.
- Run a single class: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.<Class>"`.
- No DI framework; `ProgressionStepInput` is a plain data class — new fields MUST have defaults so existing call sites compile.
- The controller stores baselines at full precision (raw kg); it never grid-rounds. Rounding happens only at `WorkoutPlanner` weight selection.
- `ProgressionControllerSimulationTest`'s existing assertions are a **hard gate** and may not be loosened: `coefInflation ∈ 0.97..1.03` under growth, `lastSetRir ∈ 0.0..1.5`, `failRate ≤ 0.20`, `convSessions ≤ 12`, `jitter ≤ 1.5`, static `trainedEndErr ≤ 8.0`. If a controller change breaks one, tune the new knobs (Huber `δ` up toward the mean, `reclaimRate` down) — do not relax the assert.
- Commit after every task with `jj describe` (repo uses jj/Jujutsu, colocated git). End commit messages with `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`.

---

### Task 1: `RobustCenter` Huber M-estimator (shared primitive)

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/RobustCenter.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/RobustCenterTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `object RobustCenter { fun of(values: List<Float>, weights: List<Float>, delta: Float, iterations: Int = 3): Float }` — weighted robust location; returns `0f` for empty input; reduces to the weighted mean when all residuals are within `delta`.

- [ ] **Step 1: Write the failing test**

```kotlin
package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.ln

class RobustCenterTest {

    private val delta = ln(1.10f) // ~0.0953

    @Test fun empty_returnsZero() {
        assertEquals(0f, RobustCenter.of(emptyList(), emptyList(), delta), 1e-6f)
    }

    @Test fun inBandValues_equalWeightedMean() {
        // All within delta of each other -> Huber weights all 1 -> plain weighted mean.
        val v = listOf(0.00f, 0.02f, -0.03f)
        val w = listOf(1f, 1f, 1f)
        assertEquals((0.00f + 0.02f - 0.03f) / 3f, RobustCenter.of(v, w, delta), 1e-4f)
    }

    @Test fun loneOutlier_isRejected_centerStaysNearCluster() {
        // Two calm points near 0, one violent -0.6 -> robust center near the cluster, not -0.2.
        val v = listOf(-0.60f, 0.03f, 0.00f)
        val w = listOf(1f, 1f, 1f)
        val c = RobustCenter.of(v, w, delta, iterations = 5)
        assertEquals(0.0f, c, 0.05f) // far from the -0.20 plain mean
    }

    @Test fun unanimousShift_isFollowed() {
        // All agree on ~-0.55 -> that IS the consensus -> center tracks it.
        val v = listOf(-0.55f, -0.60f, -0.50f)
        val w = listOf(1f, 1f, 1f)
        assertEquals(-0.55f, RobustCenter.of(v, w, delta, iterations = 5), 0.03f)
    }

    @Test fun weightsBias_towardHeavierPoints() {
        val v = listOf(0.10f, -0.10f)
        val w = listOf(3f, 1f)
        assertEquals((3f * 0.10f - 1f * 0.10f) / 4f, RobustCenter.of(v, w, delta), 1e-4f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.RobustCenterTest"`
Expected: FAIL — `RobustCenter` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package io.github.fowles.stochastic_strength.domain

import kotlin.math.abs

/**
 * Weighted Huber M-estimator of location. Used both to estimate a muscle's shared (common-mode)
 * innovation within a session and to estimate collective coefficient drift across sessions, so that
 * a lone violent outlier is down-weighted while a genuine consensus is followed.
 *
 * Reduces exactly to the weighted mean when every residual is within [delta]; otherwise iteratively
 * reweights points by `min(1, delta/|residual|)`.
 */
object RobustCenter {
    fun of(values: List<Float>, weights: List<Float>, delta: Float, iterations: Int = 3): Float {
        if (values.isEmpty()) return 0f
        require(values.size == weights.size) { "values/weights size mismatch" }
        var wsum = 0.0
        var seedNum = 0.0
        for (i in values.indices) {
            wsum += weights[i].toDouble()
            seedNum += values[i].toDouble() * weights[i]
        }
        if (wsum <= 0.0) return 0f
        var m = seedNum / wsum
        repeat(iterations) {
            var num = 0.0
            var den = 0.0
            for (i in values.indices) {
                val r = abs(values[i] - m)
                val psi = if (r <= delta || r == 0f) 1.0 else (delta / r).toDouble()
                val w = weights[i].toDouble() * psi
                num += w * values[i]
                den += w
            }
            if (den <= 0.0) return m.toFloat()
            m = num / den
        }
        return m.toFloat()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.RobustCenterTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
jj describe -m "feat: RobustCenter Huber M-estimator for concordance attribution

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Thread `seedCoefficients` into the controller input (no behavior change)

Adds the seed-coefficient map the reclaimer (Task 5) needs, wired from `ReplaySnapshot.seedCoefficients`. Defaulted to empty so the controller behaves identically until Task 5 uses it.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt:20-30` (the `ProgressionStepInput` data class)
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/WorkoutRepository.kt:111-121` (the `controller.step(...)` call)

**Interfaces:**
- Consumes: `ReplaySnapshot.seedCoefficients: Map<Long, Float>` (already exists).
- Produces: `ProgressionStepInput.seedCoefficients: Map<Long, Float>` (new field, default `emptyMap()`).

- [ ] **Step 1: Add the field to `ProgressionStepInput`**

In `ProgressionController.kt`, change the data class to add the field after `muscleExercises`:

```kotlin
data class ProgressionStepInput(
    val now: Long,
    val observations: List<ProgressionObservation>,
    val baselines: Map<MuscleGroup, Float>,
    val coefficients: Map<Long, Float>,
    /** Every loaded (coefficient > 0) exercise id per muscle — the rolling pool. */
    val muscleExercises: Map<MuscleGroup, List<Long>>,
    /** Seed (default) coefficient per loaded exercise — reference for the geomean reclaimer. */
    val seedCoefficients: Map<Long, Float> = emptyMap(),
    /** Muscles with a HURT set this session — baseline backs off, overriding the PI update. */
    val hurtMuscles: Set<MuscleGroup>,
    val weightUnit: WeightUnit,
)
```

- [ ] **Step 2: Pass it from the repository**

In `WorkoutRepository.kt`, the `ProgressionStepInput(...)` constructed at line ~112 — add `seedCoefficients`:

```kotlin
        val output = controller.step(
            ProgressionStepInput(
                now = asOf,
                observations = observations,
                baselines = snapshot.currentBaselines.toMap(),
                coefficients = snapshot.currentCoefficients.toMap(),
                muscleExercises = muscleExercises,
                seedCoefficients = snapshot.seedCoefficients,
                hurtMuscles = hurtMuscles,
                weightUnit = weightUnit,
            ),
        )
```

- [ ] **Step 3: Compile and run the existing controller tests**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest"`
Expected: PASS (unchanged behavior — the field is unused so far).

- [ ] **Step 4: Commit**

```bash
jj describe -m "feat: thread seedCoefficients into ProgressionStepInput (unused, defaulted)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Robust common mode (within-session concordance)

Replace the weighted-mean common term with `RobustCenter.of`, so a lone outlier lands in its coefficient (baseline ~flat) while a unanimous shift moves the baseline. Add the two config knobs.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt` (config + the `common` computation at line ~128)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerTest.kt`

**Interfaces:**
- Consumes: `RobustCenter.of(...)` from Task 1.
- Produces: `ProgressionControllerConfig.huberDelta: Float = ln(1.10f)`, `robustIterations: Int = 3`.

- [ ] **Step 1: Write the failing test**

Add to `ProgressionControllerTest.kt` (it already has `controller()`, `obs(...)`, `input(...)` helpers — reuse them; `obs(id, est1RM, conf)` builds a `ProgressionObservation`):

```kotlin
    @Test
    fun robustCommon_loneLowOutlier_leavesBaselineNearlyFlat() {
        val c = controller()
        val muscle = MuscleGroup.QUADS
        val ids = listOf(1L, 2L, 3L)
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 1.0f, 3L to 1.0f)
        // Two calm on-target lifts, one reading ~half (a lone bracket-style outlier).
        val obs = listOf(
            obs(1L, est1RM = 100f, conf = 0.9f),
            obs(2L, est1RM = 100f, conf = 0.9f),
            obs(3L, est1RM = 55f, conf = 0.9f),
        )
        val out = c.step(
            ProgressionStepInput(
                now = 0L, observations = obs,
                baselines = mapOf(muscle to baseline), coefficients = coefs,
                muscleExercises = mapOf(muscle to ids),
                hurtMuscles = emptySet(), weightUnit = WeightUnit.KG,
            ),
        )
        val newBaseline = out.baselineUpdates.firstOrNull { it.muscleGroup == muscle }?.newBaseline ?: baseline
        // Lone outlier rejected: baseline moves < 2%, not the ~10% a plain mean would pull.
        assertTrue("baseline moved too far for a lone outlier: $newBaseline", abs(newBaseline - baseline) / baseline < 0.02f)
        // The drop lands in exercise 3's coefficient.
        val c3 = out.coefficientUpdates.firstOrNull { it.exerciseId == 3L }?.coefficient ?: 1.0f
        assertTrue("outlier coef should drop: $c3", c3 < 0.98f)
    }

    @Test
    fun robustCommon_unanimousDrop_movesBaseline() {
        val c = controller()
        val muscle = MuscleGroup.QUADS
        val ids = listOf(1L, 2L, 3L)
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 1.0f, 3L to 1.0f)
        // All three read ~30% low together -> consensus -> baseline drops.
        val obs = ids.map { obs(it, est1RM = 70f, conf = 0.9f) }
        val out = c.step(
            ProgressionStepInput(
                now = 0L, observations = obs,
                baselines = mapOf(muscle to baseline), coefficients = coefs,
                muscleExercises = mapOf(muscle to ids),
                hurtMuscles = emptySet(), weightUnit = WeightUnit.KG,
            ),
        )
        val newBaseline = out.baselineUpdates.firstOrNull { it.muscleGroup == muscle }?.newBaseline ?: baseline
        assertTrue("unanimous drop should move baseline down: $newBaseline", newBaseline < baseline * 0.97f)
    }
```

If `abs` / `MuscleGroup` / `WeightUnit` imports are missing in the test file, add `import kotlin.math.abs`, `import io.github.fowles.stochastic_strength.data.model.MuscleGroup`, `import io.github.fowles.stochastic_strength.data.model.WeightUnit`.

- [ ] **Step 2: Run to verify the lone-outlier test fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest.robustCommon_loneLowOutlier_leavesBaselineNearlyFlat"`
Expected: FAIL — current weighted mean pulls the baseline ~10%.

- [ ] **Step 3: Add config knobs and swap in the robust estimator**

In `ProgressionControllerConfig`, add after `maxLogStepCSnap`:

```kotlin
    /** Huber threshold (log space) above which a pool member is treated as an outlier. */
    val huberDelta: Float = ln(1.10f),
    /** Reweighting iterations for the robust common mode and the reclaimer. */
    val robustIterations: Int = 3,
```

In `step`, replace the common computation:

```kotlin
            val wsum = pooled.sumOf { it.third.toDouble() }.toFloat()
            val common = if (wsum > 0f) pooled.sumOf { (it.second * it.third).toDouble() }.toFloat() / wsum else 0f
```

with:

```kotlin
            val wsum = pooled.sumOf { it.third.toDouble() }.toFloat()
            val common = RobustCenter.of(
                pooled.map { it.second }, pooled.map { it.third }, config.huberDelta, config.robustIterations,
            )
```

- [ ] **Step 4: Run the new tests and the full controller suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest"`
Expected: PASS, including both new tests. If `easyVsHard_sameAverage_baselineFlat_coefsDiverge` or `differential_conservesGeomean_acrossSequence` fail, the synthetic innovations there are within `δ` so robust == mean — re-read the failure; do not weaken the new tests.

- [ ] **Step 5: Simulation gate**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerSimulationTest"`
Expected: PASS. If `coefInflation` drifts outside `0.97..1.03`, raise `huberDelta` toward `ln(1.20f)` (closer to the mean for in-band noise) and re-run. Record the chosen value.

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat: robust (Huber) common mode rejects lone outliers from the baseline vote

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Confidence → speed (EMA bypass + baseline-clamp relaxation)

A high-confidence reading is a measurement, not a noisy trend sample: bypass the EMA in proportion to `bracketConfidence`, and relax the baseline clamp by the confidence mass behind the common mode so a *unanimous* high-confidence drop can move the baseline fast (a lone one still can't, because robust `common ≈ 0`). The coefficient-side snap (`kCSnap`, `maxLogStepCSnap`) already exists and is unchanged.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt` (EMA update at line ~99; baseline step at line ~130; config)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerTest.kt`

**Interfaces:**
- Produces: `ProgressionControllerConfig.maxLogStepBSnap: Float = ln(2f)`.

- [ ] **Step 1: Write the failing test**

```kotlin
    @Test
    fun confidenceBypassesEma_singleSessionReachesBracketReading() {
        val muscle = MuscleGroup.QUADS
        val ids = listOf(1L, 2L, 3L)
        val baseline = 100f
        val coefs = mapOf(1L to 1.0f, 2L to 1.0f, 3L to 1.0f)
        fun runWith(bracket: Float): Float {
            val c = controller()
            // Prime EMA on-target so the smoothing would otherwise halve a new reading.
            c.step(
                ProgressionStepInput(
                    now = 0L, observations = ids.map { obs(it, est1RM = 100f, conf = 0.9f) },
                    baselines = mapOf(muscle to baseline), coefficients = coefs,
                    muscleExercises = mapOf(muscle to ids), hurtMuscles = emptySet(), weightUnit = WeightUnit.KG,
                ),
            )
            val out = c.step(
                ProgressionStepInput(
                    now = daysMs(7), observations = listOf(
                        obs(1L, est1RM = 100f, conf = 0.9f),
                        obs(2L, est1RM = 100f, conf = 0.9f),
                        ProgressionObservation(3L, muscle, 50f, 0.95f, bracket),
                    ),
                    baselines = mapOf(muscle to baseline), coefficients = coefs,
                    muscleExercises = mapOf(muscle to ids), hurtMuscles = emptySet(), weightUnit = WeightUnit.KG,
                ),
            )
            return out.coefficientUpdates.first { it.exerciseId == 3L }.coefficient
        }
        // With EMA bypass (bracket=0.95) the coef reaches much closer to the 50/100=0.5 reading
        // than the smoothed (bracket=0) path does in one session.
        val snapped = runWith(0.95f)
        val smoothed = runWith(0f)
        assertTrue("snap should land lower than smoothed: snap=$snapped smoothed=$smoothed", snapped < smoothed)
        assertTrue("snap should approach the 0.5 reading: $snapped", snapped < 0.62f)
    }
```

Add a `daysMs` helper to the test class if not present:

```kotlin
    private fun daysMs(days: Int): Long = days.toLong() * 24L * 60L * 60L * 1000L
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest.confidenceBypassesEma_singleSessionReachesBracketReading"`
Expected: FAIL — without bypass, `snapped ≈ smoothed` (EMA halves both equally), so `snapped < 0.62f` fails.

- [ ] **Step 3: Add the config knob and implement bypass + clamp relaxation**

In `ProgressionControllerConfig`, add:

```kotlin
    /** Baseline log-step clamp at full common-mode confidence (unanimous high-confidence drop). */
    val maxLogStepBSnap: Float = ln(2f),
```

Replace the EMA update loop body (lines ~96-103) so `betaEff` rises with `bracketConfidence`:

```kotlin
        for (o in input.observations) {
            if (o.est1RM <= 0f) continue
            val le = ln(o.est1RM)
            val s = o.bracketConfidence.coerceIn(0f, 1f)
            val betaEff = config.emaBeta + (1f - config.emaBeta) * s
            emaLogEst[o.exerciseId] =
                emaLogEst[o.exerciseId]?.let { (1f - betaEff) * it + betaEff * le } ?: le
            lastConf[o.exerciseId] = o.confidence
            lastTime[o.exerciseId] = input.now
        }
```

Replace the baseline-step computation (lines ~130-134) to relax the clamp by the confidence mass behind `common`:

```kotlin
            val massConf =
                if (wsum > 0f) {
                    pooled.sumOf { (bracketConfById[it.first] ?: 0f).toDouble() * it.third }.toFloat() / wsum
                } else {
                    0f
                }
            val maxStepB = config.maxLogStepB + (config.maxLogStepBSnap - config.maxLogStepB) * massConf
            val dLogB = (config.kB * common).coerceIn(-maxStepB, maxStepB)
            val bNew = b * exp(dLogB)
            if (bNew != b && bNew > 0f) {
                baselineUpdates.add(BaselineUpdate(m, bNew, "pi:n=${pooled.size},common=${fmt(common)}"))
            }
```

- [ ] **Step 4: Run the new test and the full controller suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest"`
Expected: PASS, including the new bypass test. The existing `bracketSnap_movesCoefFartherThanClampedPath_inOneSession` should still pass (coefficient snap path unchanged).

- [ ] **Step 5: Simulation gate**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerSimulationTest"`
Expected: PASS (ordinary sessions have `bracketConfidence = 0` → `betaEff = emaBeta`, `massConf = 0` → byte-identical baseline path).

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat: confidence bypasses EMA and relaxes the baseline clamp for unanimous drops

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Robust geomean reclaimer (cross-session concordance)

After the per-session update, re-base *collective* coefficient drift into the baseline as a product-preserving gauge transform, using `RobustCenter` over `ln(coef/seedCoef)` so a lone idiosyncratic coefficient is **not** reclaimed. This is what lets a globally-wrong baseline self-correct when it surfaces one lift per session.

**Files:**
- Modify: `app/src/main/java/io/github/fowles/stochastic_strength/domain/ProgressionController.kt` (per-muscle tail: rewrite coefficient emission + add reclaim; config)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerTest.kt`

**Interfaces:**
- Consumes: `ProgressionStepInput.seedCoefficients` (Task 2), `RobustCenter.of` (Task 1).
- Produces: `ProgressionControllerConfig.reclaimRate: Float = 0.5f`. Reclaim emits a `BaselineUpdate(metadata = "reclaim")` and `CoefficientUpdate(metadata = "reclaim")` for the muscle's loaded exercises when collective drift exists.

- [ ] **Step 1: Write the failing tests**

```kotlin
    @Test
    fun reclaim_collectiveDrift_movesIntoBaseline_preservingProducts() {
        val c = controller()
        val muscle = MuscleGroup.QUADS
        val ids = listOf(1L, 2L, 3L)
        val baseline = 100f
        val seeds = mapOf(1L to 1.0f, 2L to 1.0f, 3L to 1.0f)
        // All coefficients have drifted ~25% below seed (a baseline that was too high, corrected
        // one lift at a time over prior sessions). No new session signal this step (no observations
        // for these ids) -> only the reclaimer acts.
        val drifted = mapOf(1L to 0.75f, 2L to 0.78f, 3L to 0.72f)
        val out = c.step(
            ProgressionStepInput(
                now = 0L, observations = emptyList(),
                baselines = mapOf(muscle to baseline), coefficients = drifted,
                muscleExercises = mapOf(muscle to ids), seedCoefficients = seeds,
                hurtMuscles = emptySet(), weightUnit = WeightUnit.KG,
            ),
        )
        val nb = out.baselineUpdates.firstOrNull { it.muscleGroup == muscle }?.newBaseline ?: baseline
        assertTrue("baseline should drop toward the collective drift: $nb", nb < baseline)
        // Products preserved: baseline*coef unchanged for each exercise after the gauge shift.
        ids.forEach { id ->
            val nc = out.coefficientUpdates.firstOrNull { it.exerciseId == id }?.coefficient ?: drifted.getValue(id)
            assertEquals("product preserved for $id", baseline * drifted.getValue(id), nb * nc, baseline * 0.01f)
        }
    }

    @Test
    fun reclaim_loneOffset_isNotReclaimed() {
        val c = controller()
        val muscle = MuscleGroup.QUADS
        val ids = listOf(1L, 2L, 3L)
        val baseline = 100f
        val seeds = mapOf(1L to 1.0f, 2L to 1.0f, 3L to 1.0f)
        // One genuinely-hard exercise sits low; the other two are at seed. Robust center ~0 -> no reclaim.
        val coefs = mapOf(1L to 0.5f, 2L to 1.0f, 3L to 1.0f)
        val out = c.step(
            ProgressionStepInput(
                now = 0L, observations = emptyList(),
                baselines = mapOf(muscle to baseline), coefficients = coefs,
                muscleExercises = mapOf(muscle to ids), seedCoefficients = seeds,
                hurtMuscles = emptySet(), weightUnit = WeightUnit.KG,
            ),
        )
        val nb = out.baselineUpdates.firstOrNull { it.muscleGroup == muscle }?.newBaseline ?: baseline
        assertTrue("lone low coef must not drag the baseline: $nb", abs(nb - baseline) / baseline < 0.01f)
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest.reclaim_collectiveDrift_movesIntoBaseline_preservingProducts"`
Expected: FAIL — no reclaimer yet, baseline unchanged (no observations).

- [ ] **Step 3: Implement the reclaimer**

In `ProgressionControllerConfig`, add:

```kotlin
    /** Fraction of collective coefficient drift re-based into the baseline per session (0..1). */
    val reclaimRate: Float = 0.5f,
```

The per-muscle loop currently runs the HURT branch, then pools, then baseline, then a `for ((id, e, w) in pooled)` coefficient loop. The reclaimer must run for every trained muscle even with no observations this session, and must compose with the differential. Restructure: track per-muscle the working baseline and a mutable coefficient map, apply the differential into it, then apply the reclaim gauge-shift, then emit updates.

Replace the entire muscle loop body from the pooling section onward (everything after the HURT `continue`, i.e. from `val pooled = ...` to the end of the differential `for` loop) with:

```kotlin
            val pooled = input.muscleExercises[m].orEmpty().mapNotNull { id ->
                val le = emaLogEst[id] ?: return@mapNotNull null
                val c = input.coefficients[id] ?: return@mapNotNull null
                if (c <= 0f) return@mapNotNull null
                val age = (input.now - (lastTime[id] ?: input.now)).coerceAtLeast(0L)
                val w = exp(-age * ln2 / config.halfLifeMs).toFloat() * (lastConf[id] ?: 0f)
                if (w <= 1e-6f) return@mapNotNull null
                Triple(id, le - ln(b * c), w)
            }

            // Working copies; the reclaimer below re-bases these as a product-preserving gauge shift.
            var bWork = b
            val cWork = input.muscleExercises[m].orEmpty()
                .mapNotNull { id -> input.coefficients[id]?.let { id to it } }
                .filter { it.second > 0f }
                .toMap().toMutableMap()

            if (pooled.isNotEmpty()) {
                val wsum = pooled.sumOf { it.third.toDouble() }.toFloat()
                val common = RobustCenter.of(
                    pooled.map { it.second }, pooled.map { it.third }, config.huberDelta, config.robustIterations,
                )
                val massConf =
                    if (wsum > 0f) {
                        pooled.sumOf { (bracketConfById[it.first] ?: 0f).toDouble() * it.third }.toFloat() / wsum
                    } else {
                        0f
                    }
                val maxStepB = config.maxLogStepB + (config.maxLogStepBSnap - config.maxLogStepB) * massConf
                val dLogB = (config.kB * common).coerceIn(-maxStepB, maxStepB)
                bWork = b * exp(dLogB)

                val maxW = pooled.maxOf { it.third }
                for ((id, e, w) in pooled) {
                    val gain = w / maxW
                    val s = (bracketConfById[id] ?: 0f).coerceIn(0f, 1f)
                    val kCeff = config.kC + (config.kCSnap - config.kC) * s
                    val maxStep = config.maxLogStepC + (config.maxLogStepCSnap - config.maxLogStepC) * s
                    val dLogC = (kCeff * gain * (e - common)).coerceIn(-maxStep, maxStep)
                    cWork[id]?.let { cWork[id] = it * exp(dLogC) }
                }
            }

            // Cross-session reclaim: move collective coef/seed drift into the baseline (products preserved).
            val offsets = cWork.mapNotNull { (id, cur) ->
                val seed = input.seedCoefficients[id] ?: return@mapNotNull null
                if (seed > 0f && cur > 0f) ln(cur / seed) else null
            }
            if (offsets.isNotEmpty()) {
                val center = RobustCenter.of(offsets, List(offsets.size) { 1f }, config.huberDelta, config.robustIterations)
                val shift = config.reclaimRate * center
                if (abs(shift) > 1e-6f) {
                    bWork *= exp(shift)
                    for (id in cWork.keys.toList()) cWork[id] = cWork.getValue(id) * exp(-shift)
                }
            }

            if (bWork != b && bWork > 0f) {
                val tag = if (pooled.isEmpty()) "reclaim" else "pi:n=${pooled.size}"
                baselineUpdates.add(BaselineUpdate(m, bWork, tag))
            }
            for ((id, cNew) in cWork) {
                val cOld = input.coefficients.getValue(id)
                if (abs(cNew - cOld) <= config.minRelativeChange * cOld) continue
                coefficientUpdates.add(CoefficientUpdate(id, cNew, "pi:c=${fmt(cNew)}"))
            }
```

Note the surrounding `for (m in trainedMuscles)` loop, the `val b = ...`/`if (b <= 0f) continue` guard, and the HURT branch above it are unchanged. `trainedMuscles` already includes only muscles observed this session or HURT — so the reclaimer runs for muscles trained this session (the common case). The old `if (pooled.isEmpty()) continue` is removed (replaced by the `if (pooled.isNotEmpty())` guard) so reclaim can run even when no exercise had a fresh measurement but the pool still drifted.

- [ ] **Step 4: Run the reclaimer tests and the full controller suite**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerTest"`
Expected: PASS — both reclaim tests plus all prior tests (robust common, ema bypass, geomean conservation, hurt, single-exercise).

- [ ] **Step 5: Simulation gate (critical)**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerSimulationTest"`
Expected: PASS. The reclaimer drives `coefInflation` toward 1.0, which should keep `production_gains_conserve_gauge_under_strengthening` inside `0.97..1.03`. If `convSessions` or `jitter` regress, lower `reclaimRate` toward `0.25f`. If gauge over-tightens against real outlier exercises, that is acceptable as long as the band holds. Record the final `reclaimRate`.

- [ ] **Step 6: Commit**

```bash
jj describe -m "feat: robust geomean reclaimer re-bases collective coef drift into the baseline

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Rewrite the characterization test around prescriptions + add new cases

The existing `BulgarianBracketCharacterizationTest` asserts on raw coef/baseline numbers that are now free gauge artifacts. Rewrite the two cases to assert on **next prescriptions**, and add the unanimous-drop and drift-in-turn cases.

**Files:**
- Modify (rewrite asserts): `app/src/test/java/io/github/fowles/stochastic_strength/domain/BulgarianBracketCharacterizationTest.kt`

**Interfaces:**
- Consumes: `RollingConservingProgressionController`, `SessionSignalExtractor`, `WorkoutPlanner` weight selection via `DefaultProgressionEngine.fromOneRepMax` + `WeightFormatter.round` (already used by the test's `nextBulgarianLb`).

- [ ] **Step 1: Add `seedCoefficients` to the test's `input(...)` and a Goblet-prescription helper**

In `BulgarianBracketCharacterizationTest.kt`, update the `input(...)` builder to pass seeds, and add a Goblet next-prescription helper next to `nextBulgarianLb`:

```kotlin
    private fun input(now: Long, obs: List<ProgressionObservation>) = ProgressionStepInput(
        now = now, observations = obs,
        baselines = mapOf(quads to baselineKg), coefficients = seedCoefs,
        muscleExercises = mapOf(quads to listOf(barbell, goblet, bulgarian)),
        seedCoefficients = seedCoefs,
        hurtMuscles = emptySet(), weightUnit = unit,
    )

    private fun nextLb(id: Long, newBaselineKg: Float, newCoef: Float): Float =
        showLb(WeightFormatter.round(DefaultProgressionEngine.fromOneRepMax(newBaselineKg * newCoef, 10), unit))
```

Extend `Result` and `run(...)` to surface the next Goblet prescription:

```kotlin
    private data class Result(
        val bulgarianEst1RmLb: Float,
        val bracketConfidence: Float,
        val nextBulgarianLb: Float,
        val nextGobletLb: Float,
        val oldBulgarianLb: Float,
        val oldGobletLb: Float,
    )
```

In `run(...)`, replace the `Result(...)` construction with:

```kotlin
        return Result(
            bulgarianEst1RmLb = showLb(bulgAgg.est1RM),
            bracketConfidence = bulgAgg.bracketConfidence,
            nextBulgarianLb = nextLb(bulgarian, newBaselineKg, coef(bulgarian)),
            nextGobletLb = nextLb(goblet, newBaselineKg, coef(goblet)),
            oldBulgarianLb = nextLb(bulgarian, baselineKg, seedCoefs.getValue(bulgarian)),
            oldGobletLb = nextLb(goblet, baselineKg, seedCoefs.getValue(goblet)),
        )
```

(Delete the now-unused `outputBaselineLb`/`barbellCoef`/`gobletCoef`/`bulgarianCoef` fields and the `nextBulgarianLb` standalone fun — `nextLb` replaces it.)

- [ ] **Step 2: Rewrite the two case bodies to assert on prescriptions**

Replace the asserts in `drop_cascade_55_35_20_complete` (keep the `set(...)` list and `run(...)` call) with:

```kotlin
        // Calibration: the real on-device starting prescriptions.
        assertEquals(55.0f, r.oldBulgarianLb, 0.5f)
        assertEquals(65.0f, r.oldGobletLb, 0.5f)
        // est1RM anchors on the completed 20 lb set, far below the 55 lb top set.
        assertEquals(38.0f, r.bulgarianEst1RmLb, 0.5f)
        assertEquals(0.95f, r.bracketConfidence, 1e-6f)
        // Outcome spec: Bulgarian lands near the demonstrated ~20 lb; Goblet keeps moving up.
        assertTrue("next Bulgarian ${r.nextBulgarianLb} should be near 20 lb", r.nextBulgarianLb <= 25.0f)
        assertTrue("next Goblet ${r.nextGobletLb} should be >= last (65)", r.nextGobletLb >= 65.0f)
```

Replace the asserts in `all_failed_55_35_20_fail` with:

```kotlin
        // Calibration: the real on-device starting prescriptions.
        assertEquals(55.0f, r.oldBulgarianLb, 0.5f)
        assertEquals(65.0f, r.oldGobletLb, 0.5f)
        // est1RM comes from the lightest failed set's achieved reps -> lower than the drop-cascade.
        assertEquals(26.7f, r.bulgarianEst1RmLb, 0.5f)
        assertEquals(0.95f, r.bracketConfidence, 1e-6f)
        // Outcome spec: deeper failure lands Bulgarian at or below the drop-cascade weight; Goblet holds/up.
        assertTrue("next Bulgarian ${r.nextBulgarianLb} should be <= 20 lb", r.nextBulgarianLb <= 20.0f)
        assertTrue("next Goblet ${r.nextGobletLb} should be >= last (65)", r.nextGobletLb >= 65.0f)
```

Update the class KDoc: delete the paragraph describing the "deliberately-tolerated quad baseline dip" and replace with one sentence: "Asserts on next prescriptions (the only user-visible quantity): a lone Bulgarian bracket drops Bulgarian to its demonstrated capacity while Goblet's prescription holds or rises; the baseline is not dragged by the outlier."

- [ ] **Step 3: Add the two new cases**

Append to the class:

```kotlin
    @Test
    fun unanimous_drop_moves_baseline_not_just_coefficients() {
        // All three quad lifts bracket ~30% low together in one session: shared signal -> baseline drops.
        val c = RollingConservingProgressionController()
        c.step(input(0L, listOf(onTarget(barbell), onTarget(goblet), onTarget(bulgarian))))
        fun lowObs(id: Long) = ProgressionObservation(id, quads, baselineKg * seedCoefs.getValue(id) * 0.70f, 0.95f, 0.95f)
        val out = c.step(input(7L * 24 * 60 * 60 * 1000, listOf(lowObs(barbell), lowObs(goblet), lowObs(bulgarian))))
        val nb = out.baselineUpdates.first { it.muscleGroup == quads }.newBaseline
        assertTrue("unanimous drop should pull the baseline down: ${showLb(nb)}", nb < baselineKg * 0.95f)
    }

    @Test
    fun drift_in_turn_converges_baseline_over_sessions() {
        // A too-high baseline reveals itself one lift per session via brackets; the reclaimer should
        // pull the collective coefficient drift back into the baseline rather than leaving it stuck.
        val c = RollingConservingProgressionController()
        c.step(input(0L, listOf(onTarget(barbell), onTarget(goblet), onTarget(bulgarian))))
        val order = listOf(bulgarian, goblet, barbell)
        var lastBaseline = baselineKg
        order.forEachIndexed { i, id ->
            val now = (7L + i) * 24 * 60 * 60 * 1000
            // The one lift trained this session brackets ~30% low; the others are not retrained.
            val bracket = ProgressionObservation(id, quads, baselineKg * seedCoefs.getValue(id) * 0.70f, 0.95f, 0.95f)
            val out = c.step(input(now, listOf(bracket)))
            out.baselineUpdates.firstOrNull { it.muscleGroup == quads }?.let { lastBaseline = it.newBaseline }
            // Note: input(...) always passes the same seedCoefs as current coefficients, so this models
            // the SIGN of the reclaim, not a full closed loop; convergence direction is what we assert.
        }
        assertTrue("baseline should trend down as drift is reclaimed: ${showLb(lastBaseline)}", lastBaseline < baselineKg)
    }
```

- [ ] **Step 4: Run the characterization test**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.BulgarianBracketCharacterizationTest"`
Expected: PASS (4 tests). If `next Bulgarian <= 25` fails (lands at, say, 30), increase the coefficient snap convergence: confirm Task 4's EMA bypass is active for this observation (`bracketConfidence = 0.95`) and that `kCSnap = 1.0`. Do not weaken the assert without re-deriving the target from the bracket est1RM.

- [ ] **Step 5: Commit**

```bash
jj describe -m "test: characterization asserts on next prescriptions + unanimous/drift-in-turn cases

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: Extend the simulation test with a drift-in-turn scenario

Add a multi-session closed-loop scenario where the baseline starts too high and is revealed one lift per session, asserting the baseline converges down and the gauge recovers — the system-level guarantee behind Task 5.

**Files:**
- Modify: `app/src/test/java/io/github/fowles/stochastic_strength/domain/ProgressionControllerSimulationTest.kt` (add `seedCoefficients` to the existing `ProgressionStepInput`; add one `@Test`)

**Interfaces:**
- Consumes: the existing `simulateRealistic` harness wiring (it already constructs `ProgressionStepInput`).

- [ ] **Step 1: Wire seeds into the existing harness**

In `simulateRealistic`, the `ProgressionStepInput(...)` built around line 228 — add `seedCoefficients = seedCoef` so the reclaimer is active in the simulation:

```kotlin
            val out = controller.step(
                ProgressionStepInput(
                    now = t, observations = observations,
                    baselines = baselines.toMap(), coefficients = coefs.toMap(),
                    muscleExercises = muscleExercises, seedCoefficients = seedCoef,
                    hurtMuscles = hurtMuscles, weightUnit = unit,
                ),
            )
```

- [ ] **Step 2: Run the existing simulation asserts with the reclaimer live**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerSimulationTest"`
Expected: PASS. This is the real gauge gate now that seeds are wired. If `coefInflation` is pulled *too* tight or convergence regresses, adjust `reclaimRate` (Task 5) and re-run both tests. Record the final value in the controller KDoc.

- [ ] **Step 3: Add the drift-in-turn closed-loop test**

Append to `ProgressionControllerSimulationTest`:

```kotlin
    @Test
    fun too_high_baseline_revealed_in_turn_converges_down() {
        // One muscle (QUADS), 3 loaded lifts, true coefficients = seeds, baseline starts 60% too high.
        // Each session ONE lift is prescribed and brackets low (drop-cascade); over rounds the baseline
        // must fall toward truth and the coefficient geomean must return toward 1.0 (drift reclaimed,
        // not stranded in the coefficients).
        val muscle = MuscleGroup.QUADS
        val ids = listOf(101L, 102L, 103L)
        val seedCoef = mapOf(101L to 1.0f, 102L to 0.6f, 103L to 0.4f)
        val trueBaseline = 130f
        val baselines = mutableMapOf(muscle to trueBaseline * 1.6f) // 60% too high
        val coefs = seedCoef.toMutableMap()
        val controller = RollingConservingProgressionController()
        val muscleExercises = mapOf(muscle to ids)

        var t = 0L
        repeat(18) { round ->
            t += daysMs(3)
            val id = ids[round % ids.size]
            val target1RM = trueBaseline * seedCoef.getValue(id) // true capacity for this lift
            // High-confidence bracket reading at true capacity.
            val obs = listOf(ProgressionObservation(id, muscle, target1RM, 0.95f, 0.95f))
            val out = controller.step(
                ProgressionStepInput(
                    now = t, observations = obs,
                    baselines = baselines.toMap(), coefficients = coefs.toMap(),
                    muscleExercises = muscleExercises, seedCoefficients = seedCoef,
                    hurtMuscles = emptySet(), weightUnit = unit,
                ),
            )
            out.baselineUpdates.forEach { baselines[it.muscleGroup] = it.newBaseline }
            out.coefficientUpdates.forEach { coefs[it.exerciseId] = it.coefficient }
        }

        val finalBaseline = baselines.getValue(muscle)
        val inflation = exp(ids.map { ln(coefs.getValue(it) / seedCoef.getValue(it)).toDouble() }.average()).toFloat()
        // Baseline converged most of the way down from 1.6x toward 1.0x truth.
        assertTrue("baseline did not converge down: ${finalBaseline / trueBaseline}", finalBaseline < trueBaseline * 1.20f)
        // Drift was reclaimed into the baseline, not stranded in collapsed coefficients.
        assertTrue("coef geomean stranded low: $inflation", inflation > 0.90f)
    }
```

- [ ] **Step 4: Run the new test**

Run: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.ProgressionControllerSimulationTest.too_high_baseline_revealed_in_turn_converges_down"`
Expected: PASS. If the baseline converges too slowly within 18 rounds, raise `reclaimRate` toward `0.7f` *and* re-run the gauge test (Step 2) — both must hold. If the gauge test then breaks, the two are in tension: prefer the smaller `reclaimRate` that keeps Step 2 green and loosen this test's `1.20f` margin toward `1.30f` (documented, since direction-of-convergence is the real guarantee).

- [ ] **Step 5: Full suite run**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (whole module). Confirm no unrelated regressions.

- [ ] **Step 6: Commit**

```bash
jj describe -m "test: simulation drift-in-turn convergence + reclaimer live in harness

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review notes (for the executor)

- **Spec §1 (confidence→speed):** Task 4 (EMA bypass + baseline clamp); coefficient snap (`kCSnap`/`maxLogStepCSnap`) pre-existing, exercised by Task 6.
- **Spec §2 (robust common):** Task 1 (estimator) + Task 3 (wired into the baseline vote).
- **Spec §3 (reclaimer):** Task 2 (seed wiring) + Task 5 (gauge transform).
- **Acceptance criteria 1–2** (Bulgarian ≈ 20, Goblet ≥ 65): Task 6. **3** (unanimous): Tasks 3/6. **4** (drift-in-turn): Tasks 5/7. **5** (no regression): the simulation gate steps in Tasks 3/4/5/7.
- **Type consistency:** `RobustCenter.of(values, weights, delta, iterations)` used identically in Tasks 3 and 5; `seedCoefficients` field name identical in Tasks 2/6/7; config knobs `huberDelta`/`robustIterations`/`maxLogStepBSnap`/`reclaimRate` defined in Tasks 3/4/5 before use.
- **Tuning knobs** (`huberDelta`, `reclaimRate`) are pinned empirically by the simulation gates, per the spec's Risks section — the plan calls out the exact adjustment direction at each gate.
