package io.github.fowles.stochastic_strength.domain.progression

/**
 * Running sum of one-step-ahead predictive log-scores, fed by [SessionProgressionStepper] when a
 * candidate config is being scored during a fit replay. Predictive variance = clean own variance
 * (predCleanVar) + the observation's own noise s². Not thread-safe; one accumulator per fit eval.
 */
class PredictiveScoreAccumulator {
    var total: Double = 0.0
        private set

    fun accumulate(obs: SetObservation, predMeanLn: Float, predCleanVar: Float) {
        val v = predCleanVar + obs.noiseSd * obs.noiseSd
        total += if (obs.gaussianLn != null) {
            PredictiveDensity.gaussianLogDensity(obs.gaussianLn, predMeanLn, v).toDouble()
        } else {
            PredictiveDensity.censoredLogMass(obs.lowerLn, obs.upperLn, predMeanLn, v).toDouble()
        }
    }
}
