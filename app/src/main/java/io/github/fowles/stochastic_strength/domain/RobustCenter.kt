package io.github.fowles.stochastic_strength.domain

import kotlin.math.abs

/**
 * Weighted Huber M-estimator of location. Used both to estimate a muscle's shared (common-mode)
 * innovation within a session and to estimate collective coefficient drift across sessions, so that
 * a lone violent outlier is down-weighted while a genuine consensus is followed.
 *
 * Reduces exactly to the weighted mean when every residual is within [delta]; otherwise iteratively
 * reweights points by `min(1, delta/|residual|)`.
 */
object RobustCenter {
    fun of(values: List<Float>, weights: List<Float>, delta: Float, iterations: Int = 3): Float {
        if (values.isEmpty()) return 0f
        require(values.size == weights.size) { "values/weights size mismatch" }
        var wsum = 0.0
        var seedNum = 0.0
        for (i in values.indices) {
            wsum += weights[i].toDouble()
            seedNum += values[i].toDouble() * weights[i]
        }
        if (wsum <= 0.0) return 0f
        var m = seedNum / wsum
        repeat(iterations) {
            var num = 0.0
            var den = 0.0
            for (i in values.indices) {
                val r = abs(values[i] - m)
                val psi = if (r <= delta.toDouble() || r == 0.0) 1.0 else (delta.toDouble() / r)
                val w = weights[i].toDouble() * psi
                num += w * values[i]
                den += w
            }
            if (den <= 0.0) return m.toFloat()
            m = num / den
        }
        return m.toFloat()
    }
}
