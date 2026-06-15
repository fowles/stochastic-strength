package io.github.fowles.stochastic_strength.domain

interface ProgressionEngine {
    val repOptions: List<Int>
    fun toOneRepMax(weight: Float, reps: Int): Float
    fun fromOneRepMax(oneRepMax: Float, reps: Int): Float
    fun scaleReps(weight: Float, from: Int, to: Int): Float
}
