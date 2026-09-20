package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
internal fun PlanPreviewMenu(
    hasSavedWorkouts: Boolean,
    onAddExercise: () -> Unit,
    onLoadWorkout: () -> Unit,
    onAppendWorkout: () -> Unit,
    onSaveWorkout: () -> Unit,
    onRandomize: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Workout options")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Add an exercise...") }, onClick = { expanded = false; onAddExercise() })
            DropdownMenuItem(
                text = { Text("Load a workout...") },
                enabled = hasSavedWorkouts,
                onClick = { expanded = false; onLoadWorkout() },
            )
            DropdownMenuItem(
                text = { Text("Append a workout...") },
                enabled = hasSavedWorkouts,
                onClick = { expanded = false; onAppendWorkout() },
            )
            if (!hasSavedWorkouts) {
                DropdownMenuItem(
                    text = { Text("No saved workouts", style = MaterialTheme.typography.labelSmall) },
                    enabled = false,
                    onClick = {},
                )
            }
            DropdownMenuItem(text = { Text("Save as workout...") }, onClick = { expanded = false; onSaveWorkout() })
            DropdownMenuItem(text = { Text("Randomize me!") }, onClick = { expanded = false; onRandomize() })
        }
    }
}
