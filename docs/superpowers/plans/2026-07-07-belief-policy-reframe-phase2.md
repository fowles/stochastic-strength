# Belief + Policy Reframe — Phase 2 (Belief Swap) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the heuristic `ExerciseEstimate(lnE, confidence)` estimator with an honest `ExerciseBelief(mu, sigma2)` filter — per-set censored (Tobit) observations, variance aging + muscle-keyed detraining drift — activate the policy's z/δ/fatigue terms, delete the detraining dialog in favor of automatic drift + a passive plan-preview notice, and re-pin the simulation and real-history backtest.

**Architecture:** Spec §§1–2, §4 (activation), §6–§10 of
`docs/superpowers/specs/2026-07-06-belief-policy-reframe-design.md`. The replay
architecture is untouched: replay is the sole source of truth, folds are
per-exercise-local, pooling is read-time, derived state is in-memory. Pooling
keeps its phase-1 *shape* (seed-vote level + evidence-gated shrink) as a bridge
until phase 3's τ-pooling; it reads beliefs through an effective-sample-size
mapping (see Bridge Decisions).

**Tech Stack:** Kotlin, JUnit4 on JVM, Room (schema untouched), Jetpack Compose + Vico (debug σ band only), org.json (test-only).

## Global Constraints

- **Zero Room migrations.** Schema stays v17. Nothing new is persisted; beliefs, policy state, and θ-free config all live in replayed/in-memory state.
- **Backtest fixtures** (`app/src/test/resources/backtest/history.json`, `baseline_prescriptions.json`) are personal data: gitignored, machine-local, NEVER committed. The frozen baseline is **never regenerated** — phase 2 compares against the same baseline phase 1 pinned (commit 92205abd worktree).
- **Version control:** jj. Commit at every red/green/refactor checkpoint with `jj commit -m "..."`. Every commit message ends with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`. Never push, never touch bookmarks.
- `org.json` stays `testImplementation` only.
- **Spec §4 policy order is binding:** base target (μ̃ − z·σ̃ + δ) → fatigue discount → failure-ceiling clamp → HURT multiplier → grid rounding. The phase-1 adjudicated round-down rule is verbatim-preserved: round down only when an unexpired **clear** ceiling exists AND nearest-rounding would land at/above `fromOneRepMax(ceiling.ceilingE1rm, sessionReps)`.
- **Spec §6 defaults** (initial values; Task 9 may re-tune ONLY z, δ, and `poolObsVar`, then pins): σ_seed 0.25, σ_override 0.10, σ_min 0.02, σ_max 0.30, q 8e-5/day, grace 14 d, driftRate 0.01/week, driftCap 0.25, φ 0.03/set, repNoiseBucket 0.75, repNoiseCounted 0.5, ρ_rel 0.06, λ₀ (levelPrior) 0.5, z 0.5, δ 0.01, noticeThreshold 0.03.
- **Deletions required by spec §7** (must be gone by end of phase): `DetrainingModel`, `DetrainingDialog`, `WorkoutPlan.detrainOverrides` + PlanPreview detraining plumbing, `RECENCY_BETA`, `bracketAggregate`, `SessionAggregate`, `wUp`, `wDown`, `wDownSnap`, `confidenceCap`, `halfLifeMs`. The projector evidence gate **stays** in phase 2 (deleted in phase 3).
- Unit tests: `./gradlew :app:testDebugUnitTest --tests "<Class>"` per task; full unit suite + `./gradlew :app:lint` + `./gradlew :app:connectedAndroidTest` before the phase closes.

## Bridge Decisions (phase-2-only design, controller-adjudicated)

These resolve gaps where the spec's end-state (phases 3–4) isn't available yet.
They are binding for this plan; phase 3 replaces №1–2.

1. **Pooling vote weight (n_eff).** `MuscleStrengthProjector` keeps its exact
   phase-1 algorithm (seed-anchored level vote, `levelPrior`, evidence-gated
   shrink with `priorStrength`), but the per-exercise vote weight becomes the
   belief's effective sample size:
   `neff = ((1/agedσ²) − (1/σ_seed²)) × poolObsVar`, clamped ≥ 0.
   Properties: a seed-only belief votes 0 (like today's seed confidence 0); a
   fully-trained belief (σ → σ_min) votes ≈ 5 (today's trained scale, cap 6);
   a stale belief (σ² grown past σ_seed²) votes 0, so a stale lone voter decays
   to the seed-anchored prior — preserving the pinned phase-1 behavior. The
   carried-forward pin "stale/same-age siblings don't pull up a fresh estimate"
   *requires* keeping the gate, which requires an evidence scale; n_eff is that
   scale. `poolObsVar` (default 2.0e-3) is a bridge constant pinned by Task 9
   and deleted in phase 3.
   [AMENDED 2026-07-08, during Task 7 adjudication: the shrink cap
   `priorStrength = 1.0` (a meaningless magnitude in n_eff units, inherited
   from confidence units) is replaced by `tauBridge = 0.25`: kappa =
   min(poolObsVar/τ², siblingExcess) ≈ min(0.032, excess). This is spec §3's
   blend with σ²_ℓLOO ≈ 0 and one uniform τ class — a trained own belief is
   barely moved by the level (matching phase 3), a cold one still adopts the
   sibling prediction fully (cSelf = 0 ⇒ full pull for any kappa > 0), and
   the stale/same-age gate is unchanged. All Task 4 pins stay green.]
2. **σ̃ for the z term = own aged σ.** Pooled σ̃ needs τ (phase 3). Until then
   `MuscleProjection` exposes `pooledSigma[id] = √(aged σ_i²)`. Consequence: a
   never-trained exercise is shaded by z·σ_seed ≈ 12% below its pooled mean —
   intentional first-time conservatism that one session of censored updates
   collapses. The cold-with-trained-siblings "within 12% of truth" pin applies
   to the projection **mean** (`effectiveE1rm`), not the shaded prescription.
3. **Fatigue discount nets out.** Beliefs estimate *fresh* (set-1) capacity;
   observations are divided by (1 − φ(k−1)), so beliefs sit ≈ 6% above today's
   last-set-tracking estimates. The policy's fatigue discount
   `+ln(1 − φ·(S−1))` (S = 3 ⇒ −6.1%) cancels that in steady state. Do not
   "fix" either half; they are a pair.
4. **Fatigue rank k** of a set = its 1-based rank by `setNumber` among ALL of
   that exercise's rows in the session (feedback-less/HURT rows still occupy a
   rank — the attempt fatigued the lifter). The factor (1 − φ(k−1)) is floored
   at 0.5.
5. **Chart dots = broad-prior session fold** (`impliedSessionE1rm`): fold the
   session's set observations into a prior with σ² = 1 anchored at the first
   observation-bearing set. This is the new-math analogue of the deleted
   `aggregateSession` — "what did this session's sets say" independent of
   history. (Deviation from spec §7's literal "post-session belief mean": that
   would place dots exactly on the own-estimate line, destroying the dot's
   information. Task 10 amends the spec line.)
6. **Aging application points.** Folding ages the stored belief to the session's
   `asOf` (subsequent same-session folds age 0 ms). Read paths (projector,
   charts) age transiently and never write back. `muscleLastObs`
   (muscle → last load-observation time) is tracked in `ReplaySnapshot` during
   replay, updated AFTER each session's folds (drift is keyed on the state
   before the session), and published to `PolicyState` for read-time use.
7. **Drift window** = overlap of [belief.updatedAt, now] with
   (muscleLastObs + grace, ∞); drift = min(driftRate × weeks, driftCap),
   subtracted from μ. `muscleLastObs == null` (muscle never trained) ⇒ no
   drift. An override row re-anchors `updatedAt`, so drift counts from the
   override when it is newer than muscleLast + grace.
8. **ProdBss pin handling.** Task 5 (the swap) temporarily relaxes
   `ProdBssPrescriptionTest` to safety bounds (prescription > 0 and ≤ 30 lb);
   Task 7 re-pins the exact value after z/δ/fatigue land
   (spec §9 expects ≈ 20 lb — if the run disagrees, report DONE_WITH_CONCERNS
   with the observed value; do not pin silently).
   [AMENDED during Task 5 execution: the original ≤ 20 lb bound is
   unsatisfiable at Task 5 — with wDownSnap deleted and z/δ still 0f the
   pooled belief rides at ~23 kg e1rm; both paths measured 30.0 lb.
   CORRECTED post-review 2026-07-08: the earlier amendment blamed the failure
   ceiling; in fact the ceiling is INERT (cap ≈ 25.3 kg would prescribe
   ~35 lb if it bound — the pooled value sits below it), so 30 lb is
   projector pass-through. Task 7's lever for closing 30 → ≈20 lb is the
   base target (z·σ shading + fatigue discount) acting on the pooled belief,
   NOT the ceiling. 20 lb returns as the expectation at Task 7's re-pin,
   which already guards it.]
   [FINAL ADJUDICATION 2026-07-08 (Task 7): policy path measured 30.0 lb with
   activation live; PINNED at 30 lb, a documented deviation from spec §9's
   "≈ 20 lb". Root cause is neither pooling (sibling excess ≈ 0 for this
   fixture; the tauBridge fix above was landed anyway for phase-3 consistency
   but did not move BSS) nor z·σ (the failures made the belief TIGHT,
   σ ≈ 0.030): the fixture's sessions contradict themselves — rank-1 failures
   (2×24.9 kg fresh, 2×29.5 kg in session 16) imply ~28–33 kg 1RM while the
   later collapsed sets imply ~18–19 — and the honest Kalman posterior
   averages to ~23.9 kg. Spec §9's ≈20 lb encoded the deleted wDownSnap's
   snap-to-worst-reading. The spec's behavioral safety pin (next weight
   strictly below every failed weight) HOLDS at 30 lb, and ceiling dynamics
   self-correct within ~2 sessions on a further miss. Surfaced to the user in
   the phase report; candidate future work: robust/adaptive observation noise
   for wildly inconsistent sessions (phase-4 fitting may partially absorb).]

## File Structure

New files (all pure, `domain/progression/` unless noted):
- `ExerciseBelief.kt` — belief data class + seed/override constructors
- `NormalCdf.kt` — erf (A&S 7.1.26), φ, Φ
- `BeliefUpdater.kt` — aging (q + drift), Gaussian fold, censored fold
- `SetObservation.kt` — set → observation translator (+ `repSlope`)
- `SessionObservations.kt` — `impliedSessionE1rm` shared chart-dot helper

Reshaped: `EstimatorConfig` (in `ExerciseEstimate.kt` → moves to `ExerciseBelief.kt`), `MuscleStrengthProjector`, `SessionProgressionStepper`, `ReplayEngine`, `ReplaySnapshot`, `DerivedStateStore`, `CrossTuning`, `ExerciseProgressionSeriesBuilder`, `PolicyState`, `PrescriptionPolicy`, `WorkoutRepository`, `WorkoutPlanner`, `WorkoutPlan`, `ExerciseDetailViewModel`, `ExerciseCoefficientDetailViewModel`, `ObservedSet`, `WorkoutScreen`/`WorkoutState`/`WorkoutSessionController`/`WorkoutViewModel` (detraining removal).

Deleted: `ExerciseEstimate` (class), `ExerciseEstimateUpdater.kt`, `SessionSignalExtractor.kt`, `DetrainingModel.kt`, `DetrainingDialog.kt`; tests `ExerciseEstimateUpdaterTest`, `SessionSignalExtractorTest`, `BulgarianBracketCharacterizationTest`, `DetrainingModelTest`, old `ExerciseEstimatorSimulationTest` (replaced by Task 9's rewrite).

---

### Task 1: ExerciseBelief, NormalCdf, BeliefUpdater folds

**Files:**
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseBelief.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/NormalCdf.kt`
- Create: `app/src/main/java/io/github/fowles/stochastic_strength/domain/progression/BeliefUpdater.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/NormalCdfTest.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefUpdaterFoldTest.kt`

**Interfaces:**
- Consumes: existing `EstimatorConfig` (adds fields, deletes nothing yet — old estimator still compiles).
- Produces: `ExerciseBelief(mu: Float, sigma2: Float, updatedAt: Long)` with `e1rm`, `sigma`, `seed(e1rm, at, config)`, `override(e1rm, at, config)`; `NormalCdf.erf/pdf/cdf(Float): Float`; `BeliefUpdater(config).foldGaussian(prior, obsLn, noiseSd, at, muscleLastObs): ExerciseBelief` and `.foldCensored(prior, lowerLn: Float?, upperLn: Float?, noiseSd, at, muscleLastObs): ExerciseBelief`. `age(...)` arrives in Task 2 — in this task implement `age` as a stub that only sets `updatedAt = now` (no q, no drift) so folds compile; Task 2 fills it in.

- [ ] **Step 1: Add the phase-2 config fields** (append to `EstimatorConfig` in `ExerciseEstimate.kt`; keep every existing field — deletion happens in Task 5):

```kotlin
    /** Seed-row belief uncertainty (std of ln 1RM). */
    val sigmaSeed: Float = 0.25f,
    /** Manual-override belief uncertainty. */
    val sigmaOverride: Float = 0.10f,
    /** Belief uncertainty floor / ceiling (std). */
    val sigmaMin: Float = 0.02f,
    val sigmaMax: Float = 0.30f,
    /** q: variance growth per idle day (σ² units). */
    val processNoisePerDay: Float = 8.0e-5f,
    /** Detraining drift: grace before drift starts, log-units lost per week, cap per idle gap. */
    val detrainGraceMs: Long = 14L * 24 * 60 * 60 * 1000,
    val detrainRatePerWeek: Float = 0.01f,
    val detrainCap: Float = 0.25f,
    /** φ: fraction of effective 1RM lost per additional set within an exercise. */
    val fatiguePerSet: Float = 0.03f,
    /** Report-noise bases (rep units) and the rep-magnitude term ρ_rel. */
    val repNoiseBucket: Float = 0.75f,
    val repNoiseCounted: Float = 0.5f,
    val repNoiseRel: Float = 0.06f,
    /** Bridge: per-observation variance defining n_eff pooling votes (phase 3 deletes). Sim-pinned. */
    val poolObsVar: Float = 2.0e-3f,
    /** Layoff notice threshold (fraction of strength eased). */
    val noticeThresholdFraction: Float = 0.03f,
```

Also change the existing `overloadDelta` default `0f → 0.01f` and `uncertaintyZ` default `0f → 0.5f`? **No — not yet.** They stay 0f until Task 7 activates them (backtest/tests would silently shift). Only add the new fields above.

- [ ] **Step 2: Write failing NormalCdf test**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalCdfTest {
    @Test
    fun erfMatchesGoldenValues() {
        // Golden values (Abramowitz–Stegun 7.1.26 max abs error 1.5e-7).
        assertEquals(0.0f, NormalCdf.erf(0f), 1e-6f)
        assertEquals(0.5204999f, NormalCdf.erf(0.5f), 5e-6f)
        assertEquals(0.8427008f, NormalCdf.erf(1f), 5e-6f)
        assertEquals(0.9953223f, NormalCdf.erf(2f), 5e-6f)
        assertEquals(-0.8427008f, NormalCdf.erf(-1f), 5e-6f)
    }

    @Test
    fun cdfAndPdfAreConsistent() {
        assertEquals(0.5f, NormalCdf.cdf(0f), 1e-6f)
        assertEquals(0.8413447f, NormalCdf.cdf(1f), 1e-5f)
        assertEquals(0.1586553f, NormalCdf.cdf(-1f), 1e-5f)
        assertEquals(0.3989423f, NormalCdf.pdf(0f), 1e-6f)
        assertEquals(0.2419707f, NormalCdf.pdf(1f), 1e-6f)
    }
}
```

- [ ] **Step 3: Run to verify failure** — `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.progression.NormalCdfTest"` — FAILS (unresolved reference).

- [ ] **Step 4: Implement NormalCdf.kt**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Standard-normal pdf/cdf via an Abramowitz–Stegun 7.1.26 erf approximation (|ε| ≤ 1.5e-7). */
object NormalCdf {
    fun erf(x: Float): Float {
        val sign = if (x < 0f) -1f else 1f
        val ax = abs(x.toDouble())
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
        return sign * (1.0 - poly * exp(-ax * ax)).toFloat()
    }

    fun pdf(x: Float): Float = (exp(-0.5 * x.toDouble() * x.toDouble()) / sqrt(2.0 * PI)).toFloat()

    fun cdf(x: Float): Float = 0.5f * (1f + erf(x / SQRT2))

    private val SQRT2 = sqrt(2.0).toFloat()
}
```

- [ ] **Step 5: Run — PASS. Commit** `jj commit -m "phase2: NormalCdf (A&S erf) with golden-value tests"`.

- [ ] **Step 6: Write failing fold tests.** The numerical-integration oracle computes the exact posterior moments of x ~ N(μ, σ²) given one observation z = x + s·ε censored to [L, U]:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.sqrt

class BeliefUpdaterFoldTest {
    private val config = EstimatorConfig()
    private val updater = BeliefUpdater(config)

    /** Exact posterior moments by trapezoidal integration over x. */
    private fun numericPosterior(mu: Double, sigma2: Double, lower: Double?, upper: Double?, s: Double): Pair<Double, Double> {
        val sigma = sqrt(sigma2)
        val n = 8001
        val lo = mu - 8 * sigma
        val hi = mu + 8 * sigma
        val dx = (hi - lo) / (n - 1)
        var m0 = 0.0; var m1 = 0.0; var m2 = 0.0
        for (i in 0 until n) {
            val x = lo + i * dx
            val prior = exp(-0.5 * (x - mu) * (x - mu) / sigma2)
            val pu = if (upper != null) NormalCdf.cdf(((upper - x) / s).toFloat()).toDouble() else 1.0
            val pl = if (lower != null) NormalCdf.cdf(((lower - x) / s).toFloat()).toDouble() else 0.0
            val wgt = prior * (pu - pl)
            m0 += wgt; m1 += wgt * x; m2 += wgt * x * x
        }
        val mean = m1 / m0
        return mean to (m2 / m0 - mean * mean)
    }

    private fun belief(mu: Float, sigma: Float) = ExerciseBelief(mu, sigma * sigma, updatedAt = 0L)

    @Test
    fun twoSidedCensoredFoldMatchesNumericalIntegration() {
        val prior = belief(4.0f, 0.15f)
        val (l, u) = 3.95f to 4.05f
        val s = 0.04f
        val folded = updater.foldCensored(prior, l, u, s, at = 0L, muscleLastObs = null)
        val (em, ev) = numericPosterior(4.0, 0.15 * 0.15, 3.95, 4.05, 0.04)
        assertEquals(em.toFloat(), folded.mu, 2e-3f)
        assertEquals(ev.toFloat(), folded.sigma2, ev.toFloat() * 0.05f)
    }

    @Test
    fun oneSidedLowerFoldMatchesNumericalIntegration() {
        val prior = belief(4.0f, 0.20f)
        val folded = updater.foldCensored(prior, 4.10f, null, 0.05f, 0L, null)
        val (em, ev) = numericPosterior(4.0, 0.04, 4.10, null, 0.05)
        assertEquals(em.toFloat(), folded.mu, 2e-3f)
        assertEquals(ev.toFloat(), folded.sigma2, ev.toFloat() * 0.05f)
        assertTrue("lower-bound obs must raise the mean", folded.mu > prior.mu)
    }

    @Test
    fun oneSidedUpperFoldMatchesNumericalIntegration() {
        val prior = belief(4.0f, 0.20f)
        val folded = updater.foldCensored(prior, null, 3.90f, 0.05f, 0L, null)
        val (em, ev) = numericPosterior(4.0, 0.04, null, 3.90, 0.05)
        assertEquals(em.toFloat(), folded.mu, 2e-3f)
        assertEquals(ev.toFloat(), folded.sigma2, ev.toFloat() * 0.05f)
        assertTrue("upper-bound obs must lower the mean", folded.mu < prior.mu)
    }

    @Test
    fun gaussianFoldIsStandardKalman() {
        val prior = belief(4.0f, 0.10f)
        val folded = updater.foldGaussian(prior, obsLnE1rm = 3.8f, noiseSd = 0.05f, at = 0L, muscleLastObs = null)
        val k = 0.01f / (0.01f + 0.0025f)
        assertEquals(4.0f + k * (3.8f - 4.0f), folded.mu, 1e-4f)
        assertEquals((1 - k) * 0.01f, folded.sigma2, 1e-5f)
    }

    @Test
    fun degenerateWindowFallsBackToGaussianAtViolatedBound() {
        // Prior far above the interval: Z ≈ 0 ⇒ Gaussian at the upper bound.
        val prior = belief(5.0f, 0.05f)
        val folded = updater.foldCensored(prior, 3.0f, 3.1f, 0.02f, 0L, null)
        val gauss = updater.foldGaussian(prior, 3.1f, 0.02f, 0L, null)
        assertEquals(gauss.mu, folded.mu, 1e-5f)
        assertEquals(gauss.sigma2, folded.sigma2, 1e-6f)
    }

    @Test
    fun sigmaIsClampedToConfiguredBounds() {
        val tight = updater.foldGaussian(belief(4f, config.sigmaMin), 4f, 1e-4f, 0L, null)
        assertTrue(tight.sigma2 >= config.sigmaMin * config.sigmaMin * 0.999f)
        val seeded = ExerciseBelief.seed(60f, at = 5L, config = config)
        assertEquals(config.sigmaSeed * config.sigmaSeed, seeded.sigma2, 1e-6f)
        assertEquals(5L, seeded.updatedAt)
        val over = ExerciseBelief.override(60f, at = 7L, config = config)
        assertEquals(config.sigmaOverride * config.sigmaOverride, over.sigma2, 1e-6f)
    }
}
```

- [ ] **Step 7: Run to verify failure**, then **implement**:

`ExerciseBelief.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One loaded exercise's belief about ln(fresh 1RM, kg): mean [mu] and variance [sigma2].
 * "Fresh" = first-set pre-fatigue capacity (spec §1); set observations are converted to the
 * fresh basis before folding. √sigma2 reads as relative uncertainty (0.04 ≈ ±4%).
 */
data class ExerciseBelief(
    val mu: Float,
    val sigma2: Float,
    val updatedAt: Long,
) {
    val e1rm: Float get() = exp(mu)
    val sigma: Float get() = sqrt(sigma2)

    companion object {
        fun seed(e1rm: Float, at: Long, config: EstimatorConfig = EstimatorConfig()): ExerciseBelief =
            ExerciseBelief(mu = ln(e1rm), sigma2 = config.sigmaSeed * config.sigmaSeed, updatedAt = at)

        fun override(e1rm: Float, at: Long, config: EstimatorConfig = EstimatorConfig()): ExerciseBelief =
            ExerciseBelief(mu = ln(e1rm), sigma2 = config.sigmaOverride * config.sigmaOverride, updatedAt = at)
    }
}
```

`BeliefUpdater.kt`:

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.sqrt

/**
 * Pure belief math: aging (variance growth + muscle-keyed detraining drift) and scalar
 * Gaussian / censored (Tobit) observation folds. Folds age the prior first (spec §2).
 */
class BeliefUpdater(private val config: EstimatorConfig = EstimatorConfig()) {

    /** Task 2 fills in q-growth and drift; the stub only re-stamps time so folds compose. */
    fun age(belief: ExerciseBelief, now: Long, muscleLastObs: Long?): ExerciseBelief =
        if (now <= belief.updatedAt) belief else belief.copy(updatedAt = now)

    fun foldGaussian(
        prior: ExerciseBelief,
        obsLnE1rm: Float,
        noiseSd: Float,
        at: Long,
        muscleLastObs: Long?,
    ): ExerciseBelief {
        val aged = age(prior, at, muscleLastObs)
        val s2 = noiseSd * noiseSd
        val k = aged.sigma2 / (aged.sigma2 + s2)
        return ExerciseBelief(
            mu = aged.mu + k * (obsLnE1rm - aged.mu),
            sigma2 = clampVar((1f - k) * aged.sigma2),
            updatedAt = at,
        )
    }

    /**
     * Fold one censored observation z = x + s·ε constrained to [lowerLn, upperLn] (either side
     * may be null = unbounded). Truncated-Gaussian moment match (spec §2) — exact for this model.
     */
    fun foldCensored(
        prior: ExerciseBelief,
        lowerLn: Float?,
        upperLn: Float?,
        noiseSd: Float,
        at: Long,
        muscleLastObs: Long?,
    ): ExerciseBelief {
        val aged = age(prior, at, muscleLastObs)
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
            return foldGaussian(aged, bound, noiseSd, at, muscleLastObs)
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
        )
    }

    internal fun clampVar(v: Float): Float =
        v.coerceIn(config.sigmaMin * config.sigmaMin, config.sigmaMax * config.sigmaMax)

    private companion object {
        const val CLAMP = 6f
        const val MIN_MASS = 1e-6f
    }
}
```

- [ ] **Step 8: Run — PASS:** `--tests "...BeliefUpdaterFoldTest" --tests "...NormalCdfTest"`. Also run the full unit suite (nothing else should notice the additive config change).

- [ ] **Step 9: Commit** `jj commit -m "phase2: ExerciseBelief + BeliefUpdater Gaussian/censored folds vs numerical oracle"`.

---

### Task 2: Aging — variance growth q + detraining drift

**Files:**
- Modify: `.../domain/progression/BeliefUpdater.kt` (replace the `age` stub)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefAgingTest.kt`

**Interfaces:**
- Produces: real `age(belief, now, muscleLastObs: Long?): ExerciseBelief` — q variance growth clamped to [σ_min², σ_max²]; μ drift per Bridge Decision №7. Every fold already routes through `age`.

- [ ] **Step 1: Write failing tests**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeliefAgingTest {
    private val config = EstimatorConfig()
    private val updater = BeliefUpdater(config)
    private fun days(d: Int): Long = d.toLong() * 24 * 60 * 60 * 1000
    private fun belief(sigma: Float, at: Long) = ExerciseBelief(mu = 4f, sigma2 = sigma * sigma, updatedAt = at)

    @Test
    fun varianceGrowsLinearlyWithIdleDaysAndClamps() {
        val b = belief(0.05f, at = 0L)
        val aged = updater.age(b, now = days(10), muscleLastObs = 0L)
        assertEquals(0.0025f + config.processNoisePerDay * 10f, aged.sigma2, 1e-6f)
        val long = updater.age(b, now = days(4000), muscleLastObs = 0L)
        assertEquals(config.sigmaMax * config.sigmaMax, long.sigma2, 1e-6f)
        assertEquals(days(4000), long.updatedAt)
    }

    @Test
    fun noDriftWithinGraceOrWhenMuscleTrainsElsewhere() {
        val b = belief(0.05f, at = 0L)
        // 10 days idle < 14-day grace: μ untouched.
        assertEquals(4f, updater.age(b, days(10), muscleLastObs = 0L).mu, 1e-6f)
        // Muscle trained (by a sibling) 2 days ago: drift window (recent+grace, now) is empty.
        assertEquals(4f, updater.age(b, days(60), muscleLastObs = days(58)).mu, 1e-6f)
        // Muscle never trained at all: no drift.
        assertEquals(4f, updater.age(b, days(60), muscleLastObs = null).mu, 1e-6f)
    }

    @Test
    fun driftAccruesPastGraceAndIsCapped() {
        val b = belief(0.05f, at = 0L)
        // 8 weeks idle: drift = rate × (56−14)/7 = 6 weeks × 1% = 0.06.
        val aged = updater.age(b, days(56), muscleLastObs = 0L)
        assertEquals(4f - config.detrainRatePerWeek * 6f, aged.mu, 1e-4f)
        // Multi-year gap: capped at detrainCap.
        val far = updater.age(b, days(1500), muscleLastObs = 0L)
        assertEquals(4f - config.detrainCap, far.mu, 1e-4f)
    }

    @Test
    fun overrideNewerThanMuscleLastReanchorsDrift() {
        // Belief re-anchored (override) at day 100; muscle last trained day 0.
        val b = belief(0.10f, at = days(100))
        // Window starts at max(updatedAt, muscleLast+grace) = day 100; 3 weeks past it.
        val aged = updater.age(b, days(121), muscleLastObs = 0L)
        assertEquals(4f - config.detrainRatePerWeek * 3f, aged.mu, 1e-4f)
    }

    @Test
    fun agingIsIdempotentInComposition() {
        // age(t0→t1) then (t1→t2) must equal age(t0→t2) when the drift window is contiguous.
        val b = belief(0.05f, at = 0L)
        val oneHop = updater.age(b, days(70), muscleLastObs = 0L)
        val twoHop = updater.age(updater.age(b, days(40), muscleLastObs = 0L), days(70), muscleLastObs = 0L)
        assertEquals(oneHop.mu, twoHop.mu, 1e-4f)
        assertEquals(oneHop.sigma2, twoHop.sigma2, 1e-6f)
        assertTrue(oneHop.mu < 4f)
    }
}
```

**Note on the composition test:** two-hop drift = (40−14)/7 + (70−40)/7 weeks only if the second hop's window starts at `updatedAt` (day 40) — which is > muscleLast+grace (day 14) — giving (26+30)/7 = 8 weeks = one-hop (70−14)/7 = 8 weeks. ✓ The cap is per-gap because each realized fold re-anchors `updatedAt`; pure read-time aging always recomputes from the stored anchor.

- [ ] **Step 2: Run — FAIL** (stub does no growth/drift).

- [ ] **Step 3: Implement `age`** (replace the stub):

```kotlin
    /**
     * Ages a belief from its [ExerciseBelief.updatedAt] to [now] (spec §1):
     * 1. Variance grows by q per idle day, clamped to [σ_min², σ_max²].
     * 2. Detraining drift on μ, keyed on the MUSCLE's last load observation: drift counts only
     *    the overlap of [updatedAt, now] with (muscleLastObs + grace, ∞), at driftRate per week,
     *    capped per idle gap. A muscle never observed ([muscleLastObs] == null) does not drift.
     * Pure function of timestamps — replay stays deterministic.
     */
    fun age(belief: ExerciseBelief, now: Long, muscleLastObs: Long?): ExerciseBelief {
        if (now <= belief.updatedAt) return belief
        val idleDays = (now - belief.updatedAt).toFloat() / DAY_MS
        val sigma2 = clampVar(belief.sigma2 + config.processNoisePerDay * idleDays)
        var mu = belief.mu
        if (muscleLastObs != null) {
            val driftStart = maxOf(belief.updatedAt, muscleLastObs + config.detrainGraceMs)
            val driftMs = now - driftStart
            if (driftMs > 0) {
                val weeks = driftMs.toFloat() / WEEK_MS
                mu -= minOf(config.detrainRatePerWeek * weeks, config.detrainCap)
            }
        }
        return ExerciseBelief(mu = mu, sigma2 = sigma2, updatedAt = now)
    }

    private companion object {
        const val CLAMP = 6f
        const val MIN_MASS = 1e-6f
        const val DAY_MS = 24f * 60 * 60 * 1000
        const val WEEK_MS = 7f * DAY_MS
    }
```

- [ ] **Step 4: Run — PASS** (`BeliefAgingTest` + `BeliefUpdaterFoldTest` — fold tests all use `at = 0L`/`updatedAt = 0L` so aging is inert there).

- [ ] **Step 5: Commit** `jj commit -m "phase2: belief aging — q variance growth + muscle-keyed detraining drift"`.

---

### Task 3: SetObservation — feedback → censored observation

**Files:**
- Create: `.../domain/progression/SetObservation.kt`
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/SetObservationTest.kt`

**Interfaces:**
- Consumes: `WorkoutSet` (`targetWeight`, `targetReps`, `actualReps`, `feedback`, `setNumber`), `DefaultProgressionEngine.rawToOneRepMax(weight, reps: Float)` (internal, same module), `EstimatorConfig` noise fields.
- Produces:
  ```kotlin
  data class SetObservation(val lowerLn: Float?, val upperLn: Float?, val gaussianLn: Float?, val noiseSd: Float)
  // companion: fun from(set: WorkoutSet, fatigueRank: Int, config: EstimatorConfig): SetObservation?
  // companion: fun repSlope(weight: Float, reps: Int): Float
  ```
  Exactly one of {gaussianLn, (lowerLn|upperLn)} is populated. `from` returns null for HURT, null feedback, or weight ≤ 0. Zero-coefficient exercise filtering is the CALLER's job (stepper), as today.

- [ ] **Step 1: Write failing tests**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln
import kotlin.math.sqrt

class SetObservationTest {
    private val config = EstimatorConfig()
    private fun set(feedback: SetFeedback?, weight: Float = 60f, reps: Int = 10, actual: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = weight, targetReps = reps, actualReps = actual, feedback = feedback)

    private fun capLn(w: Float, reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps))

    @Test
    fun rirBucketsMapToTheSpecIntervals() {
        val r01 = SetObservation.from(set(SetFeedback.RIR_0_1), fatigueRank = 1, config = config)!!
        assertEquals(capLn(60f, 10f), r01.lowerLn!!, 1e-5f)
        assertEquals(capLn(60f, 12f), r01.upperLn!!, 1e-5f)
        assertNull(r01.gaussianLn)

        val r24 = SetObservation.from(set(SetFeedback.RIR_2_4), 1, config)!!
        assertEquals(capLn(60f, 12f), r24.lowerLn!!, 1e-5f)
        assertEquals(capLn(60f, 15f), r24.upperLn!!, 1e-5f)

        val r5 = SetObservation.from(set(SetFeedback.RIR_5_PLUS), 1, config)!!
        assertEquals(capLn(60f, 15f), r5.lowerLn!!, 1e-5f)
        assertNull(r5.upperLn)
    }

    @Test
    fun countedFailureIsATightGaussianAtHalfRep() {
        val obs = SetObservation.from(set(SetFeedback.TOO_HARD, actual = 6), 1, config)!!
        assertEquals(capLn(60f, 6.5f), obs.gaussianLn!!, 1e-5f)
        assertNull(obs.lowerLn); assertNull(obs.upperLn)
        val expected = SetObservation.repSlope(60f, 10) *
            sqrt(config.repNoiseCounted * config.repNoiseCounted + (config.repNoiseRel * 10) * (config.repNoiseRel * 10))
        assertEquals(expected, obs.noiseSd, 1e-6f)
    }

    @Test
    fun uncountedFailureIsOneSidedFromAbove() {
        val obs = SetObservation.from(set(SetFeedback.TOO_HARD), 1, config)!!
        assertNull(obs.lowerLn); assertNull(obs.gaussianLn)
        assertEquals(capLn(60f, 10f), obs.upperLn!!, 1e-5f)
    }

    @Test
    fun fatigueRankShiftsObservationsUpToTheFreshBasis() {
        val fresh = SetObservation.from(set(SetFeedback.RIR_0_1), fatigueRank = 1, config = config)!!
        val third = SetObservation.from(set(SetFeedback.RIR_0_1), fatigueRank = 3, config = config)!!
        val shift = -ln(1f - config.fatiguePerSet * 2f)
        assertEquals(fresh.lowerLn!! + shift, third.lowerLn!!, 1e-5f)
        assertEquals(fresh.upperLn!! + shift, third.upperLn!!, 1e-5f)
        assertTrue(shift > 0f)
    }

    @Test
    fun hurtMissingFeedbackAndZeroWeightCarryNoObservation() {
        assertNull(SetObservation.from(set(SetFeedback.HURT), 1, config))
        assertNull(SetObservation.from(set(null), 1, config))
        assertNull(SetObservation.from(set(SetFeedback.RIR_0_1, weight = 0f), 1, config))
    }

    @Test
    fun noiseIsLargerAtLightAbsoluteLoads() {
        // λ = ∂ln f/∂ρ is steeper for light weights: accessory-lift noisiness emerges (spec §2).
        assertTrue(SetObservation.repSlope(20f, 10) > SetObservation.repSlope(100f, 10))
        assertTrue(SetObservation.repSlope(60f, 10) > 0f)
    }
}
```

- [ ] **Step 2: Run — FAIL. Implement `SetObservation.kt`:**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One working set translated into an observation of ln(fresh 1RM) (spec §2). Exactly one of
 * [gaussianLn] (counted failure) or the censored window [lowerLn, upperLn] is populated; a null
 * bound is unbounded on that side. Values are on the FRESH basis: set k under fatigue observes
 * capacity·(1 − φ(k−1)), so bounds are shifted by −ln(1 − φ(k−1)).
 */
data class SetObservation(
    val lowerLn: Float?,
    val upperLn: Float?,
    val gaussianLn: Float?,
    val noiseSd: Float,
) {
    companion object {
        /**
         * [fatigueRank] = the set's 1-based rank by setNumber among ALL of its exercise's rows in
         * the session (feedback-less/HURT rows still occupy a rank — the attempt fatigued the
         * lifter). Returns null when the set carries no load observation.
         */
        fun from(set: WorkoutSet, fatigueRank: Int, config: EstimatorConfig = EstimatorConfig()): SetObservation? {
            val feedback = set.feedback ?: return null
            if (feedback == SetFeedback.HURT) return null
            val w = set.targetWeight
            if (w <= 0f) return null
            val r = set.targetReps
            val freshShift = -ln(1f - (config.fatiguePerSet * (fatigueRank - 1)).coerceAtMost(0.5f))
            fun capLn(reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps)) + freshShift
            val lambda = repSlope(w, r)
            fun noise(base: Float) = lambda * sqrt(base * base + (config.repNoiseRel * r) * (config.repNoiseRel * r))
            return when (feedback) {
                SetFeedback.TOO_HARD -> {
                    val a = set.actualReps
                    if (a != null) SetObservation(null, null, capLn(a + 0.5f), noise(config.repNoiseCounted))
                    else SetObservation(null, capLn(r.toFloat()), null, noise(config.repNoiseBucket))
                }
                SetFeedback.RIR_0_1 -> SetObservation(capLn(r.toFloat()), capLn(r + 2f), null, noise(config.repNoiseBucket))
                SetFeedback.RIR_2_4 -> SetObservation(capLn(r + 2f), capLn(r + 5f), null, noise(config.repNoiseBucket))
                SetFeedback.RIR_5_PLUS -> SetObservation(capLn(r + 5f), null, null, noise(config.repNoiseBucket))
                SetFeedback.HURT -> null
            }
        }

        /** Local slope λ = ∂ln f(w, ρ)/∂ρ at ρ = r, central difference (spec §2). */
        fun repSlope(weight: Float, reps: Int): Float {
            val lo = (reps - 0.5f).coerceAtLeast(1f)
            val hi = reps + 0.5f
            val slope = (ln(DefaultProgressionEngine.rawToOneRepMax(weight, hi)) -
                ln(DefaultProgressionEngine.rawToOneRepMax(weight, lo))) / (hi - lo)
            return slope.coerceAtLeast(1e-4f)
        }
    }
}
```

- [ ] **Step 3: Run — PASS. Commit** `jj commit -m "phase2: SetObservation — feedback table, fresh-basis shift, rep-slope noise"`.

---

### Task 4: Projector bridge + CrossTuning on beliefs

**Files:**
- Modify: `.../domain/progression/MuscleStrengthProjector.kt` (full rewrite below)
- Modify: `.../domain/progression/CrossTuning.kt` (full rewrite below)
- Test: rewrite `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/MuscleStrengthProjectorTest.kt` and `CrossTuningTest.kt`

**Interfaces:**
- Consumes: `ExerciseBelief`, `BeliefUpdater.age`.
- Produces:
  ```kotlin
  data class MuscleProjection(val level: Float, val effectiveE1rm: Map<Long, Float>, val derivedCoef: Map<Long, Float>, val pooledSigma: Map<Long, Float>)
  class MuscleStrengthProjector(config) {
      fun neff(aged: ExerciseBelief): Float
      fun project(beliefs: Map<Long, ExerciseBelief>, seedCoef: Map<Long, Float>, muscleExerciseIds: List<Long>, now: Long, muscleLastObs: Long? = null): MuscleProjection
  }
  fun computeCrossTuning(beliefs: Map<Long, ExerciseBelief>, seedCoef, namesById, muscleExerciseIds, now, muscleLastObs: Long? = null, projector: MuscleStrengthProjector = ...): List<CrossTuningRow>
  ```
- **Compile note:** the OLD estimate-based `project`/`computeCrossTuning` are still referenced by the stepper/repository/series-builder until Task 5. This task adds the new belief-based functions as OVERLOADS in the same files (unambiguous by parameter type: `Map<Long, ExerciseBelief>` vs `Map<Long, ExerciseEstimate>`) and leaves the old ones untouched and compiling; Task 5 deletes the old overloads together with their callers. Do NOT write a shim mapping estimates to beliefs. The new `MuscleProjection` (with `pooledSigma`) REPLACES the old data class — update the old `project` overload's construction site to pass `pooledSigma = emptyMap()`.

- [ ] **Step 1: Write failing tests.** Rewrite `MuscleStrengthProjectorTest.kt` — keep every behavioral scenario currently in the file, re-expressed on beliefs. The required cases (write them all; current file shows the old versions):

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

class MuscleStrengthProjectorTest {
    private val config = EstimatorConfig()
    private val projector = MuscleStrengthProjector(config)
    private fun days(d: Int): Long = d.toLong() * 24 * 60 * 60 * 1000
    private fun trained(e1rm: Float, at: Long, sigma: Float = 0.03f) =
        ExerciseBelief(ln(e1rm), sigma * sigma, at)
    private fun cold(e1rm: Float, at: Long = 0L) = ExerciseBelief.seed(e1rm, at, config)

    @Test
    fun neffScalesFromZeroAtSeedToTrainedRange() {
        assertEquals(0f, projector.neff(cold(100f)), 1e-6f)
        val full = projector.neff(ExerciseBelief(4f, config.sigmaMin * config.sigmaMin, 0L))
        assertTrue("trained neff $full should land in today's confidence range", full in 3f..7f)
        // Stale (σ² above seed²): clamped to zero, never negative.
        assertEquals(0f, projector.neff(ExerciseBelief(4f, config.sigmaMax * config.sigmaMax, 0L)), 1e-6f)
    }

    @Test
    fun coldMuscleProjectsTheSeedLevel() {
        val beliefs = mapOf(1L to cold(80f), 2L to cold(40f))
        val seed = mapOf(1L to 1.0f, 2L to 0.5f)
        val proj = projector.project(beliefs, seed, listOf(1L, 2L), now = 0L)
        assertEquals(80f, proj.level, 0.5f)
        assertEquals(80f, proj.effectiveE1rm[1L]!!, 0.5f)
        assertEquals(40f, proj.effectiveE1rm[2L]!!, 0.5f)
    }

    @Test
    fun coldExerciseWithTrainedSiblingsIsPredictedFromTheirLevel() {
        // Two siblings trained to 130-level truth; the cold third (seeded at 100-level) is pulled
        // to within 12% of the sibling-implied capacity (carried-forward spec §9 pin, on the MEAN).
        val seed = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 0.6f)
        val beliefs = mapOf(
            1L to trained(130f, days(30)),
            2L to trained(104f, days(30)),
            3L to cold(60f), // seeded at level 100 × 0.6
        )
        val proj = projector.project(beliefs, seed, listOf(1L, 2L, 3L), now = days(30))
        val predicted = proj.effectiveE1rm[3L]!!
        assertTrue("cold exercise $predicted should approach 78 (130×0.6)", abs(predicted - 78f) / 78f <= 0.12f)
    }

    @Test
    fun staleOrSameAgeSiblingsDoNotLiftAFreshBelief() {
        // Fresh weak measurement vs stronger same-age/older siblings: gate must hold (≤ +1%).
        val seed = mapOf(1L to 0.30f, 2L to 0.55f, 3L to 0.45f)
        val now = days(400)
        val beliefs = mapOf(
            1L to trained(17.35f, now - days(6), sigma = 0.03f),
            2L to trained(36.45f, now - days(6), sigma = 0.03f),
            3L to trained(26.92f, now - days(11), sigma = 0.03f),
        )
        val proj = projector.project(beliefs, seed, listOf(1L, 2L, 3L), now = now)
        val own = exp(beliefs.getValue(1L).mu)
        assertTrue(
            "fresh belief ${proj.effectiveE1rm[1L]} must not be pulled above own $own",
            proj.effectiveE1rm[1L]!! <= own * 1.01f,
        )
    }

    @Test
    fun staleLoneVoterDecaysTowardTheSeedAnchor() {
        // One exercise trained far above seed, then idle long enough for σ to grow past σ_seed:
        // its vote → 0, level falls back to the seed-anchored prior (its own aged opinion is the
        // anchor mean, so the LEVEL equals its aged opinion — but the SHRINK no longer moves it up).
        val seed = mapOf(1L to 1.0f)
        val stale = ExerciseBelief(ln(150f), 0.29f * 0.29f, updatedAt = 0L)
        // AMENDED during execution (2026-07-07): the original plan text omitted muscleLastObs here
        // while the expected value below applies drift — the call must pass the muscle clock.
        val proj = projector.project(mapOf(1L to stale), seed, listOf(1L), now = days(600), muscleLastObs = 0L)
        // With zero vote and zero sibling excess, effective == own aged mean (drift applies via age).
        val agedMu = BeliefUpdater(config).age(stale, days(600), muscleLastObs = 0L).mu
        assertEquals(exp(agedMu), proj.effectiveE1rm[1L]!!, exp(agedMu) * 0.01f)
    }

    @Test
    fun pooledSigmaExposesTheOwnAgedUncertainty() {
        val beliefs = mapOf(1L to trained(100f, 0L, sigma = 0.05f))
        val proj = projector.project(beliefs, mapOf(1L to 1f), listOf(1L), now = days(10), muscleLastObs = 0L)
        val expected = BeliefUpdater(config).age(beliefs.getValue(1L), days(10), 0L).sigma
        assertEquals(expected, proj.pooledSigma[1L]!!, 1e-4f)
    }

    @Test
    fun driftLowersProjectionAfterAMuscleWideLayoff() {
        val beliefs = mapOf(1L to trained(100f, 0L))
        val rested = projector.project(beliefs, mapOf(1L to 1f), listOf(1L), now = days(70), muscleLastObs = 0L)
        val fresh = projector.project(beliefs, mapOf(1L to 1f), listOf(1L), now = days(70), muscleLastObs = days(69))
        assertTrue("idle-muscle projection ${rested.effectiveE1rm[1L]} must sit below active ${fresh.effectiveE1rm[1L]}",
            rested.effectiveE1rm[1L]!! < fresh.effectiveE1rm[1L]!!)
    }
}
```

For `CrossTuningTest.kt`: keep the existing scenarios (agreement sign/zero cases, contribution shares summing to 1 over confident exercises), constructed with `ExerciseBelief` and asserting `contribution` = n_eff share. Follow the current file's cases one-for-one.

- [ ] **Step 2: Run — FAIL. Implement.** New projector (keep the old `project(Map<Long, ExerciseEstimate>, ...)` beside it until Task 5):

```kotlin
data class MuscleProjection(
    /** Muscle level L: n_eff-weighted geomean of aged E_j / seedCoef_j against the seed-anchored prior. */
    val level: Float,
    /** Shrunk pooled mean per exercise (own aged belief blended toward the sibling prediction). */
    val effectiveE1rm: Map<Long, Float>,
    /** Display/prescription coefficient: effectiveE1rm[i] / level. */
    val derivedCoef: Map<Long, Float>,
    /** Own aged belief std per exercise — the z-shading input until phase-3 pooling. */
    val pooledSigma: Map<Long, Float>,
)

class MuscleStrengthProjector(private val config: EstimatorConfig = EstimatorConfig()) {
    private val updater = BeliefUpdater(config)

    /**
     * Bridge vote weight (phase 2 only): the belief's effective sample size in poolObsVar units —
     * precision above the seed floor. Seed-fresh → 0; fully trained → ≈5 (today's scale); stale
     * (σ² grown past σ_seed²) → 0, so a stale lone voter decays to the seed-anchored prior.
     */
    fun neff(aged: ExerciseBelief): Float {
        val seedVar = config.sigmaSeed * config.sigmaSeed
        return ((1f / aged.sigma2 - 1f / seedVar) * config.poolObsVar).coerceAtLeast(0f)
    }

    fun project(
        beliefs: Map<Long, ExerciseBelief>,
        seedCoef: Map<Long, Float>,
        muscleExerciseIds: List<Long>,
        now: Long,
        muscleLastObs: Long? = null,
    ): MuscleProjection {
        val loaded = muscleExerciseIds.mapNotNull { id ->
            val b = beliefs[id] ?: return@mapNotNull null
            val coef = seedCoef[id] ?: return@mapNotNull null
            if (coef <= 0f) null else Triple(id, updater.age(b, now, muscleLastObs), coef)
        }
        if (loaded.isEmpty()) return MuscleProjection(0f, emptyMap(), emptyMap(), emptyMap())

        val lnPrior = loaded.map { (_, b, coef) -> b.mu - ln(coef) }.average().toFloat()
        var num = config.levelPrior * lnPrior
        var den = config.levelPrior
        for ((_, b, coef) in loaded) {
            val c = neff(b)
            num += c * (b.mu - ln(coef))
            den += c
        }
        val lnLevel = num / den
        val level = exp(lnLevel)

        val effective = mutableMapOf<Long, Float>()
        val coefs = mutableMapOf<Long, Float>()
        val sigmas = mutableMapOf<Long, Float>()
        for ((id, b, coef) in loaded) {
            val cSelf = neff(b)
            val lnPred = ln(coef) + lnLevel
            // Evidence gate (unchanged from phase 1, in n_eff units): siblings may override only by
            // their EXCESS evidence, so same-age/staler siblings cannot lift a fresh measurement.
            val siblingExcess = loaded.sumOf { (jid, jb, _) ->
                if (jid == id) 0.0 else (neff(jb) - cSelf).coerceAtLeast(0f).toDouble()
            }.toFloat()
            val kappa = minOf(config.priorStrength, siblingExcess)
            val lnUsed = if (cSelf + kappa <= 0f) b.mu else (cSelf * b.mu + kappa * lnPred) / (cSelf + kappa)
            effective[id] = exp(lnUsed)
            coefs[id] = if (level > 0f) exp(lnUsed) / level else coef
            sigmas[id] = b.sigma
        }
        return MuscleProjection(level, effective, coefs, sigmas)
    }
}
```

`computeCrossTuning` belief overload: identical structure to the current function with `conf` → `projector.neff(updater.age(belief, now, muscleLastObs))`, `ownE1rm` → `exp(aged.mu)`, and LOO projection via the new `project`. (Instantiate one `BeliefUpdater(config)` locally; add `muscleLastObs: Long? = null` parameter.)

- [ ] **Step 3: Run — PASS** (`MuscleStrengthProjectorTest`, `CrossTuningTest`, plus the full unit suite — old callers still compile against the old overloads).

- [ ] **Step 4: Commit** `jj commit -m "phase2: projector bridge — n_eff votes over aged beliefs, pooledSigma output; cross-tuning on beliefs"`.

---

### Task 5: Replay-core swap — per-set folds, snapshot/store/policy plumbing, old estimator deleted

This is the atomic swap; the codebase compiles old-style before it and new-style after it. Work through the checklist in order; the compiler drives the fallout list.

**Files:**
- Modify: `ReplaySnapshot.kt`, `SessionProgressionStepper.kt`, `ReplayEngine.kt`, `DerivedStateStore.kt`, `WorkoutRepository.kt`, `PolicyState.kt`, `domain/progression/ObservedSet.kt`, `domain/progression/ExerciseBelief.kt` (receives `EstimatorConfig`), test `BacktestHarness.kt` (mechanical), androidTest `WorkoutSessionControllerTest.kt` (mechanical rename only)
- Delete: `domain/progression/ExerciseEstimate.kt` (move `EstimatorConfig` into `ExerciseBelief.kt` minus deleted fields), `domain/progression/ExerciseEstimateUpdater.kt`, `domain/SessionSignalExtractor.kt`, old projector/cross-tuning overloads from Task 4
- Delete tests: `ExerciseEstimateUpdaterTest.kt`, `SessionSignalExtractorTest.kt`, `BulgarianBracketCharacterizationTest.kt`, `ExerciseEstimatorSimulationTest.kt` (**ledger note: sim pin intentionally absent until Task 9 restores it — do not close the phase without it**)
- Modify tests: `SessionProgressionStepperTest.kt` (rewrite, below), `ReplayEngineTest.kt`, `ReplayHistoryTest.kt`, `ReplayProjectionTest.kt`, `ExerciseProgressionSeriesBuilderTest.kt`, `PolicyStateBuilderTest.kt`, `ProdBssPrescriptionTest.kt` (relax to safety bounds per Bridge Decision №8), `derived/` store tests if any reference estimates

**Interfaces (produced, relied on by Tasks 6–9):**
- `ReplaySnapshot`: `val currentBeliefs: MutableMap<Long, ExerciseBelief>`; `val muscleLastObs: MutableMap<MuscleGroup, Long>` (both replace `currentEstimates`)
- `SessionProgressionStepper.step(sets, snapshot, asOf)` — same signature/StepResult, per-set folds inside
- `PolicyState` gains `val muscleLastObs: Map<MuscleGroup, Long> = emptyMap()`; `PolicyStateBuilder.build(muscleLastObs: Map<MuscleGroup, Long> = emptyMap())`
- `DerivedStateStore.Snapshot.exerciseBeliefs(): Map<Long, ExerciseBelief>` / `MutableDerivedState.putExerciseBeliefs(...)`
- `EstimatorConfig` loses `halfLifeMs`, `confidenceCap`, `wUp`, `wDown`, `wDownSnap` (keeps `levelPrior`, `priorStrength`, all policy + phase-2 fields)

- [ ] **Step 1: Write the new stepper test first** (replace `SessionProgressionStepperTest.kt`):

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.ReplaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionProgressionStepperTest {
    private val config = EstimatorConfig()
    private val stepper = SessionProgressionStepper()
    private fun snapshot() = ReplaySnapshot(
        exerciseMuscle = mapOf(1L to MuscleGroup.QUADS, 2L to MuscleGroup.QUADS, 3L to MuscleGroup.CHEST),
        seedCoefficients = mapOf(1L to 1.0f, 2L to 0.8f, 3L to 1.0f),
    ).apply {
        currentBeliefs[1L] = ExerciseBelief.seed(100f, 0L, config)
        currentBeliefs[2L] = ExerciseBelief.seed(80f, 0L, config)
        currentBeliefs[3L] = ExerciseBelief.seed(60f, 0L, config)
    }
    private fun set(ex: Long, n: Int, fb: SetFeedback?, w: Float = 70f, reps: Int = 10, actual: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = ex, setNumber = n, targetWeight = w, targetReps = reps, actualReps = actual, feedback = fb)

    @Test
    fun setsFoldSequentiallyAndTightenTheBelief() {
        val snap = snapshot()
        val before = snap.currentBeliefs.getValue(1L)
        stepper.step(listOf(set(1L, 1, SetFeedback.RIR_0_1), set(1L, 2, SetFeedback.RIR_0_1), set(1L, 3, SetFeedback.RIR_0_1)), snap, asOf = 1000L)
        val after = snap.currentBeliefs.getValue(1L)
        assertTrue("3 in-target sets must tighten sigma", after.sigma2 < before.sigma2)
        assertEquals(1000L, after.updatedAt)
    }

    @Test
    fun failureLowersTheBeliefMean() {
        // [AMENDED during Task 5: was w = 90f, but rawToOneRepMax(90, 5.5) ≈ 110 kg > seed
        // (100 kg) — that observation pulls mu UP. 5 reps at 70 kg implies ≈ 85.5 kg < seed.]
        val snap = snapshot()
        val before = snap.currentBeliefs.getValue(1L).mu
        stepper.step(listOf(set(1L, 1, SetFeedback.TOO_HARD, w = 70f, actual = 5)), snap, 1000L)
        assertTrue(snap.currentBeliefs.getValue(1L).mu < before)
    }

    @Test
    fun hurtOnlySessionsFoldNothingAndDoNotTouchTheMuscleClock() {
        val snap = snapshot()
        val before = snap.currentBeliefs.getValue(1L)
        val result = stepper.step(listOf(set(1L, 1, SetFeedback.HURT)), snap, 1000L)
        assertEquals(before, snap.currentBeliefs.getValue(1L))
        assertTrue(result.steps.isEmpty())
        assertTrue(snap.muscleLastObs.isEmpty())
    }

    @Test
    fun zeroCoefficientExercisesAreSkipped() {
        val snap = snapshot()
        snap.currentBeliefs[9L] = ExerciseBelief.seed(50f, 0L, config)
        val result = stepper.step(listOf(set(9L, 1, SetFeedback.RIR_0_1)), snap, 1000L)
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun muscleClockAdvancesOnlyForFoldedMuscles() {
        val snap = snapshot()
        stepper.step(listOf(set(1L, 1, SetFeedback.RIR_2_4), set(3L, 1, SetFeedback.HURT)), snap, 1000L)
        assertEquals(1000L, snap.muscleLastObs[MuscleGroup.QUADS])
        assertTrue(MuscleGroup.CHEST !in snap.muscleLastObs)
    }

    @Test
    fun laterSetsCountAsMoreFatigued() {
        // The same failed set folded at rank 3 implies MORE fresh capacity than at rank 1.
        val s1 = snapshot(); val s3 = snapshot()
        stepper.step(listOf(set(1L, 1, SetFeedback.TOO_HARD, w = 90f, actual = 5)), s1, 1000L)
        stepper.step(
            listOf(set(1L, 1, null, w = 90f), set(1L, 2, null, w = 90f), set(1L, 3, SetFeedback.TOO_HARD, w = 90f, actual = 5)),
            s3, 1000L,
        )
        assertTrue(s3.currentBeliefs.getValue(1L).mu > s1.currentBeliefs.getValue(1L).mu)
    }

    @Test
    fun projectionStepsAreEmittedPerAffectedMuscle() {
        val snap = snapshot()
        val result = stepper.step(listOf(set(1L, 1, SetFeedback.RIR_0_1), set(3L, 1, SetFeedback.RIR_0_1, w = 40f)), snap, 1000L)
        assertEquals(setOf(MuscleGroup.QUADS, MuscleGroup.CHEST), result.steps.map { it.muscle }.toSet())
    }
}
```

- [ ] **Step 2: Implement the stepper** (full replacement of the class body):

```kotlin
/**
 * Pure per-session core: sequential per-set belief folds (spec §2) → projection of each affected
 * muscle. Mutates [ReplaySnapshot.currentBeliefs] and [ReplaySnapshot.muscleLastObs] in place.
 * HURT never touches beliefs (policy-only). Drift during the folds is keyed on the muscle clock
 * BEFORE this session; the clock advances after all of the session's folds.
 */
class SessionProgressionStepper(
    private val updater: BeliefUpdater = BeliefUpdater(),
    private val projector: MuscleStrengthProjector = MuscleStrengthProjector(),
    private val config: EstimatorConfig = EstimatorConfig(),
) {
    data class MuscleStep(val muscle: MuscleGroup, val projection: MuscleProjection)
    data class StepResult(val steps: List<MuscleStep>)

    fun step(sets: List<WorkoutSet>, snapshot: ReplaySnapshot, asOf: Long): StepResult {
        if (sets.isEmpty()) return StepResult(emptyList())

        val affectedMuscles = mutableSetOf<MuscleGroup>()
        sets.groupBy { it.exerciseId }.forEach { (id, exSets) ->
            if ((snapshot.seedCoefficients[id] ?: 0f) <= 0f) return@forEach
            var belief = snapshot.currentBeliefs[id] ?: return@forEach
            val muscleLast = snapshot.exerciseMuscle[id]?.let { snapshot.muscleLastObs[it] }
            var folded = false
            exSets.sortedBy { it.setNumber }.forEachIndexed { i, set ->
                val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
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
        for (m in affectedMuscles) snapshot.muscleLastObs[m] = asOf

        val steps = affectedMuscles.mapNotNull { m ->
            val exerciseIds = snapshot.muscleExerciseIds[m] ?: return@mapNotNull null
            MuscleStep(
                muscle = m,
                projection = projector.project(
                    beliefs = snapshot.currentBeliefs,
                    seedCoef = snapshot.seedCoefficients,
                    muscleExerciseIds = exerciseIds,
                    now = asOf,
                    muscleLastObs = snapshot.muscleLastObs[m],
                ),
            )
        }
        return StepResult(steps)
    }
}
```

- [ ] **Step 3: Sweep the rename through the compile fallout, in this order:**
  1. `ReplaySnapshot`: `currentEstimates` → `currentBeliefs: MutableMap<Long, ExerciseBelief>`; add `val muscleLastObs: MutableMap<MuscleGroup, Long> = mutableMapOf()`.
  2. `ReplayEngine.run`: seeding becomes `ExerciseBelief.seed(init.e1rm, at = init.asOf, config)`; session overrides become `ExerciseBelief.override(o.e1rm, o.asOf, config)`; give `ReplayEngine` a `private val config: EstimatorConfig = EstimatorConfig()` constructor param (after the stepper param).
  3. `PolicyState`: add `val muscleLastObs: Map<MuscleGroup, Long> = emptyMap()` (update `EMPTY`); `PolicyStateBuilder.build(muscleLastObs: Map<MuscleGroup, Long> = emptyMap())` copies it in.
  4. `DerivedStateStore`/`MutableDerivedState`: `exerciseEstimates` → `exerciseBeliefs: Map<Long, ExerciseBelief>`, `putExerciseEstimates` → `putExerciseBeliefs`.
  5. `WorkoutRepository`: `replayDerivedState` observer unchanged except `scratch.putPolicyState(policyBuilder.build(snapshot.muscleLastObs.toMap()))` and `scratch.putExerciseBeliefs(snapshot.currentBeliefs.toMap())`; the cold-start display-fill block passes `muscleLastObs = snapshot.muscleLastObs[muscle]` to `project`. `buildPlanner`: `derivedState.snapshot().exerciseBeliefs()`; per-muscle projection loop passes `muscleLastObs = derivedState.snapshot().policyState().muscleLastObs[muscle]` — restructure the flatMap into an explicit loop over `muscleIds` so the muscle is in scope. `getCrossTuning` passes the same. (`PrescriptionPolicy` input stays `Map<Long, Float>` of `effectiveE1rm` until Task 7.)
  6. `ExerciseProgressionSeriesBuilder` (`sampleSession`/`buildFrame`): `snapshot.currentEstimates[targetId]` → `currentBeliefs`, line value stays the stored post-fold mean `exp(belief.mu)` ("line = where the estimate landed", no read-time aging). The two `aggregateSession` dot call sites become `impliedSessionE1rm(exSets)`; create `SessionObservations.kt` NOW with exactly this content (Task 6 verifies + tests it):
     ```kotlin
     package io.github.fowles.stochastic_strength.domain.progression

     import io.github.fowles.stochastic_strength.data.model.SetFeedback
     import io.github.fowles.stochastic_strength.data.model.WorkoutSet
     import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
     import kotlin.math.ln

     /**
      * The session's implied fresh-1RM for one exercise: a broad-prior fold of the session's set
      * observations. Chart-dot analogue of the deleted aggregateSession — "what did this session's
      * sets say", independent of prior history (prior σ² = 1 ≈ uninformative). Returns null when no
      * set carries a load observation.
      */
     fun impliedSessionE1rm(sets: List<WorkoutSet>, config: EstimatorConfig = EstimatorConfig()): Float? {
         val ordered = sets.sortedBy { it.setNumber }
         val anchor = ordered.firstOrNull {
             it.targetWeight > 0f && it.feedback != null && it.feedback != SetFeedback.HURT
         } ?: return null
         val updater = BeliefUpdater(config)
         var belief = ExerciseBelief(
             mu = ln(DefaultProgressionEngine.rawToOneRepMax(anchor.targetWeight, anchor.targetReps)),
             sigma2 = 1f,
             updatedAt = 0L,
         )
         var folded = false
         ordered.forEachIndexed { i, set ->
             val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
             belief = if (obs.gaussianLn != null) {
                 updater.foldGaussian(belief, obs.gaussianLn, obs.noiseSd, at = 0L, muscleLastObs = null)
             } else {
                 updater.foldCensored(belief, obs.lowerLn, obs.upperLn, obs.noiseSd, at = 0L, muscleLastObs = null)
             }
             folded = true
         }
         return if (folded) belief.e1rm else null
     }
     ```
     Sibling dots in `sampleSession` keep the seed-ratio scaling: `impliedSessionE1rm(exSets)?.times(targetSeed / sibSeed)`.
  7. `ObservedSet.kt`: inline the three reserve constants (delete the `SessionSignalExtractor` import):
     ```kotlin
     /** Display-only reserve offsets for "~reps" rendering (were SessionSignalExtractor.RESERVE_*). */
     internal const val DISPLAY_RESERVE_RIR_0_1 = 0.5f
     internal const val DISPLAY_RESERVE_RIR_2_4 = 3f
     internal const val DISPLAY_RESERVE_RIR_5_PLUS = 6f
     ```
  8. Move `EstimatorConfig` into `ExerciseBelief.kt`; delete fields `halfLifeMs`, `confidenceCap`, `wUp`, `wDown`, `wDownSnap`; delete files `ExerciseEstimate.kt`, `ExerciseEstimateUpdater.kt`, `SessionSignalExtractor.kt`; delete the old projector/cross-tuning overloads from Task 4.
  9. `ExerciseDetailViewModel.observedSessionPoints`: switch `SessionSignalExtractor.aggregateSession(s.sets)?.est1RM` → `impliedSessionE1rm(s.sets)` (import from progression package).
  10. Tests: delete `ExerciseEstimateUpdaterTest`, `SessionSignalExtractorTest`, `BulgarianBracketCharacterizationTest`, `ExerciseEstimatorSimulationTest`; mechanically update `ReplayEngineTest`, `ReplayHistoryTest`, `ReplayProjectionTest`, `PolicyStateBuilderTest`, `ExerciseProgressionSeriesBuilderTest`, `derived` store tests, androidTest `WorkoutSessionControllerTest` (constructor/assertion renames only — keep scenarios).
  11. `ProdBssPrescriptionTest`: rebuild setup on beliefs; both tests assert the SAFETY property for now: prescribed weight > 0 and ≤ 30 lb (in kg via `WeightUnit.LBS.toKg(30f) + 1e-3f`). Add `// Task 7 re-pins the exact value.` [AMENDED during Task 5: was ≤ 20 lb — unsatisfiable at Task 5; both paths measured 30.0 lb. CORRECTED post-review 2026-07-08: 30 lb is the pooled projector value passing through a neutral policy — the failure ceiling is inert here (it would give ~35 lb if it bound). See amended Bridge Decision №8.]

- [ ] **Step 4: Full unit suite — PASS.** `./gradlew :app:testDebugUnitTest`. Also `./gradlew :app:assembleDebug` (androidTest compile check: `./gradlew :app:compileDebugAndroidTestKotlin`).

- [ ] **Step 5: Commit** `jj commit -m "phase2: belief swap — per-set censored folds in replay, muscle clock, old estimator deleted"`.

---

### Task 6: Chart continuity — session dots, σ band

**Files:**
- Modify: `SessionObservations.kt` (created in Task 5; verify + document), `ExerciseProgressionSeriesBuilder.kt` (σ band series), `ui/debug/ExerciseCoefficientDetailViewModel.kt` + `ui/debug/components/ExerciseProgressionChart.kt` (BAND color role), `ui/exercises/ExerciseDetailViewModel.kt` (already switched in Task 5 — verify)
- Test: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/SessionObservationsTest.kt`, update `ExerciseProgressionSeriesBuilderTest.kt`

**Interfaces:**
- Produces: `fun impliedSessionE1rm(sets: List<WorkoutSet>, config: EstimatorConfig = EstimatorConfig()): Float?`; `ExerciseProgressionSeries` gains `ownBandUpper: List<ProgressionPoint>` and `ownBandLower: List<ProgressionPoint>`; `ProgressionColorRole.BAND`.

- [ ] **Step 1: Write failing tests**

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionObservationsTest {
    private fun set(n: Int, fb: SetFeedback?, w: Float = 60f, reps: Int = 10, actual: Int? = null) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = n, targetWeight = w, targetReps = reps, actualReps = actual, feedback = fb)

    @Test
    fun onTargetSessionImpliesRoughlyTheTargetCapacity() {
        val implied = impliedSessionE1rm(listOf(set(1, SetFeedback.RIR_0_1), set(2, SetFeedback.RIR_0_1), set(3, SetFeedback.RIR_0_1)))!!
        val target = DefaultProgressionEngine.rawToOneRepMax(60f, 10)
        // RIR_0_1 = [target, target+2) on a fresh basis: implied sits at/above target, within ~12%.
        assertTrue("implied $implied vs target $target", implied >= target * 0.98f && implied <= target * 1.15f)
    }

    @Test
    fun failuresDragTheImpliedCapacityDown() {
        val clean = impliedSessionE1rm(listOf(set(1, SetFeedback.RIR_0_1)))!!
        val failed = impliedSessionE1rm(listOf(set(1, SetFeedback.TOO_HARD, actual = 5)))!!
        assertTrue(failed < clean)
    }

    @Test
    fun sessionsWithoutLoadSignalYieldNoDot() {
        assertNull(impliedSessionE1rm(listOf(set(1, SetFeedback.HURT))))
        assertNull(impliedSessionE1rm(listOf(set(1, null))))
        assertNull(impliedSessionE1rm(emptyList()))
        assertNull(impliedSessionE1rm(listOf(set(1, SetFeedback.RIR_0_1, w = 0f))))
    }

    @Test
    fun dotIsIndependentOfPriorHistory() {
        // Broad-prior fold: two identical set lists give identical dots regardless of call order.
        val sets = listOf(set(1, SetFeedback.RIR_2_4), set(2, SetFeedback.TOO_HARD, actual = 8))
        assertEquals(impliedSessionE1rm(sets)!!, impliedSessionE1rm(sets)!!, 1e-6f)
    }
}
```

`ExerciseProgressionSeriesBuilderTest`: extend the existing scenarios to assert `ownBandUpper`/`ownBandLower` bracket `ownEstimate` (upper > own > lower pointwise) and are emitted only for sessions where the own belief exists.

- [ ] **Step 2: Verify `SessionObservations.kt`** (Task 5 Step 3.6 created it; confirm it matches this reference exactly — fix in place if it drifted):

```kotlin
package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import kotlin.math.ln

/**
 * The session's implied fresh-1RM for one exercise: a broad-prior fold of the session's set
 * observations. Chart-dot analogue of the deleted aggregateSession — "what did this session's
 * sets say", independent of prior history (prior σ² = 1 ≈ uninformative). Returns null when no
 * set carries a load observation.
 */
fun impliedSessionE1rm(sets: List<WorkoutSet>, config: EstimatorConfig = EstimatorConfig()): Float? {
    val ordered = sets.sortedBy { it.setNumber }
    val anchor = ordered.firstOrNull {
        it.targetWeight > 0f && it.feedback != null && it.feedback != SetFeedback.HURT
    } ?: return null
    val updater = BeliefUpdater(config)
    var belief = ExerciseBelief(
        mu = ln(DefaultProgressionEngine.rawToOneRepMax(anchor.targetWeight, anchor.targetReps)),
        sigma2 = 1f,
        updatedAt = 0L,
    )
    var folded = false
    ordered.forEachIndexed { i, set ->
        val obs = SetObservation.from(set, fatigueRank = i + 1, config = config) ?: return@forEachIndexed
        belief = if (obs.gaussianLn != null) {
            updater.foldGaussian(belief, obs.gaussianLn, obs.noiseSd, at = 0L, muscleLastObs = null)
        } else {
            updater.foldCensored(belief, obs.lowerLn, obs.upperLn, obs.noiseSd, at = 0L, muscleLastObs = null)
        }
        folded = true
    }
    return if (folded) belief.e1rm else null
}
```

**Clamp caveat:** `foldGaussian`/`foldCensored` clamp σ² to `[σ_min², σ_max²]` — σ_max = 0.30 < the broad prior 1.0. The FIRST fold therefore clamps the posterior variance to σ_max² (fine — subsequent folds still work; the mean update uses the unclamped gain). Accept this; do not special-case.

- [ ] **Step 3: σ band.** `SessionSample` + `ExerciseProgressionSeries` gain `ownBandUpper`/`ownBandLower`; in `sampleSession`:

```kotlin
    val belief = snapshot.currentBeliefs[targetId]
    val ownEstimate = belief?.let { listOf(ProgressionPoint(asOf, exp(it.mu))) } ?: emptyList()
    val ownBandUpper = belief?.let { listOf(ProgressionPoint(asOf, exp(it.mu + it.sigma))) } ?: emptyList()
    val ownBandLower = belief?.let { listOf(ProgressionPoint(asOf, exp(it.mu - it.sigma))) } ?: emptyList()
```

Thread through the builder's accumulation loop and `ExerciseProgressionSeries`. In `ExerciseCoefficientDetailViewModel`, append two `ProgressionChartSeries(label = "±σ", style = LINE, colorRole = ProgressionColorRole.BAND)` entries (find where the existing own/siblings/merged series are assembled and mirror it). In `ExerciseProgressionChart.kt`: add `BAND` to `ProgressionColorRole` and map it in `progressionColors()` to `MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)`. **Keep `sharedProgressionYRange` computed from the existing three lines only** (do not let a wide band blow up the shared Y range; check `ChartRange.kt` and leave its inputs unchanged).

- [ ] **Step 4: Run — PASS** (`SessionObservationsTest`, `ExerciseProgressionSeriesBuilderTest`, full unit suite).

- [ ] **Step 5: Commit** `jj commit -m "phase2: chart continuity — broad-prior session dots + debug sigma band"`.

---

### Task 7: Policy activation — z/δ/fatigue discount, PooledBelief input

**Files:**
- Modify: `domain/policy/PrescriptionPolicy.kt`, `domain/WorkoutRepository.kt` (`buildPlanner`), test `domain/backtest/BacktestHarness.kt` (`replayPolicyPrescriptions`), `domain/progression/ExerciseBelief.kt` (`EstimatorConfig` z/δ defaults)
- Test: extend `domain/policy/PrescriptionPolicyTest.kt`; re-pin `ProdBssPrescriptionTest.kt`; update `WorkoutPlannerTest.kt`/`WorkoutPlannerOverrideTest.kt` helpers

**Interfaces:**
- Produces:
  ```kotlin
  data class PooledBelief(val e1rm: Float, val sigma: Float)  // in PrescriptionPolicy.kt
  class PrescriptionPolicy(private val pooled: Map<Long, PooledBelief>, /* rest unchanged */)
  ```
- `EstimatorConfig.overloadDelta` default → `0.01f`; `uncertaintyZ` default → `0.5f` (Task 9 may re-tune, then pins).

- [ ] **Step 1: Write failing tests** (add to `PrescriptionPolicyTest`; update the file's policy factory to build `PooledBelief` maps — the EXISTING phase-1 tests must keep their pinned arithmetic exactly, so give them `sigma = 0f` and a fully-neutral base-target config `EstimatorConfig(uncertaintyZ = 0f, overloadDelta = 0f, fatiguePerSet = 0f)`: they pin ceiling/HURT/rounding semantics, not the base target, and the new fatigue discount would otherwise shift (and in the near-cap cases unbind) their scenarios. The NEW tests exercise the activated defaults):

```kotlin
    @Test
    fun uncertaintyShadesThePrescriptionDown() {
        val certain = policy(pooled = mapOf(1L to PooledBelief(100f, 0.02f))).prescribe(exercise(1L), 10)!!
        val uncertain = policy(pooled = mapOf(1L to PooledBelief(100f, 0.25f))).prescribe(exercise(1L), 10)!!
        assertTrue("uncertain $uncertain must be below certain $certain", uncertain < certain)
    }

    @Test
    fun fatigueDiscountTargetsTheLastSet() {
        // z=0, δ=0 isolates the discount: weight ≈ fromOneRepMax(pooled × (1 − φ·2), reps).
        val config = EstimatorConfig(uncertaintyZ = 0f, overloadDelta = 0f)
        val p = policy(pooled = mapOf(1L to PooledBelief(100f, 0f)), config = config)
        val expected = WeightFormatter.round(
            DefaultProgressionEngine.fromOneRepMax(100f * (1f - config.fatiguePerSet * 2f), 10), WeightUnit.KG)
        assertEquals(expected, p.prescribe(exercise(1L), 10)!!, 1e-3f)
    }

    @Test
    fun steadyStateShadingAndDeltaRoughlyCancel() {
        // At σ = σ_min the default z·σ (1%) ≈ δ (1%): prescription ≈ pure fatigue-discounted pooled.
        val config = EstimatorConfig()
        val p = policy(pooled = mapOf(1L to PooledBelief(100f, config.sigmaMin)), config = config)
        val neutral = policy(pooled = mapOf(1L to PooledBelief(100f, 0f)),
            config = EstimatorConfig(uncertaintyZ = 0f, overloadDelta = 0f))
        assertEquals(neutral.prescribe(exercise(1L), 10)!!, p.prescribe(exercise(1L), 10)!!, 2.5f + 1e-3f)
    }

    @Test
    fun ceilingStillClampsTheShadedTarget() {
        // Pooled far above a clear ceiling: cap binds after shading; round-down rule intact.
        // (Mirror the existing hurtCompoundsUnderTheCeiling test's setup with PooledBelief input.)
        ...construct exactly as the existing near-cap test, expecting the same rounded-down weight...
    }
```

(For the last test: copy `nearCapTargetWithoutHurtAlsoStaysBelowTheFailedWeight`'s arithmetic, adjusting the pooled value so the pre-clamp target — now including −z·σ + δ + fatigue — still exceeds the cap; assert the same strictly-below-failed-weight outcome.)

- [ ] **Step 2: Implement.** `PrescriptionPolicy` changes only its input type and base-target line:

```kotlin
data class PooledBelief(val e1rm: Float, val sigma: Float)

class PrescriptionPolicy(
    private val pooled: Map<Long, PooledBelief>,
    private val state: PolicyState,
    private val config: EstimatorConfig = EstimatorConfig(),
    private val progressionEngine: ProgressionEngine,
    private val weightUnit: WeightUnit,
    private val nowMs: Long,
) {
    fun prescribe(exercise: Exercise, sessionReps: Int): Float? {
        val p = pooled[exercise.id] ?: return null
        if (p.e1rm <= 0f) return null

        // Base target (spec §4 items 1–2): shade by uncertainty, push by δ, then discount to the
        // LAST set — beliefs are fresh capacity; the last set is the one targeted at RIR 0–1.
        // In steady state (σ→σ_min) z·σ ≈ δ cancel and the discount offsets the fresh basis, so
        // the net prescription matches the phase-1 feel (Bridge Decision №3).
        val fatigueLn = ln(1f - config.fatiguePerSet * (PlannedExercise.DEFAULT_SETS - 1))
        var targetE1rm = exp(ln(p.e1rm) - config.uncertaintyZ * p.sigma + config.overloadDelta + fatigueLn)

        // ... failure ceiling, HURT, rounding: UNCHANGED from phase 1 (verbatim) ...
    }
}
```

(`import io.github.fowles.stochastic_strength.domain.model.PlannedExercise`.) `buildPlanner` — replace the projection flatMap with an explicit per-muscle loop and zip means with sigmas:

```kotlin
        val policyState = derivedState.snapshot().policyState()
        val projector = MuscleStrengthProjector()
        val pooled = mutableMapOf<Long, PooledBelief>()
        for ((muscle, ids) in muscleIds) {
            val proj = projector.project(beliefs, seedCoef, ids, now, policyState.muscleLastObs[muscle])
            for ((id, e1rm) in proj.effectiveE1rm) {
                pooled[id] = PooledBelief(e1rm, proj.pooledSigma[id] ?: 0f)
            }
        }
```

`BacktestHarness.replayPolicyPrescriptions`: same zip (per-muscle loop already exists there), `builder.build(snap.muscleLastObs.toMap())`, `PooledBelief` map into the policy. `WorkoutPlannerTest`/`WorkoutPlannerOverrideTest`: update only the policy-construction helper (PooledBelief with sigma 0f keeps every planner scenario's arithmetic identical); the seven sore-muscle test bodies stay untouched.

- [ ] **Step 3: Re-pin ProdBss.** Restore exact-value assertions in both `ProdBssPrescriptionTest` tests by running them and pinning the observed weight. Spec §9 expects ≈ 20 lb. If the observed value is NOT 20 lb (LBS grid), STOP: report DONE_WITH_CONCERNS with the observed value and the pre/post-clamp targets for adjudication — do not pin silently.

- [ ] **Step 4: Run — PASS:** `PrescriptionPolicyTest`, `ProdBssPrescriptionTest`, `WorkoutPlannerTest`, `WorkoutPlannerOverrideTest`, then the full unit suite (BacktestComparisonTest skips without fixtures on other machines; on this machine it may FAIL until Task 9 re-pins the band — if it fails here, note the worst delta in the report; do not touch BAND).

- [ ] **Step 5: Commit** `jj commit -m "phase2: policy activation — z-shading, overload delta, last-set fatigue discount"`.

---

### Task 8: Detraining dialog deleted; automatic drift + layoff notice

**Files:**
- Delete: `domain/DetrainingModel.kt`, `ui/workout/DetrainingDialog.kt`, test `domain/DetrainingModelTest.kt`
- Modify: `ui/workout/WorkoutState.kt` (drop `PlanPreview.detraining` + `DetrainingPrompt`), `ui/workout/WorkoutSessionController.kt` (drop `maybeOfferDetraining`/`applyDetraining`/`skipDetraining` + call sites; `buildPlanner(...)` call at ~line 264 drops `plan.detrainOverrides +`), `ui/workout/WorkoutViewModel.kt` (drop the two delegations), `ui/workout/WorkoutScreen.kt` (drop the dialog block ~lines 105–111; add the notice line), `domain/model/WorkoutPlan.kt` (drop `detrainOverrides`/`effectiveOverrides`, add `layoffEasedFraction`), `domain/WorkoutRepository.kt` (delete `applyDetrainingReduction`; `buildPlanner` computes the eased-fraction map), `domain/WorkoutPlanner.kt` (new ctor params, notice computation)
- Modify tests: `domain/model/WorkoutPlanTest.kt`, `WorkoutPlannerTest.kt` (add notice test), androidTest `WorkoutSessionControllerTest.kt` + `WorkoutRepositoryTest.kt` (remove detraining scenarios)

**Interfaces:**
- `WorkoutPlan(..., val layoffEasedFraction: Float? = null)`; `effectiveOverrides` deleted — call sites use `exerciseOverrides` directly.
- `WorkoutPlanner(..., private val muscleEasedFraction: Map<MuscleGroup, Float> = emptyMap(), private val layoffNoticeThreshold: Float = EstimatorConfig().noticeThresholdFraction)`.
- Historical DETRAIN override rows keep replaying unchanged (`ExerciseStrengthOverride` + `BaselineChangeReason.DETRAIN` untouched — data model, not behavior).

- [ ] **Step 1: Write failing planner-notice test** (add to `WorkoutPlannerTest`):

```kotlin
    @Test
    fun layoffNoticeAppearsOnlyWhenAPlannedMuscleEasedPastTheThreshold() {
        val eased = planner(muscleEasedFraction = mapOf(MuscleGroup.QUADS to 0.06f))
            .generateWorkout(sessionReps = 10)
        if (eased.exercises.any { it.exercise.primaryMuscle == MuscleGroup.QUADS && it.sessionWeight > 0f }) {
            assertEquals(0.06f, eased.layoffEasedFraction)
        }
        val fresh = planner(muscleEasedFraction = emptyMap()).generateWorkout(sessionReps = 10)
        assertNull(fresh.layoffEasedFraction)
        val subThreshold = planner(muscleEasedFraction = mapOf(MuscleGroup.QUADS to 0.01f))
            .generateWorkout(sessionReps = 10)
        assertNull(subThreshold.layoffEasedFraction)
    }
```

(Adapt to the file's existing `planner(...)` helper — add the new parameter with an `emptyMap()` default so no other test changes. Construct the eased-case planner with an all-QUADS `availableExercises` list — mirror how the file's other tests build exercise lists — so the `if` guard never goes vacuous.)

- [ ] **Step 2: Implement.** In `WorkoutPlanner.generateWorkout(sessionReps)`:

```kotlin
    fun generateWorkout(sessionReps: Int): WorkoutPlan {
        val plannable = availableExercises.filter { muscleGroupRested(it) }
        val exercises = WorkoutGenerator.generate(WorkoutGenerator.Input(plannable, random))
            .map { withWeight(it, sessionReps) }
        val eased = exercises.filter { it.sessionWeight > 0f }
            .mapNotNull { muscleEasedFraction[it.exercise.primaryMuscle] }
            .maxOrNull() ?: 0f
        return WorkoutPlan(
            exercises = exercises,
            locationId = locationId,
            sessionReps = sessionReps,
            layoffEasedFraction = eased.takeIf { it >= layoffNoticeThreshold },
        )
    }
```

`WorkoutRepository.buildPlanner` computes the map from the muscle clock (mirror of `BeliefUpdater.age`'s drift arm, expressed as a fraction):

```kotlin
        val config = EstimatorConfig()
        val muscleEased = muscleIds.keys.associateWith { m ->
            val last = policyState.muscleLastObs[m] ?: return@associateWith 0f
            val idleMs = (now - (last + config.detrainGraceMs)).coerceAtLeast(0L)
            val drift = minOf(config.detrainRatePerWeek * (idleMs.toFloat() / WEEK_MS), config.detrainCap)
            1f - exp(-drift)
        }
```

(`private const val WEEK_MS = 7f * 24 * 60 * 60 * 1000` file-level in `WorkoutRepository.kt`.) Pass `muscleEasedFraction = muscleEased` to the planner. In `WorkoutScreen`'s PlanPreview content (where the detraining dialog block was), add:

```kotlin
                    s.plan.layoffEasedFraction?.let { f ->
                        Text(
                            "Weights eased ~${(f * 100).roundToInt()}% after the break",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
```

Then the deletion sweep (Files list above): remove the dialog, prompt, controller/VM methods, `WorkoutPlan.detrainOverrides`/`effectiveOverrides` (call sites use `exerciseOverrides`), `applyDetrainingReduction`, and the two androidTest scenario groups. `startFirstExercise` keeps only `applyManualExerciseOverrides`.

- [ ] **Step 3: Run — PASS:** `WorkoutPlannerTest`, `WorkoutPlanTest`, full unit suite; `./gradlew :app:compileDebugAndroidTestKotlin`.

- [ ] **Step 4: Commit** `jj commit -m "phase2: detraining dialog deleted — automatic drift + passive layoff notice"`.

---

### Task 9: Simulation rewrite + tuning (z, δ, poolObsVar pinned)

**Files:**
- Create: `app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/BeliefSimulationTest.kt` (replaces the deleted `ExerciseEstimatorSimulationTest`)
- Modify (pin only): `EstimatorConfig` defaults for `uncertaintyZ`, `overloadDelta`, `poolObsVar`

**The harness.** Reuse the deleted test's synthetic-lifter frame (`git show 92205abd:app/src/test/java/io/github/fowles/stochastic_strength/domain/progression/ExerciseEstimatorSimulationTest.kt` has the reference `achievableReps`/`feedbackFor`/library setup) with these changes:

1. The lifter's `fatiguePerSet` stays 0.03f — it now EQUALS `EstimatorConfig.fatiguePerSet`, i.e. the estimator's fatigue model matches the simulated truth.
2. Prescription goes through the FULL production path per session: `PolicyStateBuilder` accumulated per session (`builder.onSession(asOf, sets, snapshot)`; rebuild `PolicyState` via `builder.build(snapshot.muscleLastObs.toMap())`), projector → `PooledBelief` map → `PrescriptionPolicy(pooled, state, config, DefaultProgressionEngine, KG, now).prescribe(exercise, reps)`. A ReplaySnapshot per run carries beliefs + muscle clock; folds via `SessionProgressionStepper`.
3. Steady-state target for error metrics = the LAST set's effective 1RM: truth × (1 − φ·(S−1)) — unchanged definition, but compare against the POLICY's prescribed weight converted back: `err = |prescribedWeight − round(fromOneRepMax(target1RM, reps))| / ...` — simpler and just as pinning: compare `toOneRepMax(prescribedWeight, reps)` against `target1RM` relative error.
4. Reps drawn from [5, 10] (`RepRangePicker.pick(5, 10, rng)`) — the production rep range, not [1, 20]; the old test predates the rep-range slider.

**Match-feel pins (averaged over the same 8 seeds, 120+30 sessions, growth 0.002):**
- convergence (mean err over ever-trained ≤ 10%) within ≤ 12 sessions
- tail trained error ≤ 8%
- tail jitter ≤ 6%
- tail last-set continuous reserve in [0.0, 2.0]
- tail last-set fail rate ≤ 0.40
- static lifter (growth 0): finite metrics, tail trained error ≤ 8%
- first-session guard: on the very first session from cold seeds (0.8× truth), every prescribed weight ≥ 60% of the seed-implied last-set weight (guards runaway z-shading at σ_seed).

**New-pin scenario tests** (single muscle QUADS, 3 loaded lifts, reps = 10, seeded at truth unless stated; all multi-seed where random):

```kotlin
    @Test
    fun calibration_eightyPercentIntervalRoughlyCovers() {
        // Over the tail of the realistic run: for trained exercises (neff ≥ 1), the session's
        // implied observation (impliedSessionE1rm of its sets, in ln space) falls inside the
        // pre-session aged belief's 80% predictive interval μ ± 1.2816·√(σ² + poolObsVar)
        // for 60–95% of samples (loose tolerance: the implied dot is not a clean iid draw).
        ...collect (preMu, preSigma2, lnImplied) triples during the tail; assert coverage in 0.60..0.95...
    }

    @Test
    fun badDay_ceilingBlocksThenRecoversWithinTwoSessions() {
        // Converge 15 clean sessions; one fluke session at 0.80×truth (drop-cascade emerges);
        // then: (a) next prescription < the failed weight; (b) after 2 further clean sessions
        // the prescription is back within 5% of the pre-incident prescription.
    }

    @Test
    fun layoff_easedReturnAndFastReconvergence() {
        // Converge; idle 8 weeks (t += 56 d, no sessions); comeback prescription ≤ pre-gap
        // prescription; across seeds the comeback last-set fail fraction ≤ 0.25; error back
        // ≤ 10% within 3 sessions.
    }

    @Test
    fun censoredResponsiveness_underestimatedLifterConvergesInFourSessions() {
        // Beliefs seeded at 0.70×truth; honest RIR feedback (mostly RIR_5_PLUS early): prescribed
        // error ≤ 10% within 4 sessions — one-sided censored updates must carry real information.
    }
```

**Tuning protocol (the exploratory loop):** run the full class; if pins fail, adjust ONLY `uncertaintyZ`, `overloadDelta`, `poolObsVar` (bounded: z ∈ [0.25, 1.0], δ ∈ [0.005, 0.02], poolObsVar ∈ [5e-4, 8e-3]); re-run; iterate ≤ 6 rounds. If no setting passes, report BLOCKED with the best config + failing pins table. When green, pin the values as `EstimatorConfig` defaults with a `// Pinned by BeliefSimulationTest <date>` comment, run the FULL unit suite, and document the final values + rounds in the report.

- [ ] Step 1: Port the harness (policy-path prescriptions, per-set folds), no pins yet; smoke-run one seed.
- [ ] Step 2: Add the match-feel pins; run; tune per protocol.
- [ ] Step 3: Add the four scenario tests; run; tune per protocol (re-run match-feel after any change).
- [ ] Step 4: Pin final z/δ/poolObsVar in `EstimatorConfig`; full unit suite PASS.
- [ ] Step 5: Commit `jj commit -m "phase2: belief simulation harness — match-feel + calibration/bad-day/layoff/censored pins; z, delta, poolObsVar pinned"`.

---

### Task 10 (CONTROLLER-EXECUTED): Backtest re-pin

Not a subagent dispatch — needs the machine-local personal fixtures and delta-attribution judgment.

- [ ] Step 1: `./gradlew :app:testDebugUnitTest --tests "io.github.fowles.stochastic_strength.domain.backtest.BacktestComparisonTest"` — inspect the printed delta table (counts/aggregates only; no set-level data into the transcript).
- [ ] Step 2: Attribute every >2% delta to an intended phase-1/phase-2 semantic (ceiling, z-shading on high-σ exercises, drift on layoff gaps, fresh-basis/fatigue pairing, censored vs EMA folds). Anything unattributable = investigate before pinning.
- [ ] Step 3: Pin `BAND = worst + 0.05` with an attribution comment (replacing the phase-1 comment; note phase-1's 0.19 in it). Re-run — green.
- [ ] Step 4: Commit `jj commit -m "phase2: backtest band re-pinned after belief-swap delta attribution"`.

---

### Task 11: Documentation

**Files:**
- Modify: `docs/adaptation/` pages 02–04 (list the directory first; rewrite the estimator/signal/pooling pages to: beliefs (μ, σ², aging q + drift) / per-set censored observations (feedback table, fresh basis, λ noise) / bridge pooling (n_eff votes, gate, pooledSigma) — each page states what phase 3/4 will change), create `docs/adaptation/05-prescription-policy.md` (base target z/δ/fatigue, ceiling + adjudicated rounding, HURT decay, cooldown, layoff notice; policy-state provenance from replay)
- Modify: `CLAUDE.md` — replace the "### Progression system" section body to describe: `ExerciseBelief` beliefs + `BeliefUpdater` censored per-set folds (HURT → policy), muscle-keyed drift replacing the detraining dialog, bridge pooling (n_eff + gate, phase-3 pointer), `PrescriptionPolicy` base target `μ̃ − z·σ̃ + δ + ln(1−φ(S−1))` and the preserved ceiling/HURT/rounding order, `BeliefSimulationTest` as the pin, backtest band as the gate. Keep the section's length and tone; keep the surrounding sections untouched.
- Modify: spec §7 display-continuity line — replace "chart dots (the shared `impliedObservedSet` semantics) become the post-session belief mean per session" with "chart dots = the session's broad-prior implied observation (`impliedSessionE1rm`); the post-session belief mean is already the own-estimate line — dots keep showing what the session said. *(Amended during phase 2: dots equal to the line would carry no information.)*"
- Verify: `docs/adaptation/01-*` untouched; 06-fitting is phase 4 — do NOT create it.

- [ ] Step 1: Rewrite/create the pages per outline.
- [ ] Step 2: Cross-check every constant named in the docs against `EstimatorConfig` (post-Task-9 pinned values).
- [ ] Step 3: Commit `jj commit -m "phase2: adaptation docs + CLAUDE.md rewritten to the belief model; spec dot-semantics amendment"`.

---

### Phase close (controller)

- [ ] Full unit suite green; `./gradlew :app:lint` clean; `./gradlew :app:connectedAndroidTest` green (emulator).
- [ ] Plan checkboxes marked; ledger updated; memory updated. Version bump remains the user's call.

## Self-Review Notes (writing-plans checklist)

- Spec coverage: §1 (Task 1–2), §2 (Task 3, 5), §3 bridge-deferred (Task 4; phase 3 owns the real §3), §4 items 1–2 + 7 (Tasks 7–8; items 3–6 verbatim-preserved from phase 1), §6 defaults (Tasks 1, 7, 9), §7 code map + display continuity (Tasks 5–6, 11), §8 phase-2 bullet fully covered, §9 sim + backtest (Tasks 9–10), §10 docs (Task 11). Fitting (§5) is phase 4 — no task, intentional.
- Ordering cross-check (phase-1 lesson): prescribe = base(z, δ) → fatigue → ceiling → HURT → rounding, matching spec §4 numbering; fold = age-then-update per set in setNumber order (spec §2); drift window matches spec §1's overlap definition verbatim.
- Type consistency: `ExerciseBelief(mu, sigma2, updatedAt)`, `foldGaussian/foldCensored(..., at, muscleLastObs)`, `project(beliefs, seedCoef, ids, now, muscleLastObs)`, `PooledBelief(e1rm, sigma)`, `PolicyStateBuilder.build(muscleLastObs)` — used identically across Tasks 1–9.
