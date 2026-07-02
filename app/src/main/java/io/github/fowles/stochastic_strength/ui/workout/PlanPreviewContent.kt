package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.LocationOn
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WeightFormatter.formatQuantity
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import kotlin.math.roundToInt

@Composable
internal fun PlanPreviewContent(
    state: WorkoutState.PlanPreview,
    weightUnit: WeightUnit,
    onStart: () -> Unit,
    onReplace: (exerciseId: Long, reason: ExerciseRemovalReason) -> Unit,
    onSetExerciseCount: (Int) -> Unit,
    onSetRepRange: (repMin: Int, repMax: Int) -> Unit,
    onAdjustWeight: (exerciseId: Long, delta: Float) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onEditLocation: (locationId: Long) -> Unit,
    onExerciseTap: (exerciseId: Long) -> Unit,
) {
    val plan = state.plan
    val totalSets = plan.exercises.size * PlannedExercise.DEFAULT_SETS
    val durationMin = plan.estimatedDurationSeconds / 60

    var sliderValue by remember { mutableFloatStateOf(plan.exercises.size.toFloat()) }
    var repRangeValue by remember(state.repMin, state.repMax) {
        mutableStateOf(state.repMin.toFloat()..state.repMax.toFloat())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Text("Today's Workout", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "$durationMin min · ${plan.exercises.size} exercises · $totalSets sets",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val locationId = state.plan.locationId
        val locationName = state.locationName
        if (locationId != null && locationName != null) {
            Spacer(Modifier.height(4.dp))
            AssistChip(
                onClick = { onEditLocation(locationId) },
                label = { Text(locationName) },
                leadingIcon = {
                    Icon(Icons.Filled.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Shorter",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onSetExerciseCount(sliderValue.roundToInt()) },
                valueRange = 1f..MAX_EXERCISE_COUNT.toFloat(),
                steps = MAX_EXERCISE_COUNT - 2,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(
                "Longer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Fewer reps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RangeSlider(
                value = repRangeValue,
                onValueChange = { repRangeValue = it },
                onValueChangeFinished = {
                    onSetRepRange(
                        repRangeValue.start.roundToInt(),
                        repRangeValue.endInclusive.roundToInt(),
                    )
                },
                valueRange = REP_RANGE_MIN.toFloat()..REP_RANGE_MAX.toFloat(),
                steps = REP_RANGE_MAX - REP_RANGE_MIN - 1,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text(
                "More reps",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text(
            "Swipe left to reject an exercise",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        HorizontalDivider()
        val lazyListState = rememberLazyListState()
        val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
            onMove(from.index, to.index)
        }

        LazyColumn(state = lazyListState, modifier = Modifier.weight(1f)) {
            items(plan.exercises, key = { it.exercise.id }) { planned ->
                ReorderableItem(reorderState, key = planned.exercise.id, modifier = Modifier.animateItem()) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp, label = "dragElevation")
                    Column(modifier = Modifier.graphicsLayer { shadowElevation = elevation.toPx() }) {
                        ExercisePreviewRow(
                            planned = planned,
                            weightUnit = weightUnit,
                            dragHandleModifier = Modifier.draggableHandle(),
                            onReplace = { reason -> onReplace(planned.exercise.id, reason) },
                            onWeightDecrement = if (planned.sessionWeight > 0f) {
                                { onAdjustWeight(planned.exercise.id, -2.5f) }
                            } else null,
                            onWeightIncrement = if (planned.sessionWeight > 0f) {
                                { onAdjustWeight(planned.exercise.id, +2.5f) }
                            } else null,
                            onTap = { onExerciseTap(planned.exercise.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStart, enabled = plan.exercises.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text("Let's Go")
        }
    }
}

@Composable
private fun ExercisePreviewRow(
    planned: io.github.fowles.stochastic_strength.domain.model.PlannedExercise,
    weightUnit: WeightUnit,
    dragHandleModifier: Modifier,
    onReplace: (ExerciseRemovalReason) -> Unit,
    onWeightDecrement: (() -> Unit)?,
    onWeightIncrement: (() -> Unit)?,
    onTap: () -> Unit,
) {
    var showActions by remember(planned.exercise.id) { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.currentValue }
            .collect { if (it != SwipeToDismissBoxValue.Settled) showActions = true }
    }

    if (showActions) {
        ExerciseActionRow(
            name = planned.exercise.name,
            onAction = { reason ->
                showActions = false
                onReplace(reason)
            },
        )
    } else {
        SwipeToDismissBox(
            state = dismissState,
            backgroundContent = {
                val alpha = dismissState.progress.coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.error.copy(alpha = alpha)),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError.copy(alpha = alpha),
                        modifier = Modifier
                            .padding(end = 24.dp)
                            .size(36.dp),
                    )
                }
            },
            enableDismissFromStartToEnd = false,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(onClick = onTap)
                    .padding(vertical = 12.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.DragIndicator,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = dragHandleModifier
                        .padding(start = 4.dp, end = 8.dp)
                        .size(24.dp),
                )
                val weightLabel = when {
                    planned.sessionWeight > 0f -> WeightFormatter.format(planned.sessionWeight, weightUnit)
                    planned.exercise.equipment == Equipment.BODYWEIGHT -> "Bodyweight"
                    else -> null
                }
                val repsLabel = formatQuantity(planned.sessionReps, planned.exercise.isTimed)
                Column(modifier = Modifier.weight(1f)) {
                    Text(planned.exercise.name, style = MaterialTheme.typography.titleMedium)
                    val detail = buildString {
                        append("${PlannedExercise.DEFAULT_SETS} sets × $repsLabel")
                        if (onWeightDecrement == null && weightLabel != null) append(" · $weightLabel")
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onWeightDecrement != null && onWeightIncrement != null && weightLabel != null) {
                    OutlinedButton(
                        onClick = onWeightDecrement,
                        modifier = Modifier.size(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("−", style = MaterialTheme.typography.labelLarge)
                    }
                    Text(
                        weightLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 64.dp),
                    )
                    OutlinedButton(
                        onClick = onWeightIncrement,
                        modifier = Modifier.size(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) {
                        Text("+", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseActionRow(
    name: String,
    onAction: (ExerciseRemovalReason) -> Unit,
    modifier: Modifier = Modifier,
) {
    val autoSkipProgress = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        autoSkipProgress.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = 4000, easing = LinearEasing),
        )
        onAction(ExerciseRemovalReason.SKIP_TODAY)
    }

    Column(modifier = modifier.padding(vertical = 12.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { onAction(ExerciseRemovalReason.NO_EQUIPMENT) },
                modifier = Modifier.weight(1f),
            ) { Text("No gear", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = { onAction(ExerciseRemovalReason.DISLIKE) },
                modifier = Modifier.weight(1f),
            ) { Text("Hate it", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton(
                onClick = { onAction(ExerciseRemovalReason.SKIP_TODAY) },
                modifier = Modifier.weight(1f),
            ) { Text("Not today", style = MaterialTheme.typography.labelSmall) }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { autoSkipProgress.value },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val MAX_EXERCISE_COUNT = 15
private const val REP_RANGE_MIN = 1
private const val REP_RANGE_MAX = 20
