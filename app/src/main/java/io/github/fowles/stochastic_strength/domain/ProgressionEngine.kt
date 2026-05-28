package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import kotlin.math.roundToInt

object ProgressionEngine {
    private const val INTERNAL_INCREMENT = 0.5f
    val REP_OPTIONS = listOf(5, 8, 10)

    fun computeNextBaseline(baseline: Float, feedbacks: List<SetFeedback>): Float {
        if (feedbacks.isEmpty()) return baseline
        if (SetFeedback.HURT in feedbacks) return weightDecreased(baseline, 0.85f)
        val score = scoreFromFeedbacks(feedbacks) ?: return baseline
        return applyScoreBaseline(baseline, score)
    }

    fun scoreFromFeedbacks(feedbacks: List<SetFeedback>): Float? {
        val scored = feedbacks.filter { it != SetFeedback.HURT }
        if (scored.isEmpty()) return null
        return scored.sumOf { feedbackPoints(it) }.toFloat() / scored.size
    }

    fun applyScoreBaseline(baseline: Float, score: Float): Float = when {
        score >= 2.5f  -> weightIncreasedWithFloor(baseline, 1.075f, 2.5f)
        score >= 1.5f  -> weightIncreasedWithFloor(baseline, 1.05f,  1.0f)
        score >= 0.5f  -> weightIncreasedWithFloor(baseline, 1.025f, 0.5f)
        score > -0.5f  -> baseline
        score >= -1.5f -> weightDecreasedWithFloor(baseline, 0.95f, 0.5f)
        else           -> weightDecreasedWithFloor(baseline, 0.90f, 1.0f)
    }

    fun scaleWeight(weight: Float, fromReps: Int, toReps: Int): Float {
        if (weight <= 0f || fromReps == toReps) return weight
        val oneRepMax = weight * (1f + fromReps / 30f)
        return roundInternal(oneRepMax / (1f + toReps / 30f))
    }

    private fun feedbackPoints(feedback: SetFeedback): Int = when (feedback) {
        SetFeedback.RIR_5_PLUS -> 3
        SetFeedback.RIR_2_4   -> 2
        SetFeedback.RIR_0_1   -> 1
        SetFeedback.TOO_HARD  -> -2
        SetFeedback.HURT      -> error("HURT has no points")
    }

    private fun weightIncreasedWithFloor(current: Float, factor: Float, minIncrement: Float): Float {
        val scaled = roundInternal(current * factor)
        val floored = roundInternal(current + minIncrement)
        return maxOf(scaled, floored)
    }

    private fun weightDecreasedWithFloor(current: Float, factor: Float, minDecrement: Float): Float {
        val scaled = roundInternal(current * factor)
        val floored = roundInternal(current - minDecrement)
        return maxOf(INTERNAL_INCREMENT, minOf(scaled, floored))
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
