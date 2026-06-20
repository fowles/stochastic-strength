package io.github.fowles.stochastic_strength.ui.workout

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
internal fun SetActionsMenu(
    weightAdjustable: Boolean,
    onAdjustWeight: () -> Unit,
    onSwapNoEquipment: () -> Unit,
    onSwapDislike: () -> Unit,
    onEndExercise: () -> Unit,
    onStopWorkout: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Filled.MoreVert, contentDescription = "Set options")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("Adjust weight") },
            enabled = weightAdjustable,
            onClick = { expanded = false; onAdjustWeight() },
        )
        DropdownMenuItem(
            text = { Text("Swap — no equipment") },
            onClick = { expanded = false; onSwapNoEquipment() },
        )
        DropdownMenuItem(
            text = { Text("Swap — don't like it") },
            onClick = { expanded = false; onSwapDislike() },
        )
        DropdownMenuItem(
            text = { Text("End exercise") },
            onClick = { expanded = false; onEndExercise() },
        )
        DropdownMenuItem(
            text = { Text("Stop workout") },
            onClick = { expanded = false; onStopWorkout() },
        )
    }
}
