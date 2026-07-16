package io.github.fowles.stochastic_strength.domain.progression

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservedSetTest {
    private fun set(feedback: SetFeedback?, targetReps: Int = 8, actualReps: Int? = null) =
        WorkoutSet(
            sessionId = 1L, exerciseId = 1L, setNumber = 1,
            targetWeight = 50f, targetReps = targetReps, actualReps = actualReps, feedback = feedback,
        )

    @Test fun rirFeedbacksAddBucketDerivedReserveAndAreEstimates() {
        // Offsets come from the SetIntervals buckets: midpoint of [0,2] = 1, midpoint of [2,5] =
        // 3.5 (rounds up), lower bound of the unbounded 5+ bucket = 5.
        assertEquals(ObservedSet(reps = 9, isEstimate = true, weightKg = 50f), impliedObservedSet(set(SetFeedback.RIR_0_1)))   // 8 + 1
        assertEquals(ObservedSet(reps = 12, isEstimate = true, weightKg = 50f), impliedObservedSet(set(SetFeedback.RIR_2_4))) // 8 + 3.5 -> 12
        assertEquals(ObservedSet(reps = 13, isEstimate = true, weightKg = 50f), impliedObservedSet(set(SetFeedback.RIR_5_PLUS))) // 8 + 5
    }

    @Test fun tooHardUsesActualRepsObservedNotEstimated() {
        assertEquals(ObservedSet(reps = 6, isEstimate = false, weightKg = 50f), impliedObservedSet(set(SetFeedback.TOO_HARD, actualReps = 6)))
    }

    @Test fun nonObservationsReturnNull() {
        assertNull(impliedObservedSet(set(feedback = null)))                 // warmup / unfinished
        assertNull(impliedObservedSet(set(SetFeedback.HURT)))                // injury flag
        assertNull(impliedObservedSet(set(SetFeedback.TOO_HARD, actualReps = null))) // no reps recorded
    }
}
