package io.github.fowles.stochastic_strength.domain.backtest

import io.github.fowles.stochastic_strength.domain.progression.EstimatorConfig

/** Config builders for the study's candidate structures. Each returns a fresh copy; none mutate. */
object VarianceStudyConfigs {
    fun withProcNoise(base: EstimatorConfig, mult: Double): EstimatorConfig =
        base.copy(processNoisePerDay = (base.processNoisePerDay * mult).toFloat())

    fun withObsNoise(base: EstimatorConfig, mult: Double): EstimatorConfig = base.copy(
        repNoiseBucket = (base.repNoiseBucket * mult).toFloat(),
        repNoiseCounted = (base.repNoiseCounted * mult).toFloat(),
        repNoiseRel = (base.repNoiseRel * mult).toFloat(),
        obsModelSd = (base.obsModelSd * mult).toFloat(),
    )

    fun withTau(base: EstimatorConfig, mult: Double): EstimatorConfig = base.copy(
        tauBarbell = (base.tauBarbell * mult).toFloat(),
        tauMachineCable = (base.tauMachineCable * mult).toFloat(),
        tauOtherLoaded = (base.tauOtherLoaded * mult).toFloat(),
    )

    fun withAnchorPrecision(base: EstimatorConfig, mult: Double): EstimatorConfig =
        base.copy(levelAnchorPrecision = (base.levelAnchorPrecision * mult).toFloat())
}
