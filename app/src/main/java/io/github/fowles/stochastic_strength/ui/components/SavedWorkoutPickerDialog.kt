package io.github.fowles.stochastic_strength.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.fowles.stochastic_strength.domain.CircuitStructure
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutDetail
import io.github.fowles.stochastic_strength.domain.model.SavedWorkoutEntry

/** "1 exercise" / "3 exercises" — shared by every saved-workout list. */
fun exerciseCountLabel(n: Int): String = "$n exercise" + if (n == 1) "" else "s"

/** "5 exercises", plus " · 1 circuit" when the workout has any. */
fun workoutSubtitle(entries: List<SavedWorkoutEntry>): String {
    val base = exerciseCountLabel(entries.size)
    return when (val circuits = CircuitStructure.circuitCount(entries)) {
        0 -> base
        1 -> "$base · 1 circuit"
        else -> "$base · $circuits circuits"
    }
}

@Composable
fun SavedWorkoutPickerDialog(
    title: String,
    workouts: List<SavedWorkoutDetail>?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            if (workouts == null) {
                // Still waiting on the first emission — don't flash the empty state.
                LoadingBox(PaddingValues(0.dp), Modifier.height(96.dp))
            } else if (workouts.isEmpty()) {
                Text("No saved workouts yet. Create one from Home → Workouts.")
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(workouts, key = { it.id }) { w ->
                        // Cards (not plain text rows) so it reads as a list of tap targets.
                        Card(
                            onClick = { onPick(w.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        w.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        workoutSubtitle(w.entries),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
