package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.ExerciseState
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import kotlin.math.roundToInt

object ProgressionEngine {
    const val CONSECUTIVE_RIR5_FOR_SET_INCREASE = 2
    private const val MIN_SETS = 2
    private const val MAX_SETS = 4
    private const val INTERNAL_INCREMENT = 0.5f // Use 0.5kg as internal resolution
    val REP_OPTIONS = listOf(5, 8, 10)

    fun computeNextState(state: ExerciseState, sessionFeedbacks: List<SetFeedback>): ExerciseState {
        if (sessionFeedbacks.isEmpty()) return state
        return applyFeedback(state, aggregateFeedback(sessionFeedbacks))
    }

    fun applyFeedback(state: ExerciseState, feedback: SetFeedback): ExerciseState {
        val hasWeight = state.currentWeight > 0f
        return when (feedback) {
            SetFeedback.RIR_5_PLUS -> {
                val newConsecutive = state.consecutiveRir5PlusSessions + 1
                val addSet = newConsecutive >= CONSECUTIVE_RIR5_FOR_SET_INCREASE && state.currentSets < MAX_SETS
                state.copy(
                    currentWeight = if (hasWeight) weightIncreased(state.currentWeight, 1.05f) else 0f,
                    currentSets = if (addSet) state.currentSets + 1 else state.currentSets,
                    consecutiveRir5PlusSessions = if (addSet) 0 else newConsecutive,
                )
            }
            SetFeedback.RIR_2_4 -> state.copy(
                currentWeight = if (hasWeight) weightIncreased(state.currentWeight, 1.025f) else 0f,
                consecutiveRir5PlusSessions = 0,
            )
            SetFeedback.RIR_0_1 -> state.copy(
                consecutiveRir5PlusSessions = 0,
            )
            SetFeedback.TOO_HARD -> state.copy(
                currentWeight = if (hasWeight) weightDecreased(state.currentWeight, 0.90f) else 0f,
                currentSets = maxOf(MIN_SETS, state.currentSets - 1),
                consecutiveRir5PlusSessions = 0,
            )
            SetFeedback.HURT -> state.copy(
                currentWeight = if (hasWeight) weightDecreased(state.currentWeight, 0.85f) else 0f,
                currentSets = maxOf(MIN_SETS, state.currentSets - 1),
                consecutiveRir5PlusSessions = 0,
            )
        }
    }

    private fun aggregateFeedback(feedbacks: List<SetFeedback>): SetFeedback {
        if (SetFeedback.HURT in feedbacks) return SetFeedback.HURT
        if (SetFeedback.TOO_HARD in feedbacks) return SetFeedback.TOO_HARD
        return feedbacks.last { it == SetFeedback.RIR_0_1 || it == SetFeedback.RIR_2_4 || it == SetFeedback.RIR_5_PLUS }
    }

    private fun weightIncreased(current: Float, factor: Float): Float {
        val scaled = roundInternal(current * factor)
        return if (scaled > current) scaled else roundInternal(current + INTERNAL_INCREMENT)
    }

    private fun weightDecreased(current: Float, factor: Float): Float {
        val scaled = roundInternal(current * factor)
        return if (scaled < current) maxOf(INTERNAL_INCREMENT, scaled) else maxOf(INTERNAL_INCREMENT, roundInternal(current - INTERNAL_INCREMENT))
    }

    fun scaleWeight(weight: Float, fromReps: Int, toReps: Int): Float {
        if (weight <= 0f || fromReps == toReps) return weight
        val oneRepMax = weight * (1f + fromReps / 30f)
        return roundInternal(oneRepMax / (1f + toReps / 30f))
    }

    private fun roundInternal(weight: Float): Float =
        (weight / INTERNAL_INCREMENT).roundToInt() * INTERNAL_INCREMENT
}
