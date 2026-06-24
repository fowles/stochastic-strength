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

    @Test fun rirFeedbacksAddReserveAndAreEstimates() {
        assertEquals(ObservedSet(reps = 9, isEstimate = true, weightKg = 50f), impliedObservedSet(set(SetFeedback.RIR_0_1)))   // 8 + 0.5 -> round 9 (HALF_UP? see note)
        assertEquals(ObservedSet(reps = 11, isEstimate = true, weightKg = 50f), impliedObservedSet(set(SetFeedback.RIR_2_4))) // 8 + 3
        assertEquals(ObservedSet(reps = 14, isEstimate = true, weightKg = 50f), impliedObservedSet(set(SetFeedback.RIR_5_PLUS))) // 8 + 6
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
