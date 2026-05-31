package io.github.fowles.stochastic_strength.ui

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet

data class SummarySet(val setNumber: Int, val targetWeight: Float, val targetReps: Int, val feedback: SetFeedback?, val isTimed: Boolean = false)

fun WorkoutSet.toSummarySet(isTimed: Boolean) = SummarySet(setNumber, targetWeight, targetReps, feedback, isTimed)

data class SummaryExercise(val name: String, val exerciseId: Long, val sets: List<SummarySet>)

data class WorkoutSummaryData(
    val startTime: Long,
    val durationSeconds: Long,
    val exercises: List<SummaryExercise>,
    val weightUnit: WeightUnit,
)
