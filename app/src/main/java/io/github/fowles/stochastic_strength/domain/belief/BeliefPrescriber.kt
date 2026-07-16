package io.github.fowles.stochastic_strength.domain.belief

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Raw prescription target from an effective belief (spec Phase 2). Policy caps and grid rounding
 * apply downstream (PrescriptionPolicy.prescribe); the estimator itself is scored raw, pre-z.
 */
object BeliefPrescriber {
    /** `semantic`: prescribe at roughly the [PERCENTILE]th percentile of believed capacity (Φ(z) = 0.70). */
    const val Z = 0.5244f

    /** The percentile [Z] encodes — kept next to it so display text can't drift from the math. */
    const val PERCENTILE = 30

    fun targetE1rm(eff: EffectiveBelief): Float = exp(eff.mu - Z * sqrt(eff.sigma2))
}
