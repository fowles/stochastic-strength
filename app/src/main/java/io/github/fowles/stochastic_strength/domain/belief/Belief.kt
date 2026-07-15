package io.github.fowles.stochastic_strength.domain.belief

import kotlin.math.exp

/**
 * One exercise's belief about ln(fresh 1RM, kg) — the whole estimator state (spec Phase 2).
 * [sigma2] is the variance in ln-units². Replaces ExerciseEstimate at the Phase-3 swap.
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
    /** `fitted`: fractional fresh-capacity loss per prior set of the same exercise (Task 10). */
    val phi: Float = 0.03f,
    /** `fitted`: sigma2 growth per idle day (Task 10). */
    val qPerDay: Float = 3e-4f,
    /** `fitted`: observation sigma for RIR-bucket folds (Task 10). */
    val sigmaObsRir: Float = 0.10f,
    /** `fitted`: observation sigma for TOO_HARD folds (Task 10). */
    val sigmaObsFail: Float = 0.07f,
    /** `fitted`: transfer noise between same-muscle exercises in pooling (Task 10). */
    val tau: Float = 0.15f,
    /** `flat` guard: sigma never collapses below ±2%. */
    val sigma2Floor: Float = 4e-4f,
    /** `flat` guard: sigma never exceeds ±50% (aging saturates). */
    val sigma2Cap: Float = 0.25f,
)
