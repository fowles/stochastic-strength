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
    val wUp: Float = 1.48f,
    /** Observation weight for a down-signal (fast tracking so a failed weight is not re-prescribed). */
    val wDown: Float = 3.0f,
    /** Down-signal weight at full bracketConfidence (demonstrated drop-cascade); interpolated from [wDown]. */
    val wDownSnap: Float = 8f,
    /** HURT multiplies the estimate by this factor. */
    val hurtFactor: Float = 0.85f,
    /** Sibling-prior strength (kappa) in the read-time shrink: how many confidence units the pool is worth. */
    val priorStrength: Float = 1.0f,
    /**
     * Effective sample size of the seed prior in the muscle-level pool. Every exercise votes with its
     * full decayed confidence against this fixed-weight prior, so a thinly-evidenced muscle leans on
     * the seed and a stale lone voter decays back toward it instead of defining the level. Pinned by
     * ExerciseEstimatorSimulationTest.
     */
    val levelPrior: Float = 0.5f,
    /** Overload push δ (log-space). Neutral in phase 1; activated and tuned in phase 2. */
    val overloadDelta: Float = 0f,
    /** Uncertainty shading z. Neutral in phase 1; needs pooled sigma (phase 2+). */
    val uncertaintyZ: Float = 0f,
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
)
