package io.github.fowles.stochastic_strength.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartRangeTest {

    private val eps = 1e-9

    @Test fun flatDataExtendsTenPercentEachSideOfTheValue() {
        val range = paddedChartYRange(minY = 100.0, maxY = 100.0)
        assertEquals(90.0, range.start, eps)
        assertEquals(110.0, range.endInclusive, eps)
    }

    @Test fun narrowSpreadIsWidenedToTenPercentOfCenter() {
        // Center = 100, data spread is only 2 units. 15% of spread (0.3) is far
        // smaller than 10% of the center (10), so the central floor wins.
        val range = paddedChartYRange(minY = 99.0, maxY = 101.0)
        assertEquals(90.0, range.start, eps)
        assertEquals(110.0, range.endInclusive, eps)
    }

    @Test fun wideSpreadUsesSpreadPaddingNotCentralFloor() {
        // Center = 100, spread = 100. 15% of spread (15) exceeds 10% of center
        // (10), so the spread padding wins on both ends.
        val range = paddedChartYRange(minY = 50.0, maxY = 150.0)
        assertEquals(35.0, range.start, eps)
        assertEquals(165.0, range.endInclusive, eps)
    }

    @Test fun handlesNegativeCenterRobustly() {
        val range = paddedChartYRange(minY = -100.0, maxY = -100.0)
        assertEquals(-110.0, range.start, eps)
        assertEquals(-90.0, range.endInclusive, eps)
    }
}
