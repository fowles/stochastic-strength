package io.github.fowles.stochastic_strength.domain

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

object DefaultProgressionEngine : ProgressionEngine {
    private const val INTERNAL_INCREMENT = 0.5f

    val REP_OPTIONS = listOf(5, 8, 10)

    override fun computeNextBaseline(baseline: Float, feedbacks: List<SetFeedback>, minReductionFraction: Float, sessionReps: Int): Float {
        if (feedbacks.isEmpty() && minReductionFraction == 0f) return baseline
        if (SetFeedback.HURT in feedbacks) return weightDecreased(baseline, 0.85f)
        val score = scoreFromFeedbacks(feedbacks, sessionReps)
        val scoreResult = if (score != null) applyScoreBaseline(baseline, score) else baseline
        if (minReductionFraction > 0f) {
            val cap = maxOf(INTERNAL_INCREMENT, roundInternal(baseline * (1f - minReductionFraction)))
            return minOf(scoreResult, cap)
        }
        return scoreResult
    }

    override fun scoreFromFeedbacks(feedbacks: List<SetFeedback>, sessionReps: Int): Float? {
        val scored = feedbacks.filter { it != SetFeedback.HURT }
        if (scored.isEmpty()) return null
        if (sessionReps >= REP_OPTIONS.max()
            && scored.any { it == SetFeedback.TOO_HARD }
            && scored.any { it != SetFeedback.TOO_HARD }
        ) return 0f
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

    override fun toOneRepMax(weight: Float, reps: Int): Float = roundInternal(rawToOneRepMax(weight, reps))

    override fun fromOneRepMax(oneRepMax: Float, reps: Int): Float = roundInternal(rawFromOneRepMax(oneRepMax, reps))

    override fun scaleReps(weight: Float, from: Int, to: Int): Float = roundInternal(rawFromOneRepMax(rawToOneRepMax(weight, from), to))

    internal fun rawToOneRepMax(weight: Float, reps: Int): Float {
        if (weight <= 0f || reps <= 1) return weight
        val denom = -2.55f + 4.58f * ln(weight)
        if (denom <= 0f) return weight * (1f + reps / 30f)
        return weight * (1f + (reps - 1).toFloat().pow(0.85f) / denom)
    }

    internal fun rawFromOneRepMax(oneRepMax: Float, reps: Int): Float {
        if (oneRepMax <= 0f || reps <= 1) return oneRepMax
        val k = (reps - 1).toFloat().pow(0.85f)
        val epley = oneRepMax / (1f + reps / 30f)
        var w = epley
        for (i in 0 until 3) {
            val denom = -2.55f + 4.58f * ln(w)
            if (denom <= 0f) return epley
            val fprime = 1f + k * (denom - 4.58f) / (denom * denom)
            if (fprime <= 0f) return epley
            w -= (w * (1f + k / denom) - oneRepMax) / fprime
        }
        return w
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

    private fun weightDecreased(current: Float, factor: Float): Float {
        val scaled = roundInternal(current * factor)
        return if (scaled < current) maxOf(INTERNAL_INCREMENT, scaled) else maxOf(INTERNAL_INCREMENT, roundInternal(current - INTERNAL_INCREMENT))
    }

    private fun roundInternal(weight: Float): Float =
        (weight / INTERNAL_INCREMENT).roundToInt() * INTERNAL_INCREMENT
}
