package io.github.fowles.stochastic_strength.domain

import kotlin.math.pow

/**
 * Structural log-space compression of the coefficient guesses toward the per-muscle reference lift
 * (coef 1.0). `compress(guess, λ) = guess^λ`, so λ<1 shrinks the spread of log-coefficients by a
 * constant factor while pinning the anchors: bodyweight (0) and reference (1) exercises are
 * unchanged. λ is the single identifiable structural parameter (per-exercise refit is
 * underdetermined — see the Part B spec).
 *
 * This is a test-only analysis tool. Production ships the already-baked result (see
 * [io.github.fowles.stochastic_strength.domain.ExerciseCoefficients]); the λ sweep
 * (`CoefExponentFitTest`) and the `ExerciseCoefficientsTest` consistency guard use this to
 * regenerate and verify that table.
 */
object CoefficientCompression {
    /** The exponent the shipped `ExerciseCoefficients` table was baked at (`coef = guess^BAKED_LAMBDA`). */
    const val BAKED_LAMBDA: Float = 0.75f

    fun compress(guess: Float, lambda: Float): Float = when {
        guess <= 0f -> 0f
        guess == 1f -> 1f
        else -> guess.pow(lambda)
    }

    fun compressAll(raw: Map<String, Float>, lambda: Float): Map<String, Float> =
        raw.mapValues { compress(it.value, lambda) }
}
