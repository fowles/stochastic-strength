package io.github.fowles.stochastic_strength.domain.belief

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.policy.LnInterval
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Test

class BeliefFoldTest {
    private val DAY = 24L * 60 * 60 * 1000

    // Explicit config in every test — defaults are re-fit later and must not be load-bearing here.
    private val config = BeliefConfig(
        sigmaSeed = 0.15f, sigmaOverride = 0.10f,
        fatiguePerSetEstimate = 0.05f, confidenceDecayEstimate = 1e-3f,
        perSetDoubtEstimate = 0.10f,
        crossLiftIndependenceEstimate = 0.10f, sigma2Floor = 4e-4f, sigma2Cap = 0.25f,
    )
    private val fold = BeliefFold(config)

    @Test
    fun agingGrowsVarianceLinearlyPerIdleDay() {
        val b = Belief(mu = 4.6f, sigma2 = 0.01f, updatedAt = 0L)
        val aged = fold.aged(b, now = 10 * DAY)
        assertEquals(4.6f, aged.mu, 1e-6f)                     // mu never drifts
        assertEquals(0.01f + 10 * 1e-3f, aged.sigma2, 1e-6f)   // q per idle day
        assertEquals(10 * DAY, aged.updatedAt)
    }

    @Test
    fun agingIsClampedToTheVarianceCapAndNeverNegativeTime() {
        val b = Belief(mu = 4.6f, sigma2 = 0.24f, updatedAt = 10 * DAY)
        assertEquals(0.25f, fold.aged(b, now = 100 * DAY).sigma2, 1e-6f)  // cap (flat guard)
        assertEquals(0.24f, fold.aged(b, now = 0L).sigma2, 1e-6f)         // clock skew: age >= 0
    }

    @Test
    fun fatigueShiftIsMinusLnOfRemainingCapacity() {
        assertEquals(0f, fold.fatigueShift(1), 1e-7f)                       // first set: fresh
        assertEquals(-ln(1f - 0.05f * 2), fold.fatigueShift(3), 1e-6f)      // fatiguePerSetEstimate·(k−1), k=3
    }

    @Test
    fun foldBelowTheIntervalPullsMuUpByKalmanGain() {
        // Prior sigma2=0.04, obs s2=0.01 → gain = 0.04/0.05 = 0.8; posterior sigma2 = 0.04·0.01/0.05 = 0.008.
        val b = Belief(mu = ln(100f), sigma2 = 0.04f, updatedAt = 0L)
        val lower = ln(110f)
        val out = fold.fold(b, LnInterval(lower, null), shift = 0f, obsSigma = 0.1f, at = 5L)
        assertEquals(b.mu + 0.8f * (lower - b.mu), out.mu, 1e-6f)
        assertEquals(0.008f, out.sigma2, 1e-6f)
        assertEquals(5L, out.updatedAt)
    }

    @Test
    fun foldAboveTheIntervalPullsMuDownSymmetrically() {
        // Symmetric up/down (spec): a failure moves the belief down exactly as hard.
        val b = Belief(mu = ln(120f), sigma2 = 0.04f, updatedAt = 0L)
        val upper = ln(110f)
        val out = fold.fold(b, LnInterval(null, upper), shift = 0f, obsSigma = 0.1f, at = 5L)
        assertEquals(b.mu + 0.8f * (upper - b.mu), out.mu, 1e-6f)
        assertEquals(0.008f, out.sigma2, 1e-6f)
    }

    @Test
    fun foldInsideTheIntervalConfirmsMuAndOnlyShrinksSigma() {
        val b = Belief(mu = ln(105f), sigma2 = 0.04f, updatedAt = 0L)
        val out = fold.fold(b, LnInterval(ln(100f), ln(110f)), shift = 0f, obsSigma = 0.1f, at = 5L)
        assertEquals(b.mu, out.mu, 1e-7f)
        assertEquals(0.008f, out.sigma2, 1e-6f)   // same shrink a boundary fold would give
    }

    @Test
    fun shiftMovesTheIntervalUpBeforeComparing() {
        // mu inside the raw interval but below it once shifted: a fatigued success implies MORE fresh capacity.
        val b = Belief(mu = ln(100f), sigma2 = 0.04f, updatedAt = 0L)
        val raw = LnInterval(ln(99f), ln(101f))
        val shift = 0.10f
        val out = fold.fold(b, raw, shift = shift, obsSigma = 0.1f, at = 5L)
        assertEquals(b.mu + 0.8f * (ln(99f) + shift - b.mu), out.mu, 1e-6f)
    }

    @Test
    fun foldSessionRanksAllRowsButFoldsOnlyScoreableOnes() {
        // Three rows: RIR success, feedback-less (rank counts, no fold), TOO_HARD at rank 3.
        val sets = listOf(
            WorkoutSet(id = 1, sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = 100f, targetReps = 5, feedback = SetFeedback.RIR_0_1),
            WorkoutSet(id = 2, sessionId = 1, exerciseId = 1, setNumber = 2, targetWeight = 100f, targetReps = 5),
            WorkoutSet(id = 3, sessionId = 1, exerciseId = 1, setNumber = 3, targetWeight = 100f, targetReps = 5, actualReps = 3, feedback = SetFeedback.TOO_HARD),
        )
        val prior = Belief(mu = ln(100f), sigma2 = 0.01f, updatedAt = 0L)
        val asOf = 24L * 60 * 60 * 1000
        // Hand-fold with the same components: aging first, then set 1 (rank 1) and set 3 (rank 3).
        var expected = fold.aged(prior, asOf)
        expected = fold.fold(expected, io.github.fowles.stochastic_strength.domain.policy.SetIntervals.impliedLn1RmInterval(sets[0])!!, fold.fatigueShift(1), config.perSetDoubtEstimate, asOf)
        expected = fold.fold(expected, io.github.fowles.stochastic_strength.domain.policy.SetIntervals.impliedLn1RmInterval(sets[2])!!, fold.fatigueShift(3), config.perSetDoubtEstimate, asOf)
        assertEquals(expected, fold.foldSession(prior, sets, asOf))
    }
}
