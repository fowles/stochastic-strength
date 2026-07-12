package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Test

class SetObservationNoiseScaleTest {
    private fun set(feedback: SetFeedback = SetFeedback.RIR_0_1, actualReps: Int? = null) = WorkoutSet(
        sessionId = 1, exerciseId = 1, setNumber = 1,
        targetWeight = 60f, targetReps = 5, actualReps = actualReps, feedback = feedback,
    )

    @Test fun obsNoiseScaleMultipliesNoiseLinearly() {
        // Use explicit obsNoiseScale=1f as the base so this test is not coupled to the default value.
        val base = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig(obsNoiseScale = 1f))!!
        val scaled = SetObservation.from(set(), fatigueRank = 1, config = EstimatorConfig(obsNoiseScale = 3f))!!
        assertEquals(3f * base.noiseSd, scaled.noiseSd, 1e-6f)
    }

    @Test fun adoptedDefaultsPinned() {
        // Variance-budget study 2026-07-11 outcome: ship the machinery, adopt NO behavioral change —
        // both knobs stay at their no-op values (obsNoiseScale=1.0, sessionDayEffectSd=0.0) because any
        // positive day-effect lifts the demonstrated prod-BSS 20 lb pin. Fails loudly if either default
        // is re-changed without re-running the human-gated adoption ceremony.
        assertEquals(1.0f, EstimatorConfig().obsNoiseScale, 0f)
        assertEquals(0.0f, EstimatorConfig().sessionDayEffectSd, 0f)
    }

    /** TOO_HARD (bucket, no actualReps): noise is a hard capacity bound — obsNoiseScale must not soften it. */
    @Test fun tooHardBucketNoiseIsSharpRegardlessOfObsNoiseScale() {
        val scale1 = SetObservation.from(
            set(SetFeedback.TOO_HARD, actualReps = null), fatigueRank = 1,
            config = EstimatorConfig(obsNoiseScale = 1f)
        )!!
        val scale3 = SetObservation.from(
            set(SetFeedback.TOO_HARD, actualReps = null), fatigueRank = 1,
            config = EstimatorConfig(obsNoiseScale = 3f)
        )!!
        assertEquals(scale1.noiseSd, scale3.noiseSd, 0f)
    }

    /** TOO_HARD (counted, actualReps set): Gaussian at demonstrated rep ceiling — obsNoiseScale must not soften it. */
    @Test fun tooHardCountedNoiseIsSharpRegardlessOfObsNoiseScale() {
        val scale1 = SetObservation.from(
            set(SetFeedback.TOO_HARD, actualReps = 3), fatigueRank = 1,
            config = EstimatorConfig(obsNoiseScale = 1f)
        )!!
        val scale3 = SetObservation.from(
            set(SetFeedback.TOO_HARD, actualReps = 3), fatigueRank = 1,
            config = EstimatorConfig(obsNoiseScale = 3f)
        )!!
        assertEquals(scale1.noiseSd, scale3.noiseSd, 0f)
    }
}
