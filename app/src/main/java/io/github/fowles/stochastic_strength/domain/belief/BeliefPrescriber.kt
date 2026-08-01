package io.github.fowles.stochastic_strength.domain.belief

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Raw prescription target from an effective belief (spec Phase 2). Policy caps and grid rounding
 * apply downstream (PrescriptionPolicy.prescribe); the estimator itself is scored raw, pre-caution.
 */
object BeliefPrescriber {
    /**
     * `semantic`: the caution level we aim for — prescribe a weight we believe you'll make about
     * [targetSuccessChance] of the time (here ~70%), not your median best-guess capacity. A
     * deliberate policy choice, not a fitted value.
     */
    const val targetSuccessChance = 0.70f

    /**
     * `semantic`: how far below the best guess we back off, counted in units of our own uncertainty
     * (standard deviations). The multiplier that realizes [targetSuccessChance]: backing off 0.5244
     * standard deviations lands on a weight with a ~70% chance of success. Kept next to
     * [targetSuccessChance] so the two views of the same choice can't drift apart.
     */
    const val cautionMargin = 0.5244f

    fun targetE1rm(eff: EffectiveBelief): Float = exp(eff.bestGuessLn - cautionMargin * sqrt(eff.uncertainty))
}
