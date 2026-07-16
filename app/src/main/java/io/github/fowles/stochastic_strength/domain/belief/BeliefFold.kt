package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.policy.LnInterval
import io.github.fowles.stochastic_strength.domain.policy.SetIntervals
import kotlin.math.ln

/** Pure belief updates: aging (this task), fatigue shift + boundary-pull fold (Task 3). */
class BeliefFold(private val config: BeliefConfig) {
    private val dayMs = 24L * 60 * 60 * 1000

    /** sigma² grows by q per idle day (mu untouched); clamped by the flat guards. */
    fun aged(b: Belief, now: Long): Belief {
        val idleDays = (now - b.updatedAt).coerceAtLeast(0L).toFloat() / dayMs
        val s2 = (b.sigma2 + config.qPerDay * idleDays).coerceIn(config.sigma2Floor, config.sigma2Cap)
        return Belief(b.mu, s2, now)
    }

    /**
     * Set rank k (1-based, ALL of the exercise's rows in the session, including feedback-less and
     * HURT) observes fresh capacity reduced by phi·(k−1); the implied interval shifts UP by
     * −ln(1 − phi·(k−1)) before folding. Clamped so the shift stays finite.
     */
    fun fatigueShift(rank: Int): Float =
        -ln(1f - (config.phi * (rank - 1)).coerceAtMost(0.9f))

    /**
     * Boundary-pull Gaussian fold (spec Phase 2). If mu lies inside the shifted interval the set
     * confirms: mu unchanged, sigma shrinks exactly as a Gaussian fold at the nearer boundary
     * would (the Kalman variance update is innovation-independent). If mu lies outside, one
     * Kalman line at the violated boundary. Symmetric up/down — no off-day damping, no down-snap.
     */
    fun fold(b: Belief, interval: LnInterval, shift: Float, obsSigma: Float, at: Long): Belief {
        val lower = interval.lowerLn?.plus(shift)
        val upper = interval.upperLn?.plus(shift)
        val s2 = obsSigma * obsSigma
        val gain = b.sigma2 / (b.sigma2 + s2)
        val mu = when {
            lower != null && b.mu < lower -> b.mu + gain * (lower - b.mu)
            upper != null && b.mu > upper -> b.mu + gain * (upper - b.mu)
            else -> b.mu
        }
        val sigma2 = (b.sigma2 * s2 / (b.sigma2 + s2)).coerceIn(config.sigma2Floor, config.sigma2Cap)
        return Belief(mu, sigma2, at)
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
            b = fold(b, interval, fatigueShift(idx + 1), config.sigmaObs, asOf)
        }
        return b
    }
}
