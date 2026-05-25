package io.github.fowles.stochastic_strength.domain.model

import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.ExerciseState

data class PlannedExercise(
    val exercise: Exercise,
    val state: ExerciseState,
)
