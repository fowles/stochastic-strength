package io.github.fowles.stochastic_strength.domain

import kotlin.math.pow

/**
 * Structural log-space compression of the coefficient guesses toward the per-muscle reference lift
 * (coef 1.0). `compress(guess, λ) = guess^λ`, so λ<1 shrinks the spread of log-coefficients by a
 * constant factor while pinning the anchors: bodyweight (0) and reference (1) exercises are
 * unchanged. λ is the single identifiable structural parameter (per-exercise refit is
 * underdetermined — see the Part B spec).
 */
object CoefficientCompression {
    fun compress(guess: Float, lambda: Float): Float = when {
        guess <= 0f -> 0f
        guess == 1f -> 1f
        else -> guess.pow(lambda)
    }

    fun compressAll(raw: Map<String, Float>, lambda: Float): Map<String, Float> =
        raw.mapValues { compress(it.value, lambda) }
}
