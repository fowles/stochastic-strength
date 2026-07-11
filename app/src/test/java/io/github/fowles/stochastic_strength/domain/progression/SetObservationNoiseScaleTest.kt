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
        val base = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig())!!
        val scaled = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig(obsNoiseScale = 3f))!!
        assertEquals(3f * base.noiseSd, scaled.noiseSd, 1e-6f)
    }

    @Test fun defaultScaleIsIdentity() {
        val a = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig())!!
        val b = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig(obsNoiseScale = 1f))!!
        assertEquals(a.noiseSd, b.noiseSd, 0f)
    }
}
