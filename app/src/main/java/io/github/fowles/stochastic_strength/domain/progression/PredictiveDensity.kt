package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Predictive log-scores for one observation given a prediction N(predMeanLn, predVar), where predVar
 * already folds in the observation noise s². Used by the phase-4 fitter's scoring objective; the
 * censored branch reuses NormalCdf.intervalLogMass so it can never diverge from the fold's own Z.
 */
object PredictiveDensity {
    fun gaussianLogDensity(obsLn: Float, predMeanLn: Float, predVar: Float): Float {
        val d = (obsLn - predMeanLn).toDouble()
        return (-0.5 * ln(2 * PI * predVar) - d * d / (2 * predVar)).toFloat()
    }

    fun censoredLogMass(lowerLn: Float?, upperLn: Float?, predMeanLn: Float, predVar: Float): Float =
        NormalCdf.intervalLogMass(predMeanLn, sqrt(predVar), lowerLn, upperLn)
}
