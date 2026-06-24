package io.github.fowles.stochastic_strength.ui.debug

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatBaselineSetLineTest {
    private fun set(feedback: SetFeedback?, actualReps: Int? = null) = WorkoutSet(
        sessionId = 1L, exerciseId = 1L, setNumber = 1,
        targetWeight = 50f, targetReps = 8, actualReps = actualReps, feedback = feedback,
    )

    @Test fun rirIsTildeEstimate() {
        assertEquals("~11@110lbs", formatBaselineSetLine(set(SetFeedback.RIR_2_4), WeightUnit.LBS))
    }
    @Test fun tooHardShowsActualReps() {
        assertEquals("6@50.0kg", formatBaselineSetLine(set(SetFeedback.TOO_HARD, actualReps = 6), WeightUnit.KG))
    }
    @Test fun tooHardWithoutRepsShowsQuestionMark() {
        assertEquals("?@50.0kg", formatBaselineSetLine(set(SetFeedback.TOO_HARD), WeightUnit.KG))
    }
    @Test fun hurtRendersHurt() {
        assertEquals("hurt@50.0kg", formatBaselineSetLine(set(SetFeedback.HURT), WeightUnit.KG))
    }
    @Test fun noFeedbackIsNull() {
        assertNull(formatBaselineSetLine(set(feedback = null), WeightUnit.KG))
    }
}
