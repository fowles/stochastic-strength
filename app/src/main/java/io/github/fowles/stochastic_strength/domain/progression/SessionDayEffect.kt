package io.github.fowles.stochastic_strength.domain.progression

/**
 * The shared per-session "good-day/bad-day" random intercept d ~ N(0, σ_day²) (spec §3-4). Given the
 * session's per-set residuals about the offset-free predictions, returns the Gaussian posterior of d
 * by precision-weighted combination (independent obs). σ_day = 0 ⇒ N(0,0): the offset is pinned off
 * and the caller's fold is bit-identical to the no-day-effect model. Order-independent; transient.
 */
object SessionDayEffect {
    /** One set's evidence about d: value = obsLocation − offsetFreePredMean, obsVar = cleanVar + noiseSd². */
    data class Residual(val value: Float, val obsVar: Float)

    data class DayPosterior(val mean: Float, val variance: Float)

    fun estimate(sigmaDay: Float, observations: List<Residual>): DayPosterior {
        if (sigmaDay <= 0f) return DayPosterior(0f, 0f)
        var precision = 1f / (sigmaDay * sigmaDay)
        var precisionWeightedSum = 0f // = Σ value_i / obsVar_i (prior mean is 0)
        for (o in observations) {
            if (o.obsVar <= 0f) continue
            precision += 1f / o.obsVar
            precisionWeightedSum += o.value / o.obsVar
        }
        val variance = 1f / precision
        return DayPosterior(mean = variance * precisionWeightedSum, variance = variance)
    }
}
