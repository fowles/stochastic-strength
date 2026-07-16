package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals
import kotlin.math.exp

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

/**
 * Every displayable observation for ONE exercise's session sets, as implied-e1rm values: sorted by
 * set id, rank 1-based over ALL rows (the fold's rank rule); rows with no interval emit nothing.
 * The single home of the chart-parity dot rule — the debug progression chart and the user-facing
 * exercise chart both consume this so their dots can never disagree.
 */
fun setObservationsE1rm(sets: List<WorkoutSet>, config: BeliefConfig): List<Float> =
    sets.sortedBy { it.id }.mapIndexedNotNull { idx, set ->
        setObservationLn(set, rank = idx + 1, config)?.let { exp(it) }
    }
