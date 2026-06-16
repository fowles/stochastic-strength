package io.github.fowles.stochastic_strength.domain

import kotlin.random.Random

object RepRangePicker {
    val ROUND_REPS: List<Int> = listOf(1, 2, 3, 5, 8, 10, 12, 15, 18, 20)

    fun candidates(min: Int, max: Int): List<Int> {
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        val rounds = ROUND_REPS.filter { it in lo..hi }
        return (rounds + lo + hi).distinct().sorted()
    }

    fun pick(min: Int, max: Int, random: Random): Int =
        candidates(min, max).random(random)
}
