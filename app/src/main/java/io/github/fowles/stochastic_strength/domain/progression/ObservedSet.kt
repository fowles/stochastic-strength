package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import kotlin.math.roundToInt

/** display reserve offsets — midpoints of the SetIntervals feedback buckets */
private const val RESERVE_RIR_0_1 = 0.5f
private const val RESERVE_RIR_2_4 = 3f
private const val RESERVE_RIR_5_PLUS = 6f

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
 * RIR feedbacks add the reserve offsets above (display midpoints of the SetIntervals feedback
 * buckets) and are marked as estimates. TOO_HARD with a recorded [WorkoutSet.actualReps] is an
 * observed (non-estimate) count. Warmups/unfinished sets (no feedback), HURT (an injury flag, no
 * rep estimate), and TOO_HARD without actualReps carry no numeric observation and return null.
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
