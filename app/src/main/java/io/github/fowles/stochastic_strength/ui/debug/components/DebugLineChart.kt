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
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import androidx.compose.material3.MaterialTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugChartPoint(val timestampMs: Long, val value: Float)

@Composable
internal fun DebugLineChart(
    points: List<DebugChartPoint>,
    yFormatter: (Float) -> String,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        modelProducer.runTransaction {
            lineSeries {
                if (points.isNotEmpty()) {
                    series(
                        x = points.map { it.timestampMs / 86_400_000L },
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
    val dateFormatter = remember {
        val sdf = SimpleDateFormat("MMM d", Locale.getDefault())
        CartesianValueFormatter { _, value, _ ->
            sdf.format(Date(value.toLong() * 86_400_000L))
        }
    }

    val scrollState = rememberVicoScrollState(
        initialScroll = Scroll.Absolute.End,
        autoScroll = Scroll.Absolute.End,
        autoScrollCondition = AutoScrollCondition.OnModelGrowth,
    )

    val marker = rememberMarker()

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
private fun rememberMarker(): DefaultCartesianMarker {
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
    val sdf = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
    val dateFormatter = remember(sdf) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            sdf.format(Date((targets.firstOrNull()?.x?.toLong() ?: 0L) * 86_400_000L))
        }
    }
    return rememberDefaultCartesianMarker(
        label = label,
        valueFormatter = dateFormatter,
        guideline = guideline,
        indicatorSize = 0.dp,
    )
}
