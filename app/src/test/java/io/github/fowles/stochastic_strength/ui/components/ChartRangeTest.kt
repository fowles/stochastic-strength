package io.github.fowles.stochastic_strength.ui.components

import io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionData
import io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionSeries
import io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartRangeTest {

    private val eps = 1e-9

    private fun pt(v: Float) = ProgressionPoint(timestampMs = 0L, value = v)

    @Test fun sharedRangeCoversDotsAndMergedButIgnoresEstimateLines() {
        val data = ExerciseProgressionData(
            series = ExerciseProgressionSeries(
                ownEstimate = listOf(pt(5f)),       // a cold-start dip — must NOT pull the range down
                siblingsEstimate = listOf(pt(300f)), // a spike — must NOT push the range up
                merged = listOf(pt(90f), pt(100f)),
                bandUpper = listOf(pt(90f), pt(100f)),
                bandLower = listOf(pt(90f), pt(100f)),
                ownObservations = listOf(pt(95f)),
                siblingObservations = listOf(pt(85f), pt(105f)),
            ),
            frames = emptyList(),
        )
        val range = sharedProgressionYRange(data)!!
        // Range is padded over {85, 105, 95, 90, 100} -> min 85, max 105; spread padding 0.15*20=3.
        assertEquals(82.0, range.start, eps)
        assertEquals(108.0, range.endInclusive, eps)
    }

    @Test fun sharedRangeExtendsToCoverTheBand() {
        val data = ExerciseProgressionData(
            series = ExerciseProgressionSeries(
                ownEstimate = emptyList(),
                siblingsEstimate = emptyList(),
                merged = listOf(pt(100f)),
                bandUpper = listOf(pt(120f)),  // wider than the dots/merged -> must extend the range
                bandLower = listOf(pt(80f)),
                ownObservations = listOf(pt(95f), pt(105f)),
                siblingObservations = emptyList(),
            ),
            frames = emptyList(),
        )
        val range = sharedProgressionYRange(data)!!
        // Values {95,105,100,120,80} -> min 80, max 120; spread padding 0.15*40=6.
        assertEquals(74.0, range.start, eps)
        assertEquals(126.0, range.endInclusive, eps)
    }

    @Test fun sharedRangeIsNullWhenNothingToPlot() {
        val data = ExerciseProgressionData(ExerciseProgressionSeries.empty(), emptyList())
        assertNull(sharedProgressionYRange(data))
    }

    @Test fun fixedProviderReturnsTheGivenRangeIgnoringData() {
        val provider = fixedChartRangeProvider(70.0..110.0)
        val store = com.patrykandpatrick.vico.core.common.data.ExtraStore.Empty
        assertEquals(70.0, provider.getMinY(0.0, 1000.0, store), eps)
        assertEquals(110.0, provider.getMaxY(0.0, 1000.0, store), eps)
    }

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
