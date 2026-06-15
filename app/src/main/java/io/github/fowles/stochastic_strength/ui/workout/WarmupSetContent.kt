package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.fowles.stochastic_strength.data.model.WeightUnit

@Composable
internal fun WarmupSetContent(
    state: WorkoutState.ActiveSet,
    weightUnit: WeightUnit,
    onDone: () -> Unit,
) {
    val warmupSet = state.currentWarmupSet ?: return
    val exercise = state.plannedExercise.exercise
    val totalWarmups = state.plannedExercise.warmupSets.size
    ExerciseSetLayout(
        exercise = exercise,
        progressLabel = "Warm-up ${state.warmupSetIndex!! + 1} of $totalWarmups",
        progressColor = MaterialTheme.colorScheme.secondary,
        weight = warmupSet.weight,
        reps = warmupSet.reps,
        weightUnit = weightUnit,
    ) {
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.warmupSetIndex + 1 < totalWarmups) "Next Warm-up" else "Start Working Sets")
        }
    }
}
