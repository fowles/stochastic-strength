package io.github.fowles.stochastic_strength.ui.debug.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import io.github.fowles.stochastic_strength.ui.components.paddedChartRangeProvider
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

enum class ProgressionSeriesStyle { LINE, FILLED_DOTS, HOLLOW_DOTS }
enum class ProgressionColorRole { OWN, SIBLINGS, MERGED, OWN_OBS, SIBLING_OBS }

data class ProgressionChartSeries(
    val label: String,
    val points: List<DebugChartPoint>,
    val style: ProgressionSeriesStyle,
    val colorRole: ProgressionColorRole,
)

/** Lines first, dots last, so dots render on top of the lines. Stable within each group. */
internal fun seriesPlotOrder(series: List<ProgressionChartSeries>): List<ProgressionChartSeries> =
    series.sortedBy { if (it.style == ProgressionSeriesStyle.LINE) 0 else 1 }

@Composable
internal fun ExerciseProgressionChart(
    series: List<ProgressionChartSeries>,
    yFormatter: (Float) -> String,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val ordered = remember(series) { seriesPlotOrder(series) }
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(ordered, zone) {
        modelProducer.runTransaction {
            lineSeries {
                ordered.forEach { s ->
                    // A single-point or empty series still needs a slot to keep line<->style indices aligned.
                    series(
                        x = s.points.map { timestampToLocalEpochDay(it.timestampMs, zone) }.ifEmpty { listOf(0L) },
                        y = s.points.map { it.value }.ifEmpty { listOf(0f) },
                    )
                }
            }
        }
    }

    val colors = progressionColors()
    val transparent = remember { LineCartesianLayer.LineFill.single(Fill.Transparent) }
    val lines = ordered.map { s ->
        val color = colors.getValue(s.colorRole)
        when (s.style) {
            ProgressionSeriesStyle.LINE -> LineCartesianLayer.rememberLine(
                fill = LineCartesianLayer.LineFill.single(fill(color)),
            )
            ProgressionSeriesStyle.FILLED_DOTS -> LineCartesianLayer.rememberLine(
                fill = transparent,
                pointProvider = LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.point(rememberShapeComponent(fill(color), CorneredShape.Pill), size = 8.dp),
                ),
            )
            ProgressionSeriesStyle.HOLLOW_DOTS -> LineCartesianLayer.rememberLine(
                fill = transparent,
                pointProvider = LineCartesianLayer.PointProvider.single(
                    LineCartesianLayer.point(
                        rememberShapeComponent(
                            fill = fill(Color.Transparent),
                            shape = CorneredShape.Pill,
                            strokeFill = fill(color),
                            strokeThickness = 1.5.dp,
                        ),
                        size = 8.dp,
                    ),
                ),
            )
        }
    }
    val lineProvider = remember(lines) { LineCartesianLayer.LineProvider.series(lines) }
    val rangeProvider = remember { paddedChartRangeProvider() }

    val yValueFormatter = remember(yFormatter) {
        CartesianValueFormatter { _, value, _ -> yFormatter(value.toFloat()) }
    }
    val dateFormatter = remember(zone) {
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault()).apply { timeZone = TimeZone.getTimeZone(zone) }
        CartesianValueFormatter { _, value, _ -> epochDayLabel(value.toLong(), sdf) }
    }
    val scrollState = rememberVicoScrollState(initialScroll = Scroll.Absolute.End)

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(lineProvider = lineProvider, pointSpacing = 0.dp, rangeProvider = rangeProvider),
            startAxis = VerticalAxis.rememberStart(valueFormatter = yValueFormatter),
            bottomAxis = HorizontalAxis.rememberBottom(valueFormatter = dateFormatter, labelRotationDegrees = 45f),
        ),
        modelProducer = modelProducer,
        scrollState = scrollState,
        modifier = modifier,
    )
}

// Explicit, high-contrast series colors (not dynamic-color tokens): blue=own, grey=siblings,
// red=merged. Fixed hues so the three lines stay distinct on any device/theme. Dots track their
// line's color (own dots blue, sibling dots grey). See memory: reference_dynamic_color_charts.
private val OwnColor = Color(0xFF1E88E5) // blue
private val SiblingColor = Color(0xFF9E9E9E) // grey
private val MergedColor = Color(0xFFE53935) // red

internal fun progressionColors(): Map<ProgressionColorRole, Color> = mapOf(
    ProgressionColorRole.OWN to OwnColor,
    ProgressionColorRole.SIBLINGS to SiblingColor,
    ProgressionColorRole.MERGED to MergedColor,
    ProgressionColorRole.OWN_OBS to OwnColor,
    ProgressionColorRole.SIBLING_OBS to SiblingColor,
)
