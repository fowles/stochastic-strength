package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.Exercise

interface CoefficientSource {
    fun get(exercise: Exercise): Float?
}
