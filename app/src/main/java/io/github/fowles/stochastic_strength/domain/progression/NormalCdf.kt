package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Standard-normal pdf/cdf via an Abramowitz–Stegun 7.1.26 erf approximation (|ε| ≤ 1.5e-7). */
object NormalCdf {
    fun erf(x: Float): Float {
        val sign = if (x < 0f) -1f else 1f
        val ax = abs(x.toDouble())
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
        return sign * (1.0 - poly * exp(-ax * ax)).toFloat()
    }

    fun pdf(x: Float): Float = (exp(-0.5 * x.toDouble() * x.toDouble()) / sqrt(2.0 * PI)).toFloat()

    fun cdf(x: Float): Float = 0.5f * (1f + erf(x / SQRT2))

    private val SQRT2 = sqrt(2.0).toFloat()
}
