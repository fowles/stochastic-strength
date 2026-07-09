package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertTrue
import org.junit.Test

class BeliefAdaptationTest {
    private val config = EstimatorConfig()
    private val updater = BeliefUpdater(config)

    private fun tooHard(w: Float, reps: Int, got: Int) =
        WorkoutSet(sessionId = 1, exerciseId = 1, setNumber = 1, targetWeight = w,
            targetReps = reps, actualReps = got, feedback = SetFeedback.TOO_HARD)

    @Test
    fun singleSetObservationCarriesModelUncertaintyFloor() {
        // A lone 2-rep failure must NOT claim ±2.5% knowledge of fresh 1RM.
        val obs = SetObservation.from(tooHard(w = 25f, reps = 10, got = 2), fatigueRank = 1, config = config)!!
        assertTrue("obs noise must be floored by obsModelSd (got ${obs.noiseSd})",
            obs.noiseSd >= config.obsModelSd)
    }

    @Test
    fun obsModelFloorKeepsSingleFoldOffTheHardFloor() {
        // The light obsModelSd floor keeps one confident failure from slamming σ onto the hard
        // sigmaMin floor (a mild fluke regularizer). Full recovery from an over-collapsed belief is
        // adaptation's job, not this floor's — see consistentRunOfSurprisesReopensAndTracks.
        val seed = ExerciseBelief.seed(e1rm = 38f, at = 0L, config = config)
        val obs = SetObservation.from(tooHard(w = 29.5f, reps = 10, got = 2), fatigueRank = 1, config = config)!!
        val after = updater.foldGaussian(seed, obs.gaussianLn!!, obs.noiseSd, at = 0L, muscleLastObs = null)
        assertTrue("σ must stay off the hard floor after one fold (σ=${after.sigma})",
            after.sigma > config.sigmaMin * 1.3f)
    }

    // --- Fix B: adaptive attention ---

    /** A single surprising observation (run below threshold) must NOT yank a tight belief. */
    @Test
    fun loneSurpriseDoesNotYankTightBelief() {
        val tight = ExerciseBelief(mu = 3.6f, sigma2 = 0.02f * 0.02f, updatedAt = 0L)
        val after = updater.foldGaussian(tight, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null)
        assertTrue("one surprise should barely move a tight belief (μ=${after.mu})", after.mu > 3.5f)
    }

    /** A consistent one-signed run of surprises must re-open σ and let the belief track the data. */
    @Test
    fun consistentRunOfSurprisesReopensAndTracks() {
        var b = ExerciseBelief(mu = 3.6f, sigma2 = 0.02f * 0.02f, updatedAt = 0L)
        repeat(5) { b = updater.foldGaussian(b, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null) }
        assertTrue("consistent down-run must drag the belief toward the data (μ=${b.mu})", b.mu < 3.35f)
        assertTrue("innovationRun must have accumulated downward (${b.innovationRun})", b.innovationRun < -config.adaptRunThreshold)
    }

    /** Turning adaptation off (threshold huge) leaves the belief stuck — proves the run is doing the work. */
    @Test
    fun withoutAdaptationTheBeliefStaysStuck() {
        val noAdapt = EstimatorConfig(adaptRunThreshold = 1e6f)
        val u = BeliefUpdater(noAdapt)
        var b = ExerciseBelief(mu = 3.6f, sigma2 = 0.02f * 0.02f, updatedAt = 0L)
        repeat(5) { b = u.foldGaussian(b, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null) }
        assertTrue("without adaptation a tight belief cannot follow (μ=${b.mu})", b.mu > 3.35f)
    }

    /** A direction flip restarts the run rather than compounding it. */
    @Test
    fun signFlipRestartsRun() {
        var b = ExerciseBelief(mu = 3.6f, sigma2 = 0.02f * 0.02f, updatedAt = 0L)
        repeat(3) { b = updater.foldGaussian(b, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null) }
        assertTrue("run is negative after a down-sequence", b.innovationRun < 0f)
        val flipped = updater.foldGaussian(b, obsLnE1rm = b.mu + 0.5f, noiseSd = 0.05f, at = 0L, muscleLastObs = null)
        assertTrue("an up-surprise restarts the run positive (${flipped.innovationRun})", flipped.innovationRun > 0f)
    }

    /** The innovation run must survive aging across a time gap (cross-session adaptation). */
    @Test
    fun innovationRunCarriesAcrossAgingGap() {
        val DAY = 24L * 60 * 60 * 1000
        // Build up a negative run within one "session" (same timestamp).
        var b = ExerciseBelief(mu = 3.6f, sigma2 = 0.02f * 0.02f, updatedAt = 0L)
        repeat(3) { b = updater.foldGaussian(b, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 0L, muscleLastObs = null) }
        val runBefore = b.innovationRun
        assertTrue("precondition: run accumulated negative ($runBefore)", runBefore < 0f)
        // A fold 3 days later must NOT reset the run to 0 — it continues from runBefore.
        val later = updater.foldGaussian(b, obsLnE1rm = 3.2f, noiseSd = 0.05f, at = 3 * DAY, muscleLastObs = null)
        assertTrue("run must persist across the aging gap, not reset (${later.innovationRun})",
            later.innovationRun < runBefore)
    }
}
