package io.github.fowles.stochastic_strength.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SetFeedbackTest {
    @Test
    fun displayLabelWithActualRepsAnnotatesOnlyTooHard() {
        assertEquals("Too Heavy (4)", SetFeedback.TOO_HARD.displayLabel(4))
        assertEquals("Too Heavy", SetFeedback.TOO_HARD.displayLabel(null))
        assertEquals("Hurt", SetFeedback.HURT.displayLabel(4))
        assertEquals("0–1 more", SetFeedback.RIR_0_1.displayLabel(8))
        assertEquals("2–4 more", SetFeedback.RIR_2_4.displayLabel(null))
        assertEquals("5+ more", SetFeedback.RIR_5_PLUS.displayLabel(10))
    }
}
