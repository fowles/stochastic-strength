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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WeightFormatter.formatQuantity
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise
import io.github.fowles.stochastic_strength.ui.components.CircuitRailGutter
import io.github.fowles.stochastic_strength.ui.components.ExerciseRowBody
import io.github.fowles.stochastic_strength.ui.components.ExerciseRowScaffold
import io.github.fowles.stochastic_strength.ui.components.LinkNodeHost
import io.github.fowles.stochastic_strength.ui.components.RowPlace
import io.github.fowles.stochastic_strength.ui.components.SuggestionNote
import io.github.fowles.stochastic_strength.ui.components.ValueStepper
import io.github.fowles.stochastic_strength.ui.components.WeightOrBodyweightTrailing
import io.github.fowles.stochastic_strength.ui.components.keyedBlocks
import io.github.fowles.stochastic_strength.ui.components.linkAbove
import io.github.fowles.stochastic_strength.ui.components.rowPlace
import kotlin.math.roundToInt

@Composable
internal fun PlanPreviewContent(
    state: WorkoutState.PlanPreview,
    weightUnit: WeightUnit,
    onStart: () -> Unit,
    onReplace: (exerciseId: Long, reason: ExerciseRemovalReason) -> Unit,
    onSetExerciseCount: (Int) -> Unit,
    onSetRepRange: (repMin: Int, repMax: Int) -> Unit,
    onAdjustWeight: (exerciseId: Long, steps: Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onLink: (rowIndex: Int) -> Unit,
    onUnlink: (rowIndex: Int) -> Unit,
    onSetSets: (exerciseId: Long, sets: Int) -> Unit,
    onSetReps: (exerciseId: Long, reps: Int) -> Unit,
    onResetReps: (exerciseId: Long) -> Unit,
    onResetWeight: (exerciseId: Long) -> Unit,
    suggestWeight: (PlannedExercise) -> Float,
    onEditLocation: (locationId: Long) -> Unit,
    onExerciseTap: (exerciseId: Long) -> Unit,
    hasSavedWorkouts: Boolean,
    onAddExercise: () -> Unit,
    onLoadWorkout: () -> Unit,
    onAppendWorkout: () -> Unit,
    onSaveWorkout: () -> Unit,
) {
    val plan = state.plan
    val totalSets = plan.exercises.sumOf { it.sets }
    val durationMin = plan.estimatedDurationSeconds / 60

    var sliderValue by remember(state.targetCount) { mutableFloatStateOf(state.targetCount.toFloat()) }
    var repRangeValue by remember(state.repMin, state.repMax) {
        mutableStateOf(state.repMin.toFloat()..state.repMax.toFloat())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Today's Workout", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            PlanPreviewMenu(
                hasSavedWorkouts = hasSavedWorkouts,
                onAddExercise = onAddExercise,
                onLoadWorkout = onLoadWorkout,
                onAppendWorkout = onAppendWorkout,
                onSaveWorkout = onSaveWorkout,
            )
        }
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
            "Swipe left to reject · tap a dimmed number's − or + to set it yourself",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        HorizontalDivider()
        val lazyListState = rememberLazyListState()
        val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
            onMove(from.index, to.index)
        }

        val blocks = remember(plan.exercises) { keyedBlocks(plan.exercises) { it.exercise.id } }
        LazyColumn(state = lazyListState, modifier = Modifier.weight(1f)) {
            items(blocks, key = { it.key }) { keyed ->
                val block = keyed.block
                ReorderableItem(reorderState, key = keyed.key) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp, label = "dragElevation")
                    Column(modifier = Modifier.animateItem().graphicsLayer { shadowElevation = elevation.toPx() }) {
                        for (i in block.indices) {
                            val planned = keyed.rows[i - block.start]
                            key(planned.exercise.id) {
                                // Memoized: `linkAbove` is a plain function, so its toggle lambda
                                // would otherwise be a fresh, never-equal instance every
                                // composition and stop ExercisePreviewRow ever skipping. `block`
                                // is an all-Int data class and `i` an Int, so they compare
                                // properly; onLink/onUnlink are bound references to the screen's
                                // view model, so pinning them here can't capture a stale callback.
                                val (linkedAbove, toggleLink) = remember(block, i) {
                                    linkAbove(block, i, onLink = onLink, onUnlink = onUnlink)
                                }
                                ExercisePreviewRow(
                                    planned = planned,
                                    weightUnit = weightUnit,
                                    place = rowPlace(block, i),
                                    sets = block.rounds,
                                    dragHandleModifier = Modifier.draggableHandle(),
                                    // Only a pinned row can show a suggestion, so only a
                                    // pinned row pays for one on every recomposition.
                                    suggestedWeight =
                                        if (planned.weightPinned) suggestWeight(planned) else 0f,
                                    onReplace = { reason -> onReplace(planned.exercise.id, reason) },
                                    onAdjustWeight = { steps -> onAdjustWeight(planned.exercise.id, steps) },
                                    onRepsChange = { onSetReps(planned.exercise.id, it) },
                                    onResetReps = { onResetReps(planned.exercise.id) },
                                    onResetWeight = { onResetWeight(planned.exercise.id) },
                                    onTap = { onExerciseTap(planned.exercise.id) },
                                    onSetsChange = { onSetSets(planned.exercise.id, it) },
                                    flag = state.rowFlags[planned.exercise.id],
                                    linkedAbove = linkedAbove,
                                    onToggleLink = toggleLink,
                                )
                            }
                        }
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
    planned: PlannedExercise,
    weightUnit: WeightUnit,
    place: RowPlace,
    sets: Int,
    dragHandleModifier: Modifier,
    /** The live prescription, shown beside a pin that differs from it; 0 on an unpinned row. */
    suggestedWeight: Float,
    onReplace: (ExerciseRemovalReason) -> Unit,
    onAdjustWeight: (Int) -> Unit,
    onRepsChange: (Int) -> Unit,
    onResetReps: () -> Unit,
    onResetWeight: () -> Unit,
    onTap: () -> Unit,
    onSetsChange: (Int) -> Unit,
    flag: RowFlag?,
    linkedAbove: Boolean?,
    onToggleLink: () -> Unit,
) {
    var showActions by remember(planned.exercise.id) { mutableStateOf(false) }

    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.currentValue }
            .collect { if (it != SwipeToDismissBoxValue.Settled) showActions = true }
    }

    // The node lives outside the swipe box below, so it never sees that box's own internal
    // translation; feed it the same live offset directly. Once the action row takes over, the
    // row's content is no longer translated (the swipe box itself is gone), so the node offset
    // resets to 0 rather than dragging the stale swiped-away distance along with it.
    val swipeOffsetPx = {
        // requireOffset() throws until the swipe box's anchors have been initialized by its first
        // measurement, and this lambda can be read before then (first frame, or a row recomposed
        // into the list); 0 is the right offset in that window anyway.
        linkNodeSwipeOffsetPx(showActions, runCatching { dismissState.requireOffset() }.getOrDefault(0f))
    }

    LinkNodeHost(linkedAbove = linkedAbove, onToggleLink = onToggleLink, swipeOffsetPx = swipeOffsetPx) {
        if (showActions) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            ) {
                // Only a circuit member needs the gutter — it's what keeps the rail continuous
                // through a swiped row. A SOLO row draws no rail, and while it can still carry an
                // unlinked node, that node sits at a fixed offset from the host Box and does not
                // depend on the gutter. So a gutter there would just cost the action row (its
                // three buttons and progress bar) 36dp of width for nothing.
                if (place != RowPlace.SOLO) CircuitRailGutter(place)
                ExerciseActionRow(
                    name = planned.exercise.name,
                    onAction = { reason ->
                        showActions = false
                        onReplace(reason)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
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
                ExerciseRowScaffold(
                    place = place,
                    sets = sets,
                    onSetsChange = onSetsChange,
                    dragHandleModifier = dragHandleModifier,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable(onClick = onTap)
                        .padding(vertical = 8.dp),
                    trailing = {
                        // The note sits under the weight, in the height the trailing column already
                        // has spare (the body's name + reps stepper is taller than the stepper
                        // alone), so a pinned weight never makes the row grow.
                        WeightOrBodyweightTrailing(
                            showWeight = planned.sessionWeight > 0f,
                            isBodyweight = planned.exercise.equipment == Equipment.BODYWEIGHT,
                            stepper = {
                                ValueStepper(
                                    text = WeightFormatter.format(planned.sessionWeight, weightUnit),
                                    pinned = planned.weightPinned,
                                    unit = null,
                                    onDecrement = { onAdjustWeight(-1) },
                                    onIncrement = { onAdjustWeight(+1) },
                                    onReset = onResetWeight,
                                    fewerDescription = "Less weight",
                                    moreDescription = "More weight",
                                    canDecrement = !WeightFormatter.atFloor(planned.sessionWeight, weightUnit),
                                )
                            },
                            note = {
                                SuggestionNote(
                                    pinnedKg = planned.sessionWeight.takeIf { planned.weightPinned },
                                    suggestedKg = suggestedWeight,
                                    unit = weightUnit,
                                )
                            },
                        )
                    },
                ) {
                    ExerciseRowBody(
                        name = planned.exercise.name,
                        timedText = if (planned.exercise.isTimed) formatQuantity(planned.sessionReps, true) else null,
                        reps = {
                            ValueStepper(
                                text = "${planned.sessionReps}",
                                pinned = planned.repsPinned,
                                unit = "reps",
                                onDecrement = { onRepsChange(planned.sessionReps - 1) },
                                onIncrement = { onRepsChange(planned.sessionReps + 1) },
                                onReset = onResetReps,
                                fewerDescription = "One rep fewer",
                                moreDescription = "One rep more",
                                canDecrement = planned.sessionReps > PlannedExercise.PINNED_REPS.first,
                                canIncrement = planned.sessionReps < PlannedExercise.PINNED_REPS.last,
                            )
                        },
                        extra = {
                            flag?.let {
                                Text(
                                    when (it) {
                                        RowFlag.NOT_AT_LOCATION -> "Missing equipment"
                                        RowFlag.TRAINED_RECENTLY -> "Trained recently"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        },
                    )
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

/**
 * The offset a row's own [LinkNodeHost][io.github.fowles.stochastic_strength.ui.components.LinkNodeHost]
 * node should track: the row's live swipe offset while it's still showing its swipe box, or 0
 * once [showingActions] — at that point the row's content is no longer translated (the swipe box
 * itself is gone, replaced by the action row), so the node must not keep the stale swiped-away
 * [rawOffsetPx] either.
 */
internal fun linkNodeSwipeOffsetPx(showingActions: Boolean, rawOffsetPx: Float): Float =
    if (showingActions) 0f else rawOffsetPx

private const val MAX_EXERCISE_COUNT = 15
private const val REP_RANGE_MIN = 1
private const val REP_RANGE_MAX = 20
