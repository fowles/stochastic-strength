package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig

/** Config builders for the study's candidate structures. Each returns a fresh copy; none mutate. */
object VarianceStudyConfigs {
    fun withProcNoise(base: EstimatorConfig, mult: Double): EstimatorConfig =
        base.copy(processNoisePerDay = (base.processNoisePerDay * mult).toFloat())
}
