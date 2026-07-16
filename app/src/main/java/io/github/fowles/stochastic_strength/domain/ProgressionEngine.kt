package io.github.fowles.stochastic_strength.domain

interface ProgressionEngine {
    val repOptions: List<Int>
    fun toOneRepMax(weight: Float, reps: Int): Float
    fun fromOneRepMax(oneRepMax: Float, reps: Int): Float
    fun scaleReps(weight: Float, from: Int, to: Int): Float

    /** Un-rounded rep-max conversion; fractional reps allowed (log-space interval math). */
    fun rawToOneRepMax(weight: Float, reps: Float): Float

    /** Un-rounded inverse of [rawToOneRepMax] at integer reps. */
    fun rawFromOneRepMax(oneRepMax: Float, reps: Int): Float
}
