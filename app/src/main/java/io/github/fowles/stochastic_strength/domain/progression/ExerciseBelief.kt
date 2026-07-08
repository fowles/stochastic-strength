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

/**
 * Tuning constants for the per-exercise belief estimator and the read-time pooling. All in one place;
 * pinned by BeliefSimulationTest (phase 2 Task 9).
 */
data class EstimatorConfig(
    /** Sibling-prior strength (kappa) in the read-time shrink: how many n_eff units the pool is worth. */
    val priorStrength: Float = 1.0f,
    /**
     * Effective sample size of the seed prior in the muscle-level pool. Every exercise votes with its
     * n_eff against this fixed-weight prior, so a thinly-evidenced muscle leans on the seed and a
     * stale lone voter decays back toward it. Pinned by BeliefSimulationTest.
     */
    val levelPrior: Float = 0.5f,
    /** Overload push δ (log-space). Activated in phase 2; Task 9 may re-tune. */
    val overloadDelta: Float = 0.01f,
    /** Uncertainty shading z. Activated in phase 2; Task 9 may re-tune. */
    val uncertaintyZ: Float = 0.5f,
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
    /** Bridge: per-observation variance defining n_eff pooling votes (phase 3 deletes). Sim-pinned. */
    val poolObsVar: Float = 2.0e-3f,
    /** Layoff notice threshold (fraction of strength eased). */
    val noticeThresholdFraction: Float = 0.03f,
)
