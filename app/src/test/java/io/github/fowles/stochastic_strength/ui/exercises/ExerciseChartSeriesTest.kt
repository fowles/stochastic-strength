package io.github.fowles.stochastic_strength.ui.exercises

import io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionData
import io.github.fowles.stochastic_strength.domain.progression.ExerciseProgressionSeries
import io.github.fowles.stochastic_strength.domain.progression.ProgressionPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class ExerciseChartSeriesTest {

    private val zone = ZoneOffset.UTC
    private val dayMs = 86_400_000L
    private val hourMs = 3_600_000L

    private fun at(day: Long, hour: Long, value: Float) =
        ProgressionPoint(day * dayMs + hour * hourMs, value)

    private fun data(
        merged: List<ProgressionPoint> = emptyList(),
        own: List<ProgressionPoint> = emptyList(),
        siblings: List<ProgressionPoint> = emptyList(),
    ) = ExerciseProgressionData(
        series = ExerciseProgressionSeries.empty().copy(
            merged = merged,
            ownObservations = own,
            siblingObservations = siblings,
        ),
        frames = emptyList(),
    )

    @Test
    fun `estimate line is the pipeline's merged series`() {
        val series = exerciseChartSeries(
            data(merged = listOf(at(10, 18, 100f), at(12, 19, 105f))),
            zone,
        )
        assertEquals(
            listOf(ChartPoint(10 * dayMs, 100f), ChartPoint(12 * dayMs, 105f)),
            series.estimate,
        )
    }

    @Test
    fun `dots are the pipeline's own and sibling observations`() {
        val series = exerciseChartSeries(
            data(
                own = listOf(at(10, 18, 98f), at(10, 18, 96f)),
                siblings = listOf(at(11, 9, 92f)),
            ),
            zone,
        )
        assertEquals(listOf(ChartPoint(10 * dayMs, 98f), ChartPoint(10 * dayMs, 96f)), series.own)
        assertEquals(listOf(ChartPoint(11 * dayMs, 92f)), series.siblings)
    }

    @Test
    fun `no estimate line when the pipeline pools no estimate`() {
        // Zero-coefficient (bodyweight) exercises never enter the pool, so `merged` is empty.
        val series = exerciseChartSeries(data(own = listOf(at(10, 18, 98f))), zone)
        assertTrue(series.estimate.isEmpty())
        assertEquals(listOf(ChartPoint(10 * dayMs, 98f)), series.own)
    }

    @Test
    fun `points are bucketed by local day, not UTC`() {
        // 03:00 UTC on day 10 is still the evening of day 9 in New York.
        val ny = ZoneId.of("America/New_York")
        val series = exerciseChartSeries(data(merged = listOf(at(10, 3, 100f))), ny)
        assertEquals(listOf(ChartPoint(9 * dayMs, 100f)), series.estimate)
    }
}
