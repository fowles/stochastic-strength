package io.github.fowles.stochastic_strength.ui.savedworkouts

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.data.model.Equipment
import io.github.fowles.stochastic_strength.data.model.WeightUnit
import io.github.fowles.stochastic_strength.domain.RowSuggester
import io.github.fowles.stochastic_strength.domain.WeightFormatter
import io.github.fowles.stochastic_strength.domain.WeightFormatter.formatQuantity
import io.github.fowles.stochastic_strength.domain.model.PlannedExercise.Companion.PINNED_REPS
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutEntry
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutNaming
import io.github.fowles.stochastic_strength.ui.components.BackTopAppBar
import io.github.fowles.stochastic_strength.ui.components.ExercisePickerSheet
import io.github.fowles.stochastic_strength.ui.components.ExerciseRowScaffold
import io.github.fowles.stochastic_strength.ui.components.LinkNodeHost
import io.github.fowles.stochastic_strength.ui.components.LoadingBox
import io.github.fowles.stochastic_strength.ui.components.RowPlace
import io.github.fowles.stochastic_strength.ui.components.SuggestionNote
import io.github.fowles.stochastic_strength.ui.components.ValueStepper
import io.github.fowles.stochastic_strength.ui.components.keyedBlocks
import io.github.fowles.stochastic_strength.ui.components.rowPlace
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedWorkoutEditScreen(
    workoutId: Long,
    onBack: () -> Unit,
    viewModel: SavedWorkoutEditViewModel = viewModel(factory = SavedWorkoutEditViewModel.factory(workoutId)),
) {
    val state by viewModel.state.collectAsState()
    val allExercises by viewModel.allExercises.collectAsState()
    val suggester by viewModel.suggester.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()
    var showPicker by rememberSaveable { mutableStateOf(false) }

    var showDiscard by rememberSaveable { mutableStateOf(false) }

    // Only Done saves. Back leaves without saving, asking first if that would lose edits.
    val leave = { if (viewModel.hasUnsavedChanges()) showDiscard = true else onBack() }
    BackHandler(onBack = leave)

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("Tap Done to keep them.") },
            confirmButton = { TextButton(onClick = { showDiscard = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text("Keep editing") } },
        )
    }

    val title = if (workoutId == SavedWorkoutEditViewModel.NEW_WORKOUT_ID) "New workout" else "Edit workout"
    Scaffold(topBar = { BackTopAppBar(title = title, onBack = leave) }) { paddingValues ->
        when (state.status) {
            LoadStatus.LOADING -> {
                LoadingBox(contentPadding = paddingValues)
                return@Scaffold
            }
            LoadStatus.MISSING -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("This workout no longer exists.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                return@Scaffold
            }
            LoadStatus.LOADED -> Unit
        }
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::setName,
                // No label: M3 hides the placeholder behind an in-box label until first focus, and the
                // derived name must be visible (and track exercise edits) from the start.
                placeholder = { Text(SavedWorkoutNaming.defaultName(state.entries.map { it.exercise.name })) },
                supportingText = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Drag to reorder · swipe left to remove · dimmed numbers are suggestions",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            HorizontalDivider()
            val blocks = remember(state.entries) { keyedBlocks(state.entries) { it.exercise.id } }
            val lazyListState = rememberLazyListState()
            val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
                viewModel.move(from.index, to.index)
            }
            LazyColumn(state = lazyListState, modifier = Modifier.weight(1f)) {
                // One item per block, so a drag carries a whole circuit. The smallest member id is a
                // key that survives the drag.
                items(blocks, key = { it.key }) { keyed ->
                    val block = keyed.block
                    ReorderableItem(reorderState, key = keyed.key) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 4.dp else 0.dp, label = "dragElevation")
                        Column(modifier = Modifier.animateItem().graphicsLayer { shadowElevation = elevation.toPx() }) {
                            for (i in block.indices) {
                                val entry = keyed.rows[i - block.start]
                                key(entry.exercise.id) {
                                    LinkNodeHost(
                                        linkedAbove = if (i == 0) null else i != block.start,
                                        onToggleLink = {
                                            if (i != block.start) viewModel.unlink(i - 1) else viewModel.link(i - 1)
                                        },
                                    ) {
                                        EntryRow(
                                            entry = entry,
                                            place = rowPlace(block, i),
                                            sets = block.rounds,
                                            dragHandleModifier = Modifier.draggableHandle(),
                                            suggester = suggester,
                                            weightUnit = weightUnit,
                                            onRemove = { viewModel.removeExercise(entry.exercise.id) },
                                            onRepsChange = { reps -> viewModel.setReps(entry.exercise.id, reps) },
                                            onWeightChange = { weight -> viewModel.setWeight(entry.exercise.id, weight) },
                                            onSetsChange = { sets -> viewModel.setSets(entry.exercise.id, sets) },
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("Add exercise")
            }
            Button(
                onClick = { viewModel.save(); onBack() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text("Done")
            }
        }
    }

    if (showPicker) {
        val excludeIds = remember(state.entries) { state.entries.map { it.exercise.id }.toSet() }
        ExercisePickerSheet(
            exercises = allExercises,
            excludeIds = excludeIds,
            onPick = { id -> showPicker = false; viewModel.addExercise(id) },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun EntryRow(
    entry: SavedWorkoutEntry,
    place: RowPlace,
    sets: Int,
    dragHandleModifier: Modifier,
    suggester: RowSuggester?,
    weightUnit: WeightUnit?,
    onRemove: () -> Unit,
    onRepsChange: (Int?) -> Unit,
    onWeightChange: (Float?) -> Unit,
    onSetsChange: (Int) -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState) {
        snapshotFlow { dismissState.currentValue }
            .collect { if (it == SwipeToDismissBoxValue.EndToStart) onRemove() }
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(end = 24.dp).size(36.dp),
                )
            }
        },
    ) {
        // Priced once per (suggester, exercise, reps) rather than on every recomposition: the
        // stepper reads this value, so it cannot be skipped the way Today's workout skips
        // unpinned rows. Keyed on the suggester itself, so a planner rebuild reprices.
        val suggested = remember(suggester, entry.exercise, entry.reps) {
            suggester?.weight(entry.exercise, entry.reps)
        }
        // Stored rows arrive raw (a backup import writes what it was given): a non-positive weight
        // is no pin at all, the same reading the planner takes.
        val pinnedWeight = entry.weight?.takeIf { it > 0f }
        // A pinned weight is the user's own number: it shows (and steps) with nothing but a unit,
        // even while the suggester is still building or has no estimate for this lift. Until the
        // unit itself is known there is no grid to show it on, so the row waits.
        val unit = suggester?.weightUnit ?: weightUnit
        val shownWeight = pinnedWeight ?: suggested ?: 0f
        // Whether the row carries a weight is the exercise's own property, never the stored value:
        // a hand-edited weight on a bodyweight row must not conjure a stepper the session ignores.
        // Until the suggester lands, only an existing pin on a plausibly loadable row shows one.
        val weightUnitOrNull = unit?.takeIf {
            suggester?.canCarryWeight(entry.exercise)
                ?: (pinnedWeight != null && !entry.exercise.isTimed &&
                    entry.exercise.equipment.canCarryWeight)
        }
        ExerciseRowScaffold(
            place = place,
            sets = sets,
            onSetsChange = onSetsChange,
            dragHandleModifier = dragHandleModifier,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 8.dp),
            trailing = {
                when {
                    // The note sits under the weight, in the height the trailing column already
                    // has spare (the body's name + reps stepper is taller than the stepper
                    // alone), so a pinned weight never makes the row grow.
                    weightUnitOrNull != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        ValueStepper(
                            text = WeightFormatter.format(shownWeight, weightUnitOrNull),
                            pinned = pinnedWeight != null,
                            unit = null,
                            onDecrement = { onWeightChange(WeightFormatter.step(shownWeight, -1, weightUnitOrNull)) },
                            onIncrement = { onWeightChange(WeightFormatter.step(shownWeight, +1, weightUnitOrNull)) },
                            onReset = { onWeightChange(null) },
                            fewerDescription = "Less weight",
                            moreDescription = "More weight",
                            canDecrement = !WeightFormatter.atFloor(shownWeight, weightUnitOrNull),
                        )
                        if (suggested != null) {
                            SuggestionNote(pinnedKg = pinnedWeight, suggestedKg = suggested, unit = weightUnitOrNull)
                        }
                    }
                    entry.exercise.equipment == Equipment.BODYWEIGHT -> Text(
                        "Bodyweight",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> Unit
                }
            },
        ) {
            Text(
                entry.exercise.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.exercise.isTimed) {
                Text(
                    formatQuantity(60, true),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Null while the suggester is still building: there is no number to step from yet.
                val baseReps = entry.reps ?: suggester?.typicalReps
                ValueStepper(
                    text = entry.reps?.toString() ?: suggester?.let { "${it.repMin}–${it.repMax}" } ?: "–",
                    pinned = entry.reps != null,
                    unit = "reps",
                    onDecrement = {
                        if (baseReps != null) onRepsChange((baseReps - 1).coerceIn(PINNED_REPS))
                    },
                    onIncrement = {
                        if (baseReps != null) onRepsChange((baseReps + 1).coerceIn(PINNED_REPS))
                    },
                    onReset = { onRepsChange(null) },
                    fewerDescription = "One rep fewer",
                    moreDescription = "One rep more",
                    canDecrement = baseReps != null && baseReps > PINNED_REPS.first,
                    canIncrement = baseReps != null && baseReps < PINNED_REPS.last,
                )
            }
        }
    }
}

