package io.github.fowles.stochastic_strength.domain

import kotlin.random.Random

object RepRangePicker {
    val ROUND_REPS: List<Int> = listOf(1, 2, 3, 5, 8, 10, 12, 15, 18, 20)

    const val DEFAULT_MIN = 5
    const val DEFAULT_MAX = 10

    fun candidates(min: Int, max: Int): List<Int> {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        val rounds = ROUND_REPS.filter { it in lo..hi }
        return (rounds + lo + hi).distinct().sorted()
    }

    fun pick(min: Int, max: Int, random: Random): Int =
        candidates(min, max).random(random)

    /** A stable stand-in for "whatever the session picks": the middle candidate (lower middle on a tie). */
    fun typical(min: Int, max: Int): Int = candidates(min, max).let { it[(it.size - 1) / 2] }
}
