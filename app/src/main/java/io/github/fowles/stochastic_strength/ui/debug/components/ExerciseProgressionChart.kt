package io.github.fowles.stochastic_strength.ui.debug.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.compose.common.shape.markerCorneredShape
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import io.github.fowles.stochastic_strength.ui.components.paddedChartRangeProvider
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone

enum class ProgressionSeriesStyle { LINE, FILLED_DOTS, HOLLOW_DOTS }
enum class ProgressionColorRole { OWN, SIBLINGS, MERGED, OWN_OBS, SIBLING_OBS }

private class ValueHolder(var value: Long? = null)

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
    selectedSessionEpochDay: Long? = null,
    onSelectEpochDay: (Long) -> Unit = {},
    tooltipLabel: (epochDay: Long) -> CharSequence = { "" },
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
    val tooltipLabelState = rememberUpdatedState(tooltipLabel)
    val markerValueFormatter = remember {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            val epochDay = targets.firstOrNull()?.x?.toLong()
            epochDay?.let { tooltipLabelState.value(it) } ?: ""
        }
    }
    val markerLabel = rememberTextComponent(
        color = MaterialTheme.colorScheme.onSurface,
        background = rememberShapeComponent(
            fill = fill(MaterialTheme.colorScheme.surface),
            shape = markerCorneredShape(CorneredShape.Corner.Rounded),
            strokeThickness = 1.dp,
            strokeFill = fill(MaterialTheme.colorScheme.outline),
        ),
        padding = insets(8.dp, 4.dp),
    )
    val marker = rememberDefaultCartesianMarker(label = markerLabel, valueFormatter = markerValueFormatter)
    val onSelectState = rememberUpdatedState(onSelectEpochDay)
    val lastForwarded = remember { ValueHolder() }
    val visibilityListener = remember {
        object : CartesianMarkerVisibilityListener {
            private fun forward(targets: List<CartesianMarker.Target>) {
                val epochDay = targets.firstOrNull()?.x?.toLong() ?: return
                if (lastForwarded.value != epochDay) {
                    lastForwarded.value = epochDay
                    onSelectState.value(epochDay)
                }
            }
            override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) = forward(targets)
            override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) = forward(targets)
            // onHidden intentionally not overridden: selection persists after the finger lifts.
        }
    }
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
            marker = marker,
            markerVisibilityListener = visibilityListener,
            persistentMarkers = { selectedSessionEpochDay?.let { marker at it.toDouble() } },
        ),
        modelProducer = modelProducer,
        scrollState = scrollState,
        modifier = modifier,
    )
}

// Series colors: own=primary (theme dark blue), merged=error (the same red the cross-tuning
// bars use for negative values), siblings=fixed grey. Dots track their line's color (own dots
// primary, sibling dots grey).
private val SiblingColor = Color(0xFF9E9E9E) // grey

@Composable
internal fun progressionColors(): Map<ProgressionColorRole, Color> {
    val own = MaterialTheme.colorScheme.primary
    return mapOf(
        ProgressionColorRole.OWN to own,
        ProgressionColorRole.SIBLINGS to SiblingColor,
        ProgressionColorRole.MERGED to MaterialTheme.colorScheme.error,
        ProgressionColorRole.OWN_OBS to own,
        ProgressionColorRole.SIBLING_OBS to SiblingColor,
    )
}
