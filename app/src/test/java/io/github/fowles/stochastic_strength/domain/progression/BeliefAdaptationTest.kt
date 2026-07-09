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
    fun oneConfidentFailureDoesNotCollapseSigmaToFloor() {
        // Fold one tight failure from the seed prior; σ must stay well above the floor so the
        // filter can still hear later sets in the same session.
        val seed = ExerciseBelief.seed(e1rm = 38f, at = 0L, config = config)
        val obs = SetObservation.from(tooHard(w = 29.5f, reps = 10, got = 2), fatigueRank = 1, config = config)!!
        val after = updater.foldGaussian(seed, obs.gaussianLn!!, obs.noiseSd, at = 0L, muscleLastObs = null)
        assertTrue("σ must not collapse to the floor after one fold (σ=${after.sigma})",
            after.sigma > 0.06f)
    }
}
