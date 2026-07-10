package io.github.fowles.stochastic_strength.domain.progression

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
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

    /** Natural-log of the probability mass in [lowerLn, upperLn] under N(mean, sd²); null = unbounded.
     *  Standardized bounds clamped to ±6; mass floored at 1e-6 to keep the log finite. */
    fun intervalLogMass(mean: Float, sd: Float, lowerLn: Float?, upperLn: Float?): Float {
        val a = (if (lowerLn != null) (lowerLn - mean) / sd else -CLAMP).coerceIn(-CLAMP, CLAMP)
        val b = (if (upperLn != null) (upperLn - mean) / sd else CLAMP).coerceIn(-CLAMP, CLAMP)
        val z = (cdf(b) - cdf(a)).coerceAtLeast(MIN_MASS)
        return kotlin.math.ln(z)
    }

    private val SQRT2 = sqrt(2.0).toFloat()
    private const val CLAMP = 6f
    private const val MIN_MASS = 1e-6f
}
