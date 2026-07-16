package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals
import kotlin.math.roundToInt

// Display reserve offsets, derived from the SetIntervals feedback buckets so implied rep counts
// can never drift from the intervals the estimator and policy actually use: bounded buckets show
// their midpoint, the unbounded RIR_5_PLUS bucket its lower bound.
private const val RESERVE_RIR_0_1 = (SetIntervals.RIR_0_1_LOW + SetIntervals.RIR_0_1_HIGH) / 2f
private const val RESERVE_RIR_2_4 = (SetIntervals.RIR_2_4_LOW + SetIntervals.RIR_2_4_HIGH) / 2f
private const val RESERVE_RIR_5_PLUS = SetIntervals.RIR_5_PLUS_LOW

/** One displayable set observation in unit-free form. [reps] is the implied/observed rep count. */
data class ObservedSet(
    val reps: Int,
    /** true => an RIR-derived estimate (render with a leading "~"); false => an observed count. */
    val isEstimate: Boolean,
    val weightKg: Float,
)

/**
 * The numeric rep observation a set implies, or null when it carries none.
 *
 * RIR feedbacks add the reserve offsets above (from the SetIntervals feedback buckets) and are
 * marked as estimates. TOO_HARD with a recorded [WorkoutSet.actualReps] is an observed (non-
 * estimate) count. Warmups/unfinished sets (no feedback), HURT (an injury flag, no rep estimate),
 * and TOO_HARD without actualReps carry no numeric observation and return null.
 */
fun impliedObservedSet(set: WorkoutSet): ObservedSet? {
    val feedback = set.feedback ?: return null
    val reps: Int
    val isEstimate: Boolean
    when (feedback) {
        SetFeedback.RIR_0_1 -> { reps = (set.targetReps + RESERVE_RIR_0_1).roundToInt(); isEstimate = true }
        SetFeedback.RIR_2_4 -> { reps = (set.targetReps + RESERVE_RIR_2_4).roundToInt(); isEstimate = true }
        SetFeedback.RIR_5_PLUS -> { reps = (set.targetReps + RESERVE_RIR_5_PLUS).roundToInt(); isEstimate = true }
        SetFeedback.TOO_HARD -> { reps = set.actualReps ?: return null; isEstimate = false }
        SetFeedback.HURT -> return null
    }
    return ObservedSet(reps = reps, isEstimate = isEstimate, weightKg = set.targetWeight)
}
