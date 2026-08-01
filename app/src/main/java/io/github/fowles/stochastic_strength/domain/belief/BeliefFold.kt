package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.policy.LnInterval
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals
import kotlin.math.ln

/** Pure belief updates: aging (this task), fatigue shift + boundary-pull fold (Task 3). */
class BeliefFold(private val config: BeliefConfig) {
    private val dayMs = 24L * 60 * 60 * 1000

    /** uncertainty² grows by q per idle day (bestGuessLn untouched); clamped by the flat guards. */
    fun aged(b: Belief, now: Long): Belief {
        val idleDays = (now - b.updatedAt).coerceAtLeast(0L).toFloat() / dayMs
        val s2 = (b.uncertainty + config.confidenceDecayEstimate * idleDays).coerceIn(config.uncertaintyFloor, config.uncertaintyCap)
        return Belief(b.bestGuessLn, s2, now)
    }

    /**
     * Set rank k (1-based, ALL of the exercise's rows in the session, including feedback-less and
     * HURT) observes fresh capacity reduced by fatiguePerSetEstimate·(k−1); the implied interval
     * shifts UP by −ln(1 − fatiguePerSetEstimate·(k−1)) before folding. Clamped so the shift stays
     * finite.
     */
    fun fatigueShift(rank: Int): Float =
        -ln(1f - (config.fatiguePerSetEstimate * (rank - 1)).coerceAtMost(0.9f))

    /**
     * Boundary-pull Gaussian fold (spec Phase 2). If bestGuessLn lies inside the shifted interval the set
     * confirms: bestGuessLn unchanged, uncertainty shrinks exactly as a Gaussian fold at the nearer boundary
     * would (the Kalman variance update is innovation-independent). If bestGuessLn lies outside, one
     * Kalman line at the violated boundary. Symmetric up/down — no off-day damping, no down-snap.
     */
    fun fold(b: Belief, interval: LnInterval, shift: Float, obsSigma: Float, at: Long): Belief {
        val lower = interval.lowerLn?.plus(shift)
        val upper = interval.upperLn?.plus(shift)
        val s2 = obsSigma * obsSigma
        val gain = b.uncertainty / (b.uncertainty + s2)
        val bestGuessLn = when {
            lower != null && b.bestGuessLn < lower -> b.bestGuessLn + gain * (lower - b.bestGuessLn)
            upper != null && b.bestGuessLn > upper -> b.bestGuessLn + gain * (upper - b.bestGuessLn)
            else -> b.bestGuessLn
        }
        val uncertainty = (b.uncertainty * s2 / (b.uncertainty + s2)).coerceIn(config.uncertaintyFloor, config.uncertaintyCap)
        return Belief(bestGuessLn, uncertainty, at)
    }

    /**
     * One exercise's session: age to [asOf], then fold each row in set-id order. Rank counts every
     * row; only rows with an implied interval fold (HURT and feedback-less rows carry no load
     * observation — policy handles HURT).
     */
    fun foldSession(prior: Belief, exSets: List<WorkoutSet>, asOf: Long): Belief {
        var b = aged(prior, asOf)
        exSets.sortedBy { it.id }.forEachIndexed { idx, set ->
            val interval = SetIntervals.impliedLn1RmInterval(set) ?: return@forEachIndexed
            b = fold(b, interval, fatigueShift(idx + 1), config.perSetDoubtEstimate, asOf)
        }
        return b
    }
}
