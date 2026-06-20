package io.github.fowles.stochastic_strength.ui

import io.github.fowles.stochastic_strength.data.model.SetFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SummarySetTest {
    private fun set(feedback: SetFeedback?, isTimed: Boolean, actualReps: Int? = null) =
        SummarySet(
            setNumber = 1,
            targetWeight = 0f,
            targetReps = 8,
            actualReps = actualReps,
            feedback = feedback,
            isTimed = isTimed,
        )

    @Test
    fun timedSetsHideRepsInReserveFeedback() {
        assertNull(set(SetFeedback.RIR_0_1, isTimed = true).summaryFeedbackLabel())
        assertNull(set(SetFeedback.RIR_2_4, isTimed = true).summaryFeedbackLabel())
        assertNull(set(SetFeedback.RIR_5_PLUS, isTimed = true).summaryFeedbackLabel())
    }

    @Test
    fun timedSetsStillShowTooHardAndHurt() {
        assertEquals("Too Hard", set(SetFeedback.TOO_HARD, isTimed = true).summaryFeedbackLabel())
        assertEquals("Hurt", set(SetFeedback.HURT, isTimed = true).summaryFeedbackLabel())
    }

    @Test
    fun repBasedSetsStillSayTooHeavy() {
        assertEquals("Too Heavy", set(SetFeedback.TOO_HARD, isTimed = false).summaryFeedbackLabel())
        assertEquals("Too Heavy (5)", set(SetFeedback.TOO_HARD, isTimed = false, actualReps = 5).summaryFeedbackLabel())
    }

    @Test
    fun repBasedSetsShowRepsInReserveFeedback() {
        assertEquals("0–1 more", set(SetFeedback.RIR_0_1, isTimed = false).summaryFeedbackLabel())
        assertEquals("2–4 more", set(SetFeedback.RIR_2_4, isTimed = false).summaryFeedbackLabel())
        assertEquals("5+ more", set(SetFeedback.RIR_5_PLUS, isTimed = false).summaryFeedbackLabel())
    }

    @Test
    fun nullFeedbackHasNoLabel() {
        assertNull(set(null, isTimed = true).summaryFeedbackLabel())
        assertNull(set(null, isTimed = false).summaryFeedbackLabel())
    }
}
