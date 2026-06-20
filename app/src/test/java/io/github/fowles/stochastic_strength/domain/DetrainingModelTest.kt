package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetrainingModelTest {
    private val week = 7L * 24 * 60 * 60 * 1000

    @Test fun weeksOff_floorsToWholeWeeks() {
        assertEquals(0, DetrainingModel.weeksOff(lastEndTime = 0, now = 6 * 24 * 60 * 60 * 1000))
        assertEquals(1, DetrainingModel.weeksOff(lastEndTime = 0, now = week))
        assertEquals(3, DetrainingModel.weeksOff(lastEndTime = 0, now = 3 * week + 100))
    }

    @Test fun weeksOff_neverNegative() {
        assertEquals(0, DetrainingModel.weeksOff(lastEndTime = week, now = 0))
    }

    @Test fun suggestedFraction_isFivePercentPerWeek() {
        assertEquals(0.05f, DetrainingModel.suggestedFraction(1), 1e-6f)
        assertEquals(0.15f, DetrainingModel.suggestedFraction(3), 1e-6f)
    }

    @Test fun suggestedFraction_cappedAtFiftyPercent() {
        assertEquals(0.50f, DetrainingModel.suggestedFraction(10), 1e-6f)
        assertEquals(0.50f, DetrainingModel.suggestedFraction(20), 1e-6f)
    }

    @Test fun suggestedFraction_zeroForNoWeeks() {
        assertEquals(0f, DetrainingModel.suggestedFraction(0), 1e-6f)
    }

    @Test fun qualifies_requiresAtLeastOneWeek() {
        assertFalse(DetrainingModel.qualifies(0))
        assertTrue(DetrainingModel.qualifies(1))
        assertTrue(DetrainingModel.qualifies(5))
    }

    @Test fun reduce_lowersBaselineByFraction() {
        assertEquals(90f, DetrainingModel.reduce(100f, 0.10f), 1e-4f)
        assertEquals(100f, DetrainingModel.reduce(100f, 0f), 1e-4f)
    }
}
