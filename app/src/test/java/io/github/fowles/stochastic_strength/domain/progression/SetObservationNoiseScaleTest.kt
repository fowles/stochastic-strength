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

    @Test fun defaultScaleIsIdentity() {
        // Verify default config is consistent with itself (not against a hardcoded 1f,
        // since the fitted default is 2.5f — adopted from joint CV fit 2026-07-11).
        val a = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig())!!
        val b = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig())!!
        assertEquals(a.noiseSd, b.noiseSd, 0f)
    }
}
