package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise

class UserCoefficientSource(
    private val userCoefficients: Map<Long, Float>,
    private val fallback: CoefficientSource = ExerciseCoefficients,
) : CoefficientSource {
    override fun get(exercise: Exercise): Float? =
        userCoefficients[exercise.id] ?: fallback.get(exercise)
}
