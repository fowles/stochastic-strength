package io.github.fowles.stochastic_strength.ui.debug.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
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
import com.patrykandpatrick.vico.core.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import androidx.compose.material3.MaterialTheme
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DebugChartPoint(val timestampMs: Long, val value: Float)

/**
 * Converts a wall-clock timestamp to an epoch-day index anchored in [zone].
 *
 * The chart x-axis must agree with the event-row dates (which are formatted
 * in the system default zone), so the index must reflect the *local*
 * calendar day rather than UTC.
 */
internal fun timestampToLocalEpochDay(timestampMs: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate().toEpochDay()

/**
 * Renders an epoch-day index using [sdf], whose [SimpleDateFormat.timeZone]
 * must match the zone used to produce the index.
 */
internal fun epochDayLabel(epochDay: Long, sdf: SimpleDateFormat): String =
    sdf.format(Date(LocalDate.ofEpochDay(epochDay).atStartOfDay(sdf.timeZone.toZoneId()).toInstant().toEpochMilli()))

/**
 * Builds the floating marker label shown when the user holds a point.
 * Combines the x-axis label with each line-series y value at that point.
 */
internal fun formatLineMarkerLabel(
    targets: List<CartesianMarker.Target>,
    xLabel: (Double) -> String,
    yLabel: (Double) -> String,
): CharSequence {
    val target = targets.firstOrNull() ?: return ""
    val x = xLabel(target.x)
    val ys = (target as? LineCartesianLayerMarkerTarget)?.points?.map { yLabel(it.entry.y) }
        ?: return x
    return if (ys.isEmpty()) x else "$x • ${ys.joinToString(" / ")}"
}

@Composable
internal fun DebugLineChart(
    points: List<DebugChartPoint>,
    yFormatter: (Float) -> String,
    modifier: Modifier = Modifier,
) {
    val zone = remember { ZoneId.systemDefault() }
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points, zone) {
        modelProducer.runTransaction {
            lineSeries {
                if (points.isNotEmpty()) {
                    series(
                        x = points.map { timestampToLocalEpochDay(it.timestampMs, zone) },
                        y = points.map { it.value },
                    )
                }
            }
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val transparentFill = remember { LineCartesianLayer.LineFill.single(Fill.Transparent) }
    val primaryLine = LineCartesianLayer.rememberLine(
        fill = transparentFill,
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.point(
                rememberShapeComponent(fill(primaryColor), CorneredShape.Pill),
                size = 8.dp,
            )
        ),
    )
    val lineProvider = remember(primaryLine) {
        LineCartesianLayer.LineProvider.series(listOf(primaryLine))
    }

    val rangeProvider = remember {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                val padding = if (minY == maxY) maxOf(minY * 0.10, 0.05) else (maxY - minY) * 0.15
                return minY - padding
            }
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                val padding = if (minY == maxY) maxOf(maxY * 0.10, 0.05) else (maxY - minY) * 0.15
                return maxY + padding
            }
        }
    }

    val yValueFormatter = remember(yFormatter) {
        CartesianValueFormatter { _, value, _ -> yFormatter(value.toFloat()) }
    }
    val dateFormatter = remember(zone) {
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault()).apply {
            timeZone = TimeZone.getTimeZone(zone)
        }
        CartesianValueFormatter { _, value, _ ->
            epochDayLabel(value.toLong(), sdf)
        }
    }

    val scrollState = rememberVicoScrollState(
        initialScroll = Scroll.Absolute.End,
        autoScroll = Scroll.Absolute.End,
        autoScrollCondition = AutoScrollCondition.OnModelGrowth,
    )

    val marker = rememberMarker(yFormatter)

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = lineProvider,
                pointSpacing = 0.dp,
                rangeProvider = rangeProvider,
            ),
            startAxis = VerticalAxis.rememberStart(valueFormatter = yValueFormatter),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = dateFormatter,
                labelRotationDegrees = 45f,
            ),
            marker = marker,
        ),
        modelProducer = modelProducer,
        scrollState = scrollState,
        modifier = modifier,
    )
}

@Composable
private fun rememberMarker(yFormatter: (Float) -> String): DefaultCartesianMarker {
    val labelBackground = rememberShapeComponent(
        fill = fill(MaterialTheme.colorScheme.surface),
        shape = CorneredShape.Pill,
        strokeFill = fill(MaterialTheme.colorScheme.outline),
        strokeThickness = 1.dp,
    )
    val label = rememberTextComponent(
        color = MaterialTheme.colorScheme.onSurface,
        padding = insets(8.dp, 4.dp),
        background = labelBackground,
    )
    val guideline = rememberAxisGuidelineComponent()
    val sdf = remember {
        SimpleDateFormat("MMM d", Locale.getDefault()).apply {
            timeZone = TimeZone.getDefault()
        }
    }
    val valueFormatter = remember(sdf, yFormatter) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            formatLineMarkerLabel(
                targets = targets,
                xLabel = { x -> epochDayLabel(x.toLong(), sdf) },
                yLabel = { y -> yFormatter(y.toFloat()) },
            )
        }
    }
    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = valueFormatter,
        guideline = guideline,
        indicatorSize = 0.dp,
    )
}
