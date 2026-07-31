package io.github.fowles.stochastic_strength.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetrainingModelTest {
    private val week = DetrainingModel.WEEK_MILLIS

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

    @Test fun qualifies_requiresTwoFullWeeks() {
        assertFalse(DetrainingModel.qualifies(0))
        assertFalse(DetrainingModel.qualifies(1))
        assertTrue(DetrainingModel.qualifies(2))
        assertTrue(DetrainingModel.qualifies(5))
    }

    @Test fun reduce_lowersBaselineByFraction() {
        assertEquals(90f, DetrainingModel.reduce(100f, 0.10f), 1e-4f)
        assertEquals(100f, DetrainingModel.reduce(100f, 0f), 1e-4f)
    }

    @Test fun retention_isFullBelowTwoWeeks() {
        assertEquals(1f, DetrainingModel.retention(0L), 1e-6f)
        assertEquals(1f, DetrainingModel.retention(week), 1e-6f)          // 1 week -> no detrain
        assertEquals(1f, DetrainingModel.retention(2 * week - 1), 1e-6f)  // just under 2 weeks
    }

    @Test fun retention_dropsAfterTwoWeeks() {
        assertEquals(0.90f, DetrainingModel.retention(2 * week), 1e-6f)    // 2 weeks -> 10%
        assertEquals(0.85f, DetrainingModel.retention(3 * week), 1e-6f)    // 3 weeks -> 15%
    }

    @Test fun retention_floorsAtHalf() {
        assertEquals(0.5f, DetrainingModel.retention(20 * week), 1e-6f)    // capped at 50%
    }
}
