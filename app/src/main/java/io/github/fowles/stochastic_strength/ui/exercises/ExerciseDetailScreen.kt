package io.github.fowles.stochastic_strength.ui.exercises

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.common.insets
import com.patrykandpatrick.vico.compose.cartesian.layer.point
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.common.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
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
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerVisibilityListener
import com.patrykandpatrick.vico.core.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.core.common.Fill
import com.patrykandpatrick.vico.core.common.data.ExtraStore
import com.patrykandpatrick.vico.core.common.shape.CorneredShape
import io.github.fowles.stochastic_strength.data.model.SetFeedback
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.ui.YoutubeFormCard

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
        bottomBar = {
            if (exercise != null) {
                YoutubeFormCard(
                    exerciseName = exercise.name,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp, top = 8.dp),
                )
            }
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
                    onDaySelected = viewModel::selectDay,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                state.selectedDay?.let { day ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    SelectedDayDetail(
                        day = day,
                        exerciseName = exercise.name,
                        primarySets = state.allSets.filter {
                            it.completedAt != null && it.completedAt / 86_400_000L == day
                        },
                        shadowSets = state.shadowSetsByDay[day] ?: emptyList(),
                        weightUnit = state.weightUnit,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ExerciseChart(
    primaryPoints: List<ChartPoint>,
    shadowPoints: List<ChartPoint>,
    weightUnit: WeightUnit,
    onDaySelected: (Long?) -> Unit,
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

    val currentOnDaySelected by rememberUpdatedState(onDaySelected)
    val markerListener = remember {
        object : CartesianMarkerVisibilityListener {
            override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) =
                currentOnDaySelected(targets.firstOrNull()?.x?.toLong())
            override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) =
                currentOnDaySelected(targets.firstOrNull()?.x?.toLong())
        }
    }
    val marker = rememberSelectionMarker()

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
            marker = marker,
            markerVisibilityListener = markerListener,
        ),
        modelProducer = modelProducer,
        scrollState = scrollState,
        modifier = modifier,
    )
}

@Composable
private fun rememberSelectionMarker(): DefaultCartesianMarker {
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

@Composable
private fun SelectedDayDetail(
    day: Long,
    exerciseName: String,
    primarySets: List<WorkoutSet>,
    shadowSets: List<ExerciseSetEntry>,
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    val sdf = remember { SimpleDateFormat("EEEE, MMM d", Locale.getDefault()) }
    Column(modifier = modifier) {
        Text(
            text = sdf.format(Date(day * 86_400_000L)),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (primarySets.isNotEmpty()) {
            ExerciseSetSection(exerciseName, primarySets, weightUnit)
        }
        shadowSets.groupBy { it.exerciseName }.forEach { (name, entries) ->
            ExerciseSetSection(name, entries.map { it.set }, weightUnit)
        }
    }
}

@Composable
private fun ExerciseSetSection(
    exerciseName: String,
    sets: List<WorkoutSet>,
    weightUnit: WeightUnit,
) {
    Text(
        text = exerciseName,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
    sets.sortedBy { it.setNumber }.forEach { set ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Set ${set.setNumber}: ${WeightFormatter.format(set.targetWeight, weightUnit)} × ${set.targetReps}",
                style = MaterialTheme.typography.bodyMedium,
            )
            set.feedback?.let { feedback ->
                Text(
                    text = feedback.displayLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
