package io.github.fowles.stochastic_strength.domain

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object DefaultProgressionEngine : ProgressionEngine {
    private const val INTERNAL_INCREMENT = 0.5f

    override val repOptions: List<Int> = listOf(5, 8, 10)
    val REP_OPTIONS get() = repOptions  // alias for external callers

    override fun toOneRepMax(weight: Float, reps: Int): Float = roundInternal(rawToOneRepMax(weight, reps))

    override fun fromOneRepMax(oneRepMax: Float, reps: Int): Float = roundInternal(rawFromOneRepMax(oneRepMax, reps))

    override fun scaleReps(weight: Float, from: Int, to: Int): Float = roundInternal(rawFromOneRepMax(rawToOneRepMax(weight, from), to))

    internal fun rawToOneRepMax(weight: Float, reps: Int): Float = rawToOneRepMax(weight, reps.toFloat())

    override fun rawToOneRepMax(weight: Float, reps: Float): Float {
        if (weight <= 0f || reps <= 1f) return weight
        val denom = -2.55f + 4.58f * ln(weight)
        if (denom <= 0f) return weight * (1f + reps / 30f)
        return weight * (1f + (reps - 1f).pow(0.85f) / denom)
    }

    override fun rawFromOneRepMax(oneRepMax: Float, reps: Int): Float {
        if (oneRepMax <= 0f || reps <= 1) return oneRepMax
        val k = (reps - 1).toFloat().pow(0.85f)
        val epley = oneRepMax / (1f + reps / 30f)
        var w = epley
        for (i in 0 until 3) {
            val denom = -2.55f + 4.58f * ln(w)
            if (denom <= 0f) return epley
            val fprime = 1f + k * (denom - 4.58f) / (denom * denom)
            if (fprime <= 0f) return epley
            w -= (w * (1f + k / denom) - oneRepMax) / fprime
            if (w <= 0f) return epley  // Newton overshot past zero; fall back to Epley
        }
        return w
    }

    internal fun roundInternal(weight: Float): Float =
        (weight / INTERNAL_INCREMENT).roundToInt() * INTERNAL_INCREMENT
}
