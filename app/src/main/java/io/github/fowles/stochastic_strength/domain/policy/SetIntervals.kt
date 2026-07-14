package io.github.fowles.stochastic_strength.domain.policy

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.DefaultProgressionEngine
import kotlin.math.ln

/** A model-free interval on ln(1RM). Null bound = unbounded on that side. */
data class LnInterval(val lowerLn: Float?, val upperLn: Float?) {
    /** 0 if [pointLn] is inside; otherwise the log-distance to the violated bound. */
    fun distanceTo(pointLn: Float): Float = when {
        lowerLn != null && pointLn < lowerLn -> lowerLn - pointLn
        upperLn != null && pointLn > upperLn -> pointLn - upperLn
        else -> 0f
    }
}

/**
 * The metric's target side (spec Phase 0): what a set says about ln(1RM) using ONLY the rep-max
 * formula and the feedback bucket. No fatigue correction, no estimator concepts — both stacks are
 * scored against the same intervals and neither can game it via its own modeling assumptions.
 */
object SetIntervals {
    fun impliedLn1RmInterval(set: WorkoutSet): LnInterval? {
        val feedback = set.feedback ?: return null
        if (feedback == SetFeedback.HURT) return null
        val w = set.targetWeight
        if (w <= 0f) return null
        val r = set.targetReps
        fun capLn(reps: Float) = ln(DefaultProgressionEngine.rawToOneRepMax(w, reps))
        return when (feedback) {
            SetFeedback.TOO_HARD -> {
                val a = set.actualReps
                if (a != null) LnInterval(capLn(a.toFloat()), capLn(a + 1f))
                else LnInterval(null, capLn(r.toFloat()))
            }
            SetFeedback.RIR_0_1 -> LnInterval(capLn(r.toFloat()), capLn(r + 2f))
            SetFeedback.RIR_2_4 -> LnInterval(capLn(r + 2f), capLn(r + 5f))
            SetFeedback.RIR_5_PLUS -> LnInterval(capLn(r + 5f), null)
            SetFeedback.HURT -> null
        }
    }
}
