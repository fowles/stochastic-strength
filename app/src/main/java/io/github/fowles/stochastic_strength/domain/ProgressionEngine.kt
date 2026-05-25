package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.ExerciseState
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import kotlin.math.roundToInt

object ProgressionEngine {
    const val CONSECUTIVE_RIR5_FOR_SET_INCREASE = 2
    private const val PLATE_INCREMENT = 2.5f
    private const val MIN_SETS = 2
    private const val MAX_SETS = 4

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
            SetFeedback.RIR_3_5 -> state.copy(
                currentWeight = if (hasWeight) weightIncreased(state.currentWeight, 1.025f) else 0f,
                consecutiveRir5PlusSessions = 0,
            )
            SetFeedback.RIR_1_2 -> state.copy(
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

    // Priority: HURT > TOO_HARD > last RIR feedback (most fatigued set is most informative)
    private fun aggregateFeedback(feedbacks: List<SetFeedback>): SetFeedback {
        if (SetFeedback.HURT in feedbacks) return SetFeedback.HURT
        if (SetFeedback.TOO_HARD in feedbacks) return SetFeedback.TOO_HARD
        return feedbacks.last { it == SetFeedback.RIR_1_2 || it == SetFeedback.RIR_3_5 || it == SetFeedback.RIR_5_PLUS }
    }

    private fun weightIncreased(current: Float, factor: Float): Float {
        val scaled = roundToPlate(current * factor)
        return if (scaled > current) scaled else current + PLATE_INCREMENT
    }

    private fun weightDecreased(current: Float, factor: Float): Float {
        val scaled = roundToPlate(current * factor)
        return if (scaled < current) maxOf(PLATE_INCREMENT, scaled) else maxOf(PLATE_INCREMENT, current - PLATE_INCREMENT)
    }

    private fun roundToPlate(weight: Float): Float =
        (weight / PLATE_INCREMENT).roundToInt() * PLATE_INCREMENT
}
