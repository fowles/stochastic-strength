package io.github.fowles.stochastic_strength.ui.exercises

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.patrykandpatrick.vico.core.cartesian.AutoScrollCondition
import com.patrykandpatrick.vico.core.cartesian.Scroll
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(exerciseId: Long, onBack: () -> Unit) {
    val viewModel: ExerciseDetailViewModel =
        viewModel(factory = ExerciseDetailViewModel.factory(exerciseId))
    val state by viewModel.state.collectAsState()
    val exercise = state.exercise

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(exercise?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (exercise == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = {}, label = { Text(exercise.equipment.displayName()) })
                AssistChip(onClick = {}, label = { Text(exercise.primaryMuscle.displayName()) })
            }

            if (exercise.secondaryMuscles.isNotEmpty()) {
                Text(
                    text = "Also works: " + exercise.secondaryMuscles.joinToString(", ") { it.displayName() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = exercise.isDisliked,
                    onClick = { viewModel.toggleDisliked() },
                    label = { Text("Disliked") },
                )
                if (exercise.hurtFlag) {
                    OutlinedButton(onClick = { viewModel.clearHurtFlag() }) {
                        Text("Clear Hurt Flag", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Progress",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )

            if (state.primaryPoints.isEmpty() && state.shadowPoints.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No history yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                ExerciseChart(
                    primaryPoints = state.primaryPoints,
                    shadowPoints = state.shadowPoints,
                    weightUnit = state.weightUnit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ExerciseChart(
    primaryPoints: List<ChartPoint>,
    shadowPoints: List<ChartPoint>,
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    LaunchedEffect(primaryPoints, shadowPoints) {
        modelProducer.runTransaction {
            lineSeries {
                if (primaryPoints.isNotEmpty()) {
                    series(
                        x = primaryPoints.map { it.dateMs / 86_400_000L },
                        y = primaryPoints.map { it.weightKg },
                    )
                }
                if (shadowPoints.isNotEmpty()) {
                    series(
                        x = shadowPoints.map { it.dateMs / 86_400_000L },
                        y = shadowPoints.map { it.weightKg },
                    )
                }
            }
        }
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
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
    val shadowLine = LineCartesianLayer.rememberLine(
        fill = transparentFill,
        pointProvider = LineCartesianLayer.PointProvider.single(
            LineCartesianLayer.point(
                rememberShapeComponent(
                    fill = Fill.Transparent,
                    shape = CorneredShape.Pill,
                    strokeFill = fill(secondaryColor),
                    strokeThickness = 2.dp,
                ),
                size = 10.dp,
            )
        ),
    )

    val hasPrimary = primaryPoints.isNotEmpty()
    val hasShadow = shadowPoints.isNotEmpty()
    val lineProvider = remember(hasPrimary, hasShadow, primaryLine, shadowLine) {
        LineCartesianLayer.LineProvider.series(buildList {
            if (hasPrimary) add(primaryLine)
            if (hasShadow) add(shadowLine)
        })
    }

    val rangeProvider = remember {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                val padding = if (minY == maxY) maxOf(minY * 0.10, 2.5) else (maxY - minY) * 0.15
                return minY - padding
            }
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore): Double {
                val padding = if (minY == maxY) maxOf(maxY * 0.10, 2.5) else (maxY - minY) * 0.15
                return maxY + padding
            }
        }
    }

    val weightFormatter = remember(weightUnit) {
        CartesianValueFormatter { _, value, _ -> WeightFormatter.format(value.toFloat(), weightUnit) }
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

    CartesianChartHost(
        chart = rememberCartesianChart(
            rememberLineCartesianLayer(
                lineProvider = lineProvider,
                pointSpacing = 0.dp,
                rangeProvider = rangeProvider,
            ),
            startAxis = VerticalAxis.rememberStart(valueFormatter = weightFormatter),
            bottomAxis = HorizontalAxis.rememberBottom(
                valueFormatter = dateFormatter,
                labelRotationDegrees = 45f,
            ),
        ),
        modelProducer = modelProducer,
        scrollState = scrollState,
        modifier = modifier,
    )
}

private fun MuscleGroup.displayName(): String =
    name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }

private fun Equipment.displayName(): String =
    name.split('_').joinToString(" ") { it.lowercase().replaceFirstChar { c -> c.uppercase() } }
