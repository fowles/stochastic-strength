package io.github.fowles.stochastic_strength.ui.debug.components

import com.patrykandpatrick.vico.core.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartMarkerLabelTest {

    @Test
    fun `single point label combines x and y formatted values`() {
        val target = fakeLineTarget(x = 20_250.0, ys = listOf(145.0))

        val label = formatLineMarkerLabel(
            targets = listOf(target),
            xLabel = { "Jun 13" },
            yLabel = { "%.0f lbs".format(it) },
        )

        assertEquals("Jun 13 • 145 lbs", label.toString())
    }

    @Test
    fun `multi-series point joins y values with slash`() {
        val target = fakeLineTarget(x = 20_250.0, ys = listOf(145.0, 150.0))

        val label = formatLineMarkerLabel(
            targets = listOf(target),
            xLabel = { "Jun 13" },
            yLabel = { "%.0f lbs".format(it) },
        )

        assertEquals("Jun 13 • 145 lbs / 150 lbs", label.toString())
    }

    @Test
    fun `empty targets yields empty label`() {
        val label = formatLineMarkerLabel(
            targets = emptyList(),
            xLabel = { "Jun 13" },
            yLabel = { "${it.toInt()}" },
        )

        assertEquals("", label.toString())
    }

    @Test
    fun `excludeColor drops the matching-color point and keeps the rest`() {
        val target = fakeColoredTarget(
            x = 20_250.0,
            points = listOf(182.0 to 0x000000, 185.0 to 0xFF3366),
        )

        val label = formatLineMarkerLabel(
            targets = listOf(target),
            xLabel = { "May 4" },
            yLabel = { "%.0f lbs".format(it) },
            excludeColor = 0xFF3366,
        )

        assertEquals("May 4 • 182 lbs", label.toString())
    }

    @Test
    fun `excludeColor that removes every point yields x label only`() {
        val target = fakeColoredTarget(x = 20_250.0, points = listOf(185.0 to 0xFF3366))

        val label = formatLineMarkerLabel(
            targets = listOf(target),
            xLabel = { "May 4" },
            yLabel = { "%.0f lbs".format(it) },
            excludeColor = 0xFF3366,
        )

        assertEquals("May 4", label.toString())
    }

    @Test
    fun `non-line target falls back to x label only`() {
        val target = object : CartesianMarker.Target {
            override val x: Double = 20_250.0
            override val canvasX: Float = 0f
        }

        val label = formatLineMarkerLabel(
            targets = listOf(target),
            xLabel = { "Jun 13" },
            yLabel = { "${it.toInt()}" },
        )

        assertEquals("Jun 13", label.toString())
    }

    private fun fakeLineTarget(x: Double, ys: List<Double>): LineCartesianLayerMarkerTarget {
        val points = ys.map { y ->
            LineCartesianLayerMarkerTarget.Point(
                entry = LineCartesianLayerModel.Entry(x, y),
                canvasY = 0f,
                color = 0,
            )
        }
        return object : LineCartesianLayerMarkerTarget {
            override val x: Double = x
            override val canvasX: Float = 0f
            override val points: List<LineCartesianLayerMarkerTarget.Point> = points
        }
    }

    private fun fakeColoredTarget(
        x: Double,
        points: List<Pair<Double, Int>>,
    ): LineCartesianLayerMarkerTarget {
        val mapped = points.map { (y, color) ->
            LineCartesianLayerMarkerTarget.Point(
                entry = LineCartesianLayerModel.Entry(x, y),
                canvasY = 0f,
                color = color,
            )
        }
        return object : LineCartesianLayerMarkerTarget {
            override val x: Double = x
            override val canvasX: Float = 0f
            override val points: List<LineCartesianLayerMarkerTarget.Point> = mapped
        }
    }
}
