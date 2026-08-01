package io.github.fowles.stochastic_strength.domain.belief

import kotlin.math.exp

/**
 * One exercise's belief about ln(fresh 1RM, kg) — the whole estimator state (spec Phase 2).
 * [sigma2] is the variance in ln-units².
 */
data class Belief(val mu: Float, val sigma2: Float, val updatedAt: Long) {
    val e1rm: Float get() = exp(mu)
}

/**
 * Constant ledger for the belief stack (constitution rule 2 — every constant labeled).
 * The `fitted` values are adopted from BeliefFitTest's coordinate descent on real history;
 * sensitivity curves live in docs/superpowers/plans/2026-07-14-phase2-belief-core.md (appendix).
 */
data class BeliefConfig(
    /** `semantic`: a seed (initial override row) is trusted to roughly ±15%. */
    val sigmaSeed: Float = 0.15f,
    /** `semantic`: a deliberate user edit / detraining row is trusted a bit more, ±10%. */
    val sigmaOverride: Float = 0.10f,
    /**
     * `fitted` 2026-07-28 (re-fit on 34-session history): fractional fresh-capacity loss per prior
     * set. Genuine bowl, min at 0.03 — curve 0.0→35.998 0.01→35.638 0.02→35.374 0.03→35.250
     * 0.05→35.542 0.08→37.328 (held-out total, ln-units). Was 0.01 (fitted 2026-07-15).
     */
    val fatiguePerSetEstimate: Float = 0.03f,
    /** `fitted` 2026-07-15, curve in phase-2 plan appendix: sigma2 growth per idle day. */
    val confidenceDecayEstimate: Float = 3e-6f,
    /**
     * `edge-pinned`/`saturated` (re-confirmed 2026-07-15 on updated history): single observation
     * sigma for all load folds. Task 10 collapsed the RIR/FAIL pair (identical optima 0.005) into
     * one constant. Re-baseline widened the grid downward to 0.001/0.002 — all three lowest values
     * (0.001→24.3352, 0.002→24.3336, 0.005→24.3274) score within ~0.03% of best, so the low edge is a
     * saturated asymptote, not a genuine bowl. 0.005 kept as the least-extreme saturated value; curve
     * in the phase-2 plan appendix.
     */
    val perSetDoubtEstimate: Float = 0.005f,
    /** `fitted` 2026-07-15, curve in phase-2 plan appendix: transfer noise between same-muscle exercises in pooling. */
    val crossLiftIndependenceEstimate: Float = 0.2f,
    /** `flat` guard: sigma never collapses below ±2%. */
    val sigma2Floor: Float = 4e-4f,
    /** `flat` guard: sigma never exceeds ±50% (aging saturates). */
    val sigma2Cap: Float = 0.25f,
)
