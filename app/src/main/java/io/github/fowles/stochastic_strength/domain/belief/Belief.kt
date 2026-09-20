package io.github.fowles.stochastic_strength.domain.belief

import kotlin.math.exp

/**
 * One exercise's belief about ln(fresh 1RM, kg) — the whole estimator state (spec Phase 2).
 * [uncertainty] is the variance in ln-units².
 */
data class Belief(val bestGuessLn: Float, val uncertainty: Float, val updatedAt: Long) {
    val e1rm: Float get() = exp(bestGuessLn)
}

/**
 * Constant ledger for the belief stack (constitution rule 2 — every constant labeled).
 * The `fitted` values are adopted from BeliefFitTest's coordinate descent on real history;
 * sensitivity curves live in docs/superpowers/plans/2026-07-14-phase2-belief-core.md (appendix).
 */
data class BeliefConfig(
    /** `semantic`: a seed (initial override row) is trusted to roughly ±15%. */
    val seedUncertaintySd: Float = 0.15f,
    /** `semantic`: a deliberate user edit / detraining row is trusted a bit more, ±10%. */
    val overrideUncertaintySd: Float = 0.10f,
    /**
     * `fitted` 2026-09-20 (split-sample on the 55-session history): fractional fresh-capacity loss
     * per prior set. Chosen on the first 35 sessions ALONE (flat bowl 0.04–0.065, min 0.05), then
     * confirmed on the 20 sessions no fit had seen. Held-out per-set, first 35 | unseen 20:
     * 0.0→0.08563|0.09138 0.03→0.08110|0.08233 0.04→0.08025|0.07991 0.05→0.08009|0.07702
     * 0.065→0.08047|0.07365 0.08→0.08174|0.07125 0.10→0.08576|0.07238. The unseen slice alone
     * prefers 0.08, but that is in-sample for it. Was 0.03 (fitted 2026-07-28), 0.01 (2026-07-15).
     */
    val fatiguePerSetEstimate: Float = 0.05f,
    /** `fitted` 2026-07-15, curve in phase-2 plan appendix: uncertainty growth per idle day. */
    val confidenceDecayEstimate: Float = 3e-6f,
    /**
     * `edge-pinned`/`saturated` (re-confirmed 2026-07-15 on updated history): single observation
     * uncertainty for all load folds. RIR and FAIL sets share it (their separate optima were both
     * 0.005). Re-baseline widened the grid downward to 0.001/0.002 — all three lowest values
     * (0.001→24.3352, 0.002→24.3336, 0.005→24.3274) score within ~0.03% of best, so the low edge is a
     * saturated asymptote, not a genuine bowl. 0.005 kept as the least-extreme saturated value; curve
     * in the phase-2 plan appendix.
     */
    val perSetDoubtEstimate: Float = 0.005f,
    /** `fitted` 2026-07-15, curve in phase-2 plan appendix: transfer noise between same-muscle exercises in pooling. */
    val crossLiftIndependenceEstimate: Float = 0.2f,
    /** `flat` guard: uncertainty never collapses below ±2%. */
    val uncertaintyFloor: Float = 4e-4f,
    /** `flat` guard: uncertainty never exceeds ±50% (aging saturates). */
    val uncertaintyCap: Float = 0.25f,
)
