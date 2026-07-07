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
