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
    /**
     * Augmented adaptive-filter state (spec §2 / adaptive-attention): a signed running sum of
     * standardized innovations while they stay one-signed. A large |innovationRun| means the belief
     * has been consistently surprised in one direction — the prior variance is understated — and the
     * fold re-inflates it (see [BeliefUpdater.adaptPrior]). Reset to 0 by seed/override. Not
     * persisted (in-memory derived state, rebuilt by replay).
     */
    val innovationRun: Float = 0f,
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

/**
 * Tuning constants for the per-exercise belief estimator and the read-time pooling. All in one place;
 * pinned by BeliefSimulationTest (phase 2 Task 9).
 */
data class EstimatorConfig(
    /**
     * Bridge transfer tightness (phase 2 only): the sibling-implied prediction enters the shrink
     * with the evidence a τ-noised transfer would earn — poolObsVar/τ² n_eff units (≈0.03) — which
     * is spec §3's blend with σ²_ℓLOO ≈ 0 and one uniform class. Keeps a fresh own measurement
     * from being pulled toward a mispredicting sibling level (the prod-BSS regression) while a
     * cold exercise (n_eff 0) still adopts the sibling prediction fully. Phase 3 replaces this
     * with per-equipment-class τ.
     */
    val tauBridge: Float = 0.25f,
    /**
     * Effective sample size of the seed prior in the muscle-level pool. Every exercise votes with its
     * n_eff against this fixed-weight prior, so a thinly-evidenced muscle leans on the seed and a
     * stale lone voter decays back toward it. Pinned by BeliefSimulationTest.
     */
    val levelPrior: Float = 0.5f,
    /**
     * Overload push δ (log-space). Forced up from 0.01 by the bad-day recovery pin: post-incident
     * RIR_5_PLUS bounds are weak, so δ is what lifts the recovered prescription back onto the
     * pre-incident grid point within the pinned session budget.
     * Pinned by BeliefSimulationTest 2026-07-08.
     */
    val overloadDelta: Float = 0.02f,
    /**
     * Uncertainty shading z. Forced down from 0.5 by the first-session guard: at σ_seed the
     * light-weight end of the 1RM formula amplifies e1rm shading ~3x in weight space, so
     * z ≥ ~0.45 collapses small-lift cold starts below 60% of their seed-implied weight.
     * Pinned by BeliefSimulationTest 2026-07-08.
     */
    val uncertaintyZ: Float = 0.4f,
    /** A CLEAR failure binds the ceiling at this fraction of the failed 1RM, with round-down. */
    val ceilingFactorClear: Float = 0.97f,
    /** Failure ceilings expire after this long (superseded earlier by any newer session). */
    val ceilingExpiryMs: Long = 28L * 24 * 60 * 60 * 1000,
    /** Immediate prescription reduction per HURT event (x(1 - depth) right after). */
    val hurtDepth: Float = 0.15f,
    /** HURT caution half-life. */
    val hurtHalfLifeMs: Long = 14L * 24 * 60 * 60 * 1000,
    /** Floor on the combined HURT multiplier. */
    val hurtFloor: Float = 0.6f,
    /** Sore-muscle planner cooldown window (was WorkoutPlanner.TWO_DAYS_MS). */
    val restCooldownMs: Long = 2L * 24 * 60 * 60 * 1000,
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
    /**
     * Irreducible per-observation uncertainty about FRESH 1RM (log-units, ≈ ±8%). Combined in
     * quadrature with the rep-derived noise so a single set — especially a low-rep failure where
     * the 1RM curve is flat and the rep-noise term is tiny — cannot drive σ to the floor and
     * deafen the filter. This is the "one session can't tell you fresh 1RM to ±2.5%" floor.
     */
    val obsModelSd: Float = 0.08f,
    /**
     * Bridge: per-observation variance defining n_eff pooling votes (phase 3 deletes). Default kept:
     * the calibration pin's coverage-vs-p table brackets it to ~[1.2e-3, 2.9e-3] and 2.0e-3 sits
     * mid-band. Pinned by BeliefSimulationTest 2026-07-08.
     */
    val poolObsVar: Float = 2.0e-3f,
    /** Layoff notice threshold (fraction of strength eased). */
    val noticeThresholdFraction: Float = 0.03f,
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
)
