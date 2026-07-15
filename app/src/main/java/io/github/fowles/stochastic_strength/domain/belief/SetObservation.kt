package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals

/**
 * The fresh-capacity observation one set implies for charts: the midpoint of its finite implied
 * ln-1RM interval (or the single finite bound), fatigue-corrected back to fresh capacity by
 * +fatigueShift(rank). Null when the set carries no interval (HURT / no feedback / no weight).
 */
fun setObservationLn(set: WorkoutSet, rank: Int, config: BeliefConfig): Float? {
    val interval = SetIntervals.impliedLn1RmInterval(set) ?: return null
    val base = when {
        interval.lowerLn != null && interval.upperLn != null -> (interval.lowerLn + interval.upperLn) / 2f
        else -> interval.lowerLn ?: interval.upperLn ?: return null
    }
    return base + BeliefFold(config).fatigueShift(rank)
}
