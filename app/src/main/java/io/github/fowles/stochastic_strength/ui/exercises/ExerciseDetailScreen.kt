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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import io.github.fowles.stochastic_strength.data.model.Exercise
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.data.model.WorkoutSet
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.MuscleGroup
import io.github.fowles.stochastic_strength.ui.ExerciseSetSection
import io.github.fowles.stochastic_strength.ui.components.BackTopAppBar
import io.github.fowles.stochastic_strength.ui.components.LoadingBox
import io.github.fowles.stochastic_strength.ui.debug.components.formatLineMarkerLabel
import io.github.fowles.stochastic_strength.ui.toSummarySet
import io.github.fowles.stochastic_strength.ui.YoutubeFormCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(exerciseId: Long, onBack: () -> Unit) {
    val viewModel: ExerciseDetailViewModel =
        viewModel(factory = ExerciseDetailViewModel.factory(exerciseId))
    val state by viewModel.state.collectAsState()
    val exercise = state.exercise

    Scaffold(
        topBar = { BackTopAppBar(title = exercise?.name ?: "", onBack = onBack) },
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
            LoadingBox(contentPadding = padding)
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
            ) {
                Button(
                    onClick = { viewModel.toggleDisliked() },
                    colors = if (exercise.isDisliked) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) else ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text("Disliked")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = exercise.isDisliked, onCheckedChange = null)
                }
                Button(
                    onClick = { viewModel.toggleHurtFlag() },
                    colors = if (state.isHurt) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ) else ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text("Hurt")
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = state.isHurt,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onError,
                            checkedTrackColor = MaterialTheme.colorScheme.error,
                            checkedBorderColor = MaterialTheme.colorScheme.error,
                        ),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Estimated One Rep Max",
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
                        exercise = exercise,
                        primarySets = state.primarySetsByDay[day] ?: emptyList(),
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
        val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
        CartesianValueFormatter { _, value, _ ->
            LocalDate.ofEpochDay(value.toLong()).format(fmt)
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
    val marker = rememberSelectionMarker(weightUnit)

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
private fun rememberSelectionMarker(weightUnit: WeightUnit): DefaultCartesianMarker {
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
    val fmt = remember { DateTimeFormatter.ofPattern("MMM d", Locale.getDefault()) }
    val valueFormatter = remember(fmt, weightUnit) {
        DefaultCartesianMarker.ValueFormatter { _, targets ->
            formatLineMarkerLabel(
                targets = targets,
                xLabel = { x -> LocalDate.ofEpochDay(x.toLong()).format(fmt) },
                yLabel = { y -> WeightFormatter.format(y.toFloat(), weightUnit) },
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

@Composable
private fun SelectedDayDetail(
    day: Long,
    exercise: Exercise,
    primarySets: List<WorkoutSet>,
    shadowSets: List<ExerciseSetEntry>,
    weightUnit: WeightUnit,
    modifier: Modifier = Modifier,
) {
    val fmt = remember { DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault()) }
    Column(modifier = modifier) {
        Text(
            text = LocalDate.ofEpochDay(day).format(fmt),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        if (primarySets.isNotEmpty()) {
            ExerciseSetSection(exercise.name, primarySets.map { it.toSummarySet(exercise.isTimed) }, weightUnit)
        }
        shadowSets.groupBy { it.exerciseName }.forEach { (name, entries) ->
            ExerciseSetSection(name, entries.map { it.set.toSummarySet(it.isTimed) }, weightUnit)
        }
    }
}

