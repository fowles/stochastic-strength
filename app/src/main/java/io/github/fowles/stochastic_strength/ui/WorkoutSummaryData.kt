package io.github.fowles.stochastic_strength.ui

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit

data class SummaryExercise(val name: String, val exerciseId: Long, val weight: Float, val feedback: List<SetFeedback?>)

data class WorkoutSummaryData(
    val startTime: Long,
    val durationSeconds: Long,
    val exercises: List<SummaryExercise>,
    val weightUnit: WeightUnit,
)
