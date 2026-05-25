package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.data.model.SetFeedback

@Composable
fun WorkoutScreen(
    onWorkoutDone: (Long) -> Unit,
    viewModel: WorkoutViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state) {
        val s = state
        if (s is WorkoutState.Done) onWorkoutDone(s.sessionId)
    }

    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            when (val s = state) {
                WorkoutState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is WorkoutState.PlanPreview -> PlanPreviewContent(
                    state = s,
                    onStart = viewModel::startFirstExercise,
                    onReplace = viewModel::replaceExercise,
                )
                is WorkoutState.ActiveSet -> ActiveSetContent(
                    state = s,
                    onFeedback = viewModel::recordFeedback,
                    onDislike = viewModel::dislikeCurrentExercise,
                    onNoEquipment = viewModel::markNoEquipmentHere,
                )
                is WorkoutState.Resting -> RestingContent(
                    state = s,
                    onSkipRest = viewModel::skipRest,
                    onUndo = viewModel::undoLastSet,
                )
                is WorkoutState.Done -> {}
            }
        }
    }
}

@Composable
private fun PlanPreviewContent(
    state: WorkoutState.PlanPreview,
    onStart: () -> Unit,
    onReplace: (index: Int, reason: ExerciseRemovalReason) -> Unit,
) {
    val plan = state.plan
    val totalSets = plan.exercises.sumOf { it.state.currentSets }
    val durationMin = plan.estimatedDurationSeconds / 60

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
        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(plan.exercises, key = { it.exercise.id }) { planned ->
                ExercisePreviewRow(
                    planned = planned,
                    onReplace = { reason -> onReplace(plan.exercises.indexOf(planned), reason) },
                    modifier = Modifier.animateItem(),
                )
                HorizontalDivider()
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text("Let's Go")
        }
    }
}

@Composable
private fun ExercisePreviewRow(
    planned: io.github.fowles.stochastic_strength.domain.model.PlannedExercise,
    onReplace: (ExerciseRemovalReason) -> Unit,
    modifier: Modifier = Modifier,
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
            modifier = modifier,
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
            modifier = modifier,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(vertical = 12.dp),
            ) {
                Text(planned.exercise.name, style = MaterialTheme.typography.titleMedium)
                val st = planned.state
                val detail = buildString {
                    append("${st.currentSets} sets × ${st.currentReps} reps")
                    if (st.currentWeight > 0f) append(" · %.1f kg".format(st.currentWeight))
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

@Composable
private fun ActiveSetContent(
    state: WorkoutState.ActiveSet,
    onFeedback: (SetFeedback) -> Unit,
    onDislike: () -> Unit,
    onNoEquipment: () -> Unit,
) {
    val exercise = state.plannedExercise.exercise
    val exerciseState = state.plannedExercise.state
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(exercise.name, style = MaterialTheme.typography.headlineMedium)
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Don't like this exercise") },
                        onClick = { onDislike(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Don't have this equipment here") },
                        onClick = { onNoEquipment(); showMenu = false },
                    )
                }
            }
        }

        Text(
            "Set ${state.setIndex + 1} of ${state.totalSets}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.weight(1f))

        if (exerciseState.currentWeight > 0f) {
            Text(
                "%.1f kg".format(exerciseState.currentWeight),
                style = MaterialTheme.typography.displaySmall,
            )
        }
        Text(
            "${exerciseState.currentReps} reps",
            style = MaterialTheme.typography.displaySmall,
        )

        Spacer(Modifier.weight(1f))

        Text("How did that feel?", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(12.dp))
        FeedbackButtons(onFeedback = onFeedback)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FeedbackButtons(onFeedback: (SetFeedback) -> Unit) {
    val errorColor = ButtonDefaults.outlinedButtonColors(
        contentColor = MaterialTheme.colorScheme.error,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.TOO_HARD) },
                colors = errorColor,
                modifier = Modifier.weight(1f),
            ) { Text("Too Hard") }
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.HURT) },
                colors = errorColor,
                modifier = Modifier.weight(1f),
            ) { Text("Hurt") }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.RIR_1_2) },
                modifier = Modifier.weight(1f),
            ) { Text("1-2 left") }
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.RIR_3_5) },
                modifier = Modifier.weight(1f),
            ) { Text("3-5 left") }
            OutlinedButton(
                onClick = { onFeedback(SetFeedback.RIR_5_PLUS) },
                modifier = Modifier.weight(1f),
            ) { Text("5+ left") }
        }
    }
}

private fun SetFeedback.displayLabel() = when (this) {
    SetFeedback.TOO_HARD -> "Too Hard"
    SetFeedback.HURT -> "Hurt"
    SetFeedback.RIR_1_2 -> "1–2 left"
    SetFeedback.RIR_3_5 -> "3–5 left"
    SetFeedback.RIR_5_PLUS -> "5+ left"
}

@Composable
private fun RestingContent(
    state: WorkoutState.Resting,
    onSkipRest: () -> Unit,
    onUndo: () -> Unit,
) {
    val plan = state.plan
    val totalSets = plan.exercises[state.exerciseIndex].state.currentSets
    val nextSet = state.completedSetIndex + 1

    val upNextLabel = when {
        nextSet < totalSets -> "Set ${nextSet + 1} of $totalSets — ${plan.exercises[state.exerciseIndex].exercise.name}"
        state.exerciseIndex + 1 < plan.exercises.size ->
            "Next exercise: ${plan.exercises[state.exerciseIndex + 1].exercise.name}"
        else -> "Last set — almost done!"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Rest", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Logged: ${state.lastFeedback.displayLabel()}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "${state.secondsRemaining}",
            style = MaterialTheme.typography.displayLarge,
        )
        Text("seconds", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(32.dp))
        Text(
            upNextLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onUndo) { Text("Undo") }
            OutlinedButton(onClick = onSkipRest) { Text("Skip Rest") }
        }
    }
}
