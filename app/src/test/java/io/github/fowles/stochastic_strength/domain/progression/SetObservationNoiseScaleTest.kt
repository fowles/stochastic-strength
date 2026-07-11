package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Test

class SetObservationNoiseScaleTest {
    private fun set() = WorkoutSet(
        sessionId = 1, exerciseId = 1, setNumber = 1,
        targetWeight = 60f, targetReps = 5, actualReps = null, feedback = SetFeedback.RIR_0_1,
    )

    @Test fun obsNoiseScaleMultipliesNoiseLinearly() {
        // Use explicit obsNoiseScale=1f as the base so this test is not coupled to the default value.
        val base = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig(obsNoiseScale = 1f))!!
        val scaled = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig(obsNoiseScale = 3f))!!
        assertEquals(3f * base.noiseSd, scaled.noiseSd, 1e-6f)
    }

    @Test fun defaultAppliesAdoptedScale() {
        val unit = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig(obsNoiseScale = 1f))!!
        val deflt = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig())!!
        // Default adopted obsNoiseScale = 2.5 (joint-fit 2026-07-11); fails loudly if it reverts.
        assertEquals(2.5f * unit.noiseSd, deflt.noiseSd, 1e-6f)
    }
}
