package io.github.fowles.stochastic_strength.ui.debug.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseProgressionChartTest {

    private fun s(label: String, style: ProgressionSeriesStyle, role: ProgressionColorRole) =
        ProgressionChartSeries(label, emptyList(), style, role)

    @Test
    fun dotsArePlottedAfterLinesSoTheyRenderOnTop() {
        val input = listOf(
            s("own dots", ProgressionSeriesStyle.FILLED_DOTS, ProgressionColorRole.OWN_OBS),
            s("own line", ProgressionSeriesStyle.LINE, ProgressionColorRole.OWN),
            s("sib dots", ProgressionSeriesStyle.HOLLOW_DOTS, ProgressionColorRole.SIBLING_OBS),
            s("merged line", ProgressionSeriesStyle.LINE, ProgressionColorRole.MERGED),
        )
        val ordered = seriesPlotOrder(input)
        assertEquals(
            listOf(ProgressionSeriesStyle.LINE, ProgressionSeriesStyle.LINE, ProgressionSeriesStyle.FILLED_DOTS, ProgressionSeriesStyle.HOLLOW_DOTS),
            ordered.map { it.style },
        )
    }
}
