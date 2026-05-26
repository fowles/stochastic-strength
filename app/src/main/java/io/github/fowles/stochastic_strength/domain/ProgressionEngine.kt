package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import kotlin.math.roundToInt

object ProgressionEngine {
    private const val INTERNAL_INCREMENT = 0.5f
    val REP_OPTIONS = listOf(5, 8, 10)

    fun computeNextBaseline(baseline: Float, feedbacks: List<SetFeedback>): Float {
        if (feedbacks.isEmpty()) return baseline
        return applyBaselineFeedback(baseline, aggregateFeedback(feedbacks))
    }

    fun applyBaselineFeedback(baseline: Float, feedback: SetFeedback): Float = when (feedback) {
        SetFeedback.RIR_5_PLUS -> weightIncreased(baseline, 1.05f)
        SetFeedback.RIR_2_4   -> weightIncreased(baseline, 1.025f)
        SetFeedback.RIR_0_1   -> baseline
        SetFeedback.TOO_HARD  -> weightDecreased(baseline, 0.90f)
        SetFeedback.HURT      -> weightDecreased(baseline, 0.85f)
    }

    // Conservative aggregation across multiple exercises in the same muscle group.
    // HURT/TOO_HARD override everything; among positive signals, the least enthusiastic wins.
    fun aggregateMuscleGroupFeedback(feedbacks: List<SetFeedback>): SetFeedback {
        if (SetFeedback.HURT in feedbacks) return SetFeedback.HURT
        if (SetFeedback.TOO_HARD in feedbacks) return SetFeedback.TOO_HARD
        if (SetFeedback.RIR_0_1 in feedbacks) return SetFeedback.RIR_0_1
        if (SetFeedback.RIR_2_4 in feedbacks) return SetFeedback.RIR_2_4
        return SetFeedback.RIR_5_PLUS
    }

    fun scaleWeight(weight: Float, fromReps: Int, toReps: Int): Float {
        if (weight <= 0f || fromReps == toReps) return weight
        val oneRepMax = weight * (1f + fromReps / 30f)
        return roundInternal(oneRepMax / (1f + toReps / 30f))
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

    private fun roundInternal(weight: Float): Float =
        (weight / INTERNAL_INCREMENT).roundToInt() * INTERNAL_INCREMENT
}
