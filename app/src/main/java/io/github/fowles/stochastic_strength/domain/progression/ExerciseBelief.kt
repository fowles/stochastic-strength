package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.Equipment
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
    /**
     * Clean variance (projector-evidence-gate): the variance this belief WOULD have if adaptation had
     * never inflated it. Drives pooling n_eff so adaptive σ-inflation isn't misread as "uninformed".
     * Folds update it from the UN-inflated prior; age() grows it like sigma2; adaptPrior never touches
     * it. Default = EstimatorConfig().sigmaSeed² (0.0625) for the default config; seed()/override() set
     * it precisely from config. Only ever the raw default on non-pooled constructions (chart broad-prior,
     * unit tests). Not persisted (in-memory derived, rebuilt by replay).
     */
    val evidenceVar: Float = 0.0625f,
) {
    val e1rm: Float get() = exp(mu)
    val sigma: Float get() = sqrt(sigma2)

    companion object {
        fun seed(e1rm: Float, at: Long, config: EstimatorConfig = EstimatorConfig()): ExerciseBelief =
            ExerciseBelief(mu = ln(e1rm), sigma2 = config.sigmaSeed * config.sigmaSeed, updatedAt = at,
                evidenceVar = config.sigmaSeed * config.sigmaSeed)

        fun override(e1rm: Float, at: Long, config: EstimatorConfig = EstimatorConfig()): ExerciseBelief =
            ExerciseBelief(mu = ln(e1rm), sigma2 = config.sigmaOverride * config.sigmaOverride, updatedAt = at,
                evidenceVar = config.sigmaOverride * config.sigmaOverride)
    }
}

/**
 * Tuning constants for the per-exercise belief estimator and the read-time pooling. All in one place;
 * pinned by BeliefSimulationTest (phase 2 Task 9).
 */
data class EstimatorConfig(
    /** Per-equipment-class transfer tightness τ (personal-offset std), pinned by BeliefSimulationTest.
     *  See [tauFor]. Barbell lifts track the muscle level tightly. */
    val tauBarbell: Float = 0.08f,
    /** τ for machines/cables — medium coupling to the muscle level. */
    val tauMachineCable: Float = 0.20f,
    /** τ for all other loaded classes (dumbbell/kettlebell/etc.) — loosest coupling. */
    val tauOtherLoaded: Float = 0.25f,
    /** λ₀: fixed precision of the seed anchor in the muscle-level pool. A
     *  thinly-evidenced muscle leans on it. Pinned by BeliefSimulationTest. */
    val levelAnchorPrecision: Float = 1.0f,
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
     * Light per-observation model-uncertainty floor (log-units), combined in quadrature with the
     * rep-derived noise. Keeps a single confident set from slamming σ onto the hard [sigmaMin] floor
     * (a mild fluke regularizer). It is deliberately SMALL: the real recovery from an over-collapsed
     * belief is the adaptive-attention re-opening ([adaptRunThreshold]), not a wide static floor.
     * Tuned down 0.08→0.02 on 2026-07-09 because the calibration gate (BeliefSimulationTest, on the
     * model-matched synthetic lifter) showed a larger floor over-widened every belief into
     * over-coverage. Fit to the calibration + ProdBss gates.
     */
    val obsModelSd: Float = 0.02f,
    /** Layoff notice threshold (fraction of strength eased). */
    val noticeThresholdFraction: Float = 0.03f,
    /**
     * Adaptive attention (innovation-covariance matching). The filter re-inflates its prior variance
     * only once the standardized-innovation run — a signed sum of consecutive one-signed surprises —
     * exceeds [adaptRunThreshold] (in std units). Below it a lone surprise is treated as noise, so
     * one bad set cannot yank the belief; above it a *consistent* run (the belief is wrong, not the
     * observation noisy) re-opens σ so the clear signal lands. Symmetric up/down. Tuning surface fit
     * to the ProdBss + BeliefSimulation gates; pinned by BeliefSimulationTest.
     * Tuned 2026-07-09 (with the projector evidence gate): 3.5→2.5 / 1.0→2.0 so the prod-BSS belief
     * fully adopts its clean set-3 RIR_0_1 evidence (own → ~19.05 kg, mid-interval [17.9, 19.5]) →
     * demonstrated 20 lb. The synthetic lifter's innovations stay well below 2.5 (its fatigue matches
     * the model), so adaptation still rarely fires on well-behaved histories.
     */
    val adaptRunThreshold: Float = 2.5f,
    /** Prior-variance multiplier added per (run-excess-over-threshold)², i.e. inflate = 1 + g·excess². */
    val adaptInflationPerExcess: Float = 2.0f,
    /** Decay applied to the run when an observation lands on-belief (no surprise), so it fades toward 0. */
    val adaptRunDecay: Float = 0.5f,
)

/** τ for an exercise's equipment class; unknown/other-loaded → the loosest class. */
fun EstimatorConfig.tauFor(equipment: Equipment?): Float = when (equipment) {
    Equipment.BARBELL -> tauBarbell
    Equipment.MACHINE, Equipment.CABLE_MACHINE -> tauMachineCable
    else -> tauOtherLoaded
}
