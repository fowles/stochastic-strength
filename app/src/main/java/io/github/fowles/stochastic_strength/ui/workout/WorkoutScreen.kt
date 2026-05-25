package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.fowles.stochastic_strength.data.model.Equipment
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
                is WorkoutState.NewLocationSetup -> NewLocationSetupContent(
                    onSave = viewModel::saveNewLocation,
                    onSkip = viewModel::skipLocationSetup,
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
                )
                is WorkoutState.Done -> {}
            }
        }
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

@Composable
private fun NewLocationSetupContent(
    onSave: (name: String, equipment: Set<Equipment>) -> Unit,
    onSkip: () -> Unit,
) {
    val equipmentChoices = remember { Equipment.entries.filter { it != Equipment.BODYWEIGHT } }
    var locationName by remember { mutableStateOf("") }
    var selectedEquipment by remember { mutableStateOf(emptySet<Equipment>()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("New Location", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Name this location and select the equipment available here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = locationName,
            onValueChange = { locationName = it },
            label = { Text("Location name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        Text("Available equipment:", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        equipmentChoices.forEach { equipment ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = equipment in selectedEquipment,
                    onCheckedChange = { checked ->
                        selectedEquipment = if (checked) selectedEquipment + equipment
                        else selectedEquipment - equipment
                    },
                )
                Text(
                    equipment.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = { onSave(locationName.trim(), selectedEquipment) },
            enabled = locationName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save & Start Workout") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Skip") }
    }
}

@Composable
private fun RestingContent(
    state: WorkoutState.Resting,
    onSkipRest: () -> Unit,
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
        OutlinedButton(onClick = onSkipRest) { Text("Skip Rest") }
    }
}
